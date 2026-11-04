/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jkiss.dbeaver.ui.ai.workbench;

import org.jkiss.code.NotNull;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Plain-main fixture tests; all files and child processes are cleaned up. */
public final class EngineeringProfileRunnerTest {
    private EngineeringProfileRunnerTest() {
    }

    public static void main(@NotNull String[] args) throws Exception {
        if (args.length > 0) {
            child(args);
            return;
        }
        Path directory = Files.createTempDirectory("dbeaver-profile-test-");
        try {
            Path csv = directory.resolve("input.csv");
            Files.writeString(csv, "id,amount\nprivate-example,1\nsecond,\n", StandardCharsets.UTF_8);
            rejects(() -> EngineeringProfileRunner.run(csv, "shell", () -> false));
            rejects(() -> EngineeringProfileRunner.run(csv, "python", () -> true));
            rejects(() -> EngineeringProfileRunner.run(directory, "python", () -> false));
            Path wrong = directory.resolve("input.txt");
            Files.writeString(wrong, "nothing");
            rejects(() -> EngineeringProfileRunner.run(wrong, "python", () -> false));
            Path large = directory.resolve("large.csv");
            try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) {
                file.setLength(10L * 1024 * 1024 + 1);
            }
            rejects(() -> EngineeringProfileRunner.run(large, "python", () -> false));
            BasicFileAttributes before = Files.readAttributes(csv, BasicFileAttributes.class);
            Files.setLastModifiedTime(csv, FileTime.fromMillis(before.lastModifiedTime().toMillis() + 2000));
            rejects(() -> EngineeringProfileRunner.verifyUnchanged(csv, before));
            int engines = 0;
            for (String engine : List.of("python", "r")) {
                if (!available(engine.equals("python") ? "python3" : "Rscript")) {
                    continue;
                }
                Map<String, Object> result = EngineeringProfileRunner.run(csv, engine, () -> false);
                check(result.get("valuesIncluded").equals(false), "Values flag");
                String profile = (String) result.get("profile");
                check(!profile.contains("private-example") && !profile.contains(directory.toString()), "Profile redaction");
                check(result.get("engine").equals(engine), "Engine metadata");
                try {
                    result.put("engine", "other");
                    throw new AssertionError("Mutable result");
                } catch (UnsupportedOperationException expected) {
                    // Immutable tool payload.
                }
                Path tsv = directory.resolve("input.tsv");
                Files.writeString(tsv, "id\tamount\nمرحبا\t1\n", StandardCharsets.UTF_8);
                String tabProfile = (String) EngineeringProfileRunner.run(tsv, engine, () -> false).get("profile");
                check(!tabProfile.contains("مرحبا") && tabProfile.contains("amount"), "TSV profile");
                engines++;
            }
            check(engines > 0, "No existing interpreter available for fixture tests");
            // Exercise process bounds with this test class, not an arbitrary external script.
            rejects(() -> process(command("child-overflow"), () -> false, Duration.ofSeconds(2)));
            long started = System.nanoTime();
            rejects(() -> process(command("child-wait"), () -> false, Duration.ofMillis(150)));
            check(System.nanoTime() - started < Duration.ofSeconds(4).toNanos(), "Deadline cleanup");
            AtomicInteger polls = new AtomicInteger();
            rejects(() -> process(command("child-wait"), () -> polls.incrementAndGet() > 3, Duration.ofSeconds(2)));
            Path childPid = directory.resolve("child.pid");
            Process unrelated = new ProcessBuilder(command("child-wait"))
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                rejects(() -> process(command("child-spawn", childPid.toString()),
                    () -> Files.exists(childPid), Duration.ofSeconds(3)));
                check(Files.exists(childPid), "Descendant fixture did not start");
                long pid = Long.parseLong(Files.readString(childPid));
                long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < end) {
                    Thread.sleep(20);
                }
                check(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "Descendant survived cleanup");
                check(unrelated.isAlive(), "Unrelated process was stopped");
            } finally {
                unrelated.destroyForcibly();
                unrelated.waitFor();
            }
            System.out.println("Engineering profile runner tests passed; existing engines checked: " + engines);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void child(@NotNull String[] args) throws Exception {
        if (args[0].equals("child-overflow")) {
            System.out.write(new byte[17 * 1024]);
            System.out.flush();
        } else if (args[0].equals("child-spawn")) {
            Process descendant = new ProcessBuilder(command("child-wait"))
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Files.writeString(Path.of(args[1]), Long.toString(descendant.pid()));
        }
        Thread.sleep(30000);
    }

    @NotNull
    private static List<String> command(@NotNull String... arguments) {
        String suffix = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? ".exe" : "";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java" + suffix).toString(),
            "-classpath", System.getProperty("java.class.path"), EngineeringProfileRunnerTest.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    @NotNull
    private static byte[] process(@NotNull List<String> command, @NotNull BooleanSupplier canceled,
        @NotNull Duration timeout) throws Exception {
        Method method = EngineeringProfileRunner.class.getDeclaredMethod("runProcess", List.class, BooleanSupplier.class, long.class);
        method.setAccessible(true);
        try {
            return (byte[]) method.invoke(null, command, canceled, timeout.toNanos());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static boolean available(@NotNull String executable) throws Exception {
        Method method = EngineeringProfileRunner.class.getDeclaredMethod("interpreter", String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, executable);
            return true;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IOException) {
                return false;
            }
            throw e;
        }
    }

    private static void rejects(@NotNull Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected a rejected operation");
        } catch (IOException expected) {
            check(!expected.getMessage().contains("private-example"), "Error leaked input value");
        }
    }

    private static void check(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
