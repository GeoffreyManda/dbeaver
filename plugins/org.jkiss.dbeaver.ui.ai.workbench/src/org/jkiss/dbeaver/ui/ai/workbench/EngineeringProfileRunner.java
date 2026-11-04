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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Runs only bundled, read-only CSV profilers using an existing local interpreter. */
public final class EngineeringProfileRunner {
    private static final long MAX_INPUT_BYTES = 10L * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);
    private static final int POLL_MILLISECONDS = 20;
    private static final String RESOURCE_ROOT = "/resources/templates/";

    private EngineeringProfileRunner() {
    }

    @NotNull
    public static Map<String, Object> run(@NotNull Path selectedInput, @NotNull String engine,
        @NotNull BooleanSupplier canceled) throws IOException {
        if (!"python".equals(engine) && !"r".equals(engine)) {
            throw new IOException("Supported profiling engines are python and r");
        }
        if (canceled.getAsBoolean()) {
            throw new IOException("Profiling canceled");
        }
        Path input = selectedInput.toAbsolutePath().normalize();
        String name = input.getFileName() == null ? "" : input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv") && !name.endsWith(".tsv")) {
            throw new IOException("Select a CSV or TSV file");
        }
        BasicFileAttributes before = attributes(input);
        if (!before.isRegularFile() || before.size() > MAX_INPUT_BYTES) {
            throw new IOException("Select a regular CSV or TSV file no larger than 10 MiB");
        }
        String delimiter = name.endsWith(".tsv") ? "\t" : ",";
        String resource = engine.equals("python") ? "python/profile_csv.py" : "r/profile_csv.R";
        String source;
        try (InputStream stream = EngineeringProfileRunner.class.getResourceAsStream(RESOURCE_ROOT + resource)) {
            if (stream == null) {
                throw new IOException("Bundled profiler is unavailable");
            }
            byte[] template = stream.readNBytes(32 * 1024);
            if (stream.read() != -1) {
                throw new IOException("Bundled profiler exceeds its resource limit");
            }
            source = new String(template, StandardCharsets.UTF_8);
        }
        String executable = interpreter(engine.equals("python") ? "python3" : "Rscript");
        List<String> command = engine.equals("python")
            ? List.of(executable, "-I", "-c", source, input.toString(), "--delimiter", delimiter)
            : List.of(executable, "--vanilla", "--default-packages=utils", "-e", source, input.toString(), delimiter);
        byte[] output = runProcess(command, canceled, PROCESS_TIMEOUT.toNanos());
        verifyUnchanged(input, before);
        return Map.of("engine", engine, "format", engine.equals("python") ? "json" : "csv",
            "profile", new String(output, StandardCharsets.UTF_8), "bytes", before.size(),
            "valuesIncluded", false, "sourceChanged", false);
    }

    @NotNull
    private static BasicFileAttributes attributes(@NotNull Path input) throws IOException {
        try {
            return Files.readAttributes(input, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException e) {
            // OS errors can contain private paths; do not attach them to the tool response.
            throw new IOException("Selected source is not accessible");
        }
    }

    static void verifyUnchanged(@NotNull Path input, @NotNull BasicFileAttributes before) throws IOException {
        BasicFileAttributes after = attributes(input);
        if (!after.isRegularFile() || before.size() != after.size()
            || !before.lastModifiedTime().equals(after.lastModifiedTime())
            || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("Selected source changed during profiling; no profile returned");
        }
    }

    @NotNull
    private static String interpreter(@NotNull String program) throws IOException {
        List<Path> directories = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (!directory.isBlank()) {
                    directories.add(Path.of(directory));
                }
            }
        }
        directories.addAll(List.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"), Path.of("/usr/bin")));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (Path directory : directories) {
            Path candidate = directory.resolve(windows ? program + ".exe" : program);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        throw new IOException("Required local profiling interpreter is not installed or not on PATH");
    }

    @NotNull
    private static byte[] runProcess(@NotNull List<String> command, @NotNull BooleanSupplier canceled,
        long timeoutNanos) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException | SecurityException e) {
            throw new IOException("Unable to start the local profiling interpreter");
        }
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        CompletableFuture<byte[]> output = new CompletableFuture<>();
        AtomicBoolean overflow = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] block = new byte[2048];
                int count;
                while ((count = stream.read(block)) != -1) {
                    if (bytes.size() + count > MAX_OUTPUT_BYTES) {
                        overflow.set(true);
                        throw new IOException("Profiling output limit exceeded");
                    }
                    bytes.write(block, 0, count);
                }
                output.complete(bytes.toByteArray());
            } catch (IOException e) {
                output.completeExceptionally(new IOException("Unable to read profiling output"));
            }
        }, "DBeaver bundled profiler output");
        reader.setDaemon(true);
        long deadline = System.nanoTime() + timeoutNanos;
        try {
            process.getOutputStream().close();
            reader.start();
            while (true) {
                process.descendants().forEach(child -> descendants.put(child.pid(), child));
                if (canceled.getAsBoolean()) {
                    throw new IOException("Profiling canceled");
                }
                if (overflow.get()) {
                    throw new IOException("Profiling output exceeds 16 KiB");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("Profiling exceeded its process deadline");
                }
                if (!process.isAlive()) {
                    if (process.exitValue() != 0) {
                        throw new IOException("Profiling failed; check the CSV structure and local runtime");
                    }
                    try {
                        return output.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_MILLISECONDS)), TimeUnit.NANOSECONDS);
                    } catch (TimeoutException e) {
                        // Keep checking cancellation while a descendant still holds the pipe.
                    }
                }
                process.waitFor(POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Profiling interrupted");
        } catch (ExecutionException e) {
            throw new IOException(overflow.get() ? "Profiling output exceeds 16 KiB" : "Unable to read profiling output");
        } finally {
            // Kill only this child tree, including descendants captured before the parent exited.
            process.descendants().forEach(child -> descendants.put(child.pid(), child));
            for (ProcessHandle child : descendants.values()) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                process.getInputStream().close();
                process.waitFor(1, TimeUnit.SECONDS);
                reader.join(1000);
            } catch (IOException ignored) {
                // Output is already suppressed and the child tree has been terminated.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
