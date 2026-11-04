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
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Temporary source fixtures exercise review, hash guards, atomic undo and compile-only verification. */
public final class WorkbenchSourceToolsTest {
    private static final String ADDON = "plugins/org.jkiss.dbeaver.ui.ai.workbench/";

    private WorkbenchSourceToolsTest() {
    }

    public static void main(@NotNull String[] args) throws Exception {
        Path directory = Files.createTempDirectory("dbeaver-source-test-");
        Path root = directory.resolve("checkout");
        Path other = directory.resolve("other");
        try {
            fixture(root);
            fixture(other);
            String file = ADDON + "src/Example.java";
            String original = "public class Example { public static int count() { return 1; } }\n";
            write(root, file, original);
            Map<String, Object> selected = WorkbenchSourceTools.choose(root);
            check(selected.get("workspaceSelected").equals(true), "Workspace selection");
            Map<String, Object> read = WorkbenchSourceTools.read(file, 0);
            check(read.get("content").equals(original), "Source content");
            check(!read.toString().contains(root.toString()), "Private workspace path");
            rejects(() -> WorkbenchSourceTools.read("../outside.txt", 0));
            rejects(() -> WorkbenchSourceTools.read(ADDON + "src/../pom.xml", 0));
            rejects(() -> WorkbenchSourceTools.read("plugins/other/private.java", 0));
            rejects(() -> WorkbenchSourceTools.read(ADDON + "resources/.env", 0));
            rejects(() -> WorkbenchSourceTools.read(file, -1));
            rejects(() -> WorkbenchSourceTools.replace(file, "0".repeat(64), "return 1", "return 2"));
            rejects(() -> WorkbenchSourceTools.replace("scripts/build-workbench-tools.py",
                hash("not executed"), "not", "executed"));
            Map<String, Object> edit = WorkbenchSourceTools.replace(file, (String) read.get("sha256"), "return 1", "return 2");
            check(Files.readString(root.resolve(file)).contains("return 2"), "Exact edit applied");
            check(edit.get("installed").equals(false), "Edit does not deploy");
            rejects(() -> WorkbenchSourceTools.choose(other));
            rejects(() -> WorkbenchSourceTools.replace(file, (String) edit.get("sha256"),
                Files.readString(root.resolve(file)), ""));
            String applied = Files.readString(root.resolve(file));
            Files.writeString(root.resolve(file), applied + "// external edit\n");
            String external = (String) WorkbenchSourceTools.read(file, 0).get("sha256");
            rejects(() -> WorkbenchSourceTools.rollback(file, external));
            rejects(() -> WorkbenchSourceTools.replace(file, external, "return 2", "return 3"));
            check(Files.readString(root.resolve(file)).contains("external edit"), "External edit preserved");
            Files.writeString(root.resolve(file), applied);
            WorkbenchSourceTools.rollback(file, (String) edit.get("sha256"));
            check(Files.readString(root.resolve(file)).equals(original), "Original restored");
            // An early rejected or successful edit leaves no staging sibling behind.
            try (var paths = Files.walk(root)) {
                check(paths.noneMatch(path -> path.getFileName().toString().startsWith(".workbench-edit-")), "Atomic temp cleanup");
            }
            String repeated = ADDON + "resources/repeated.md";
            write(root, repeated, "match match");
            rejects(() -> WorkbenchSourceTools.replace(repeated, hash("match match"), "match", "other"));
            Path outside = directory.resolve("outside.txt");
            Files.writeString(outside, "outside-data");
            Path link = root.resolve(ADDON + "resources/link.md");
            Files.createSymbolicLink(link, outside);
            rejects(() -> WorkbenchSourceTools.read(ADDON + "resources/link.md", 0));
            Path linkedDirectory = root.resolve(ADDON + "src/linked");
            Files.createSymbolicLink(linkedDirectory, directory);
            rejects(() -> WorkbenchSourceTools.read(ADDON + "src/linked/outside.txt", 0));
            String longFile = ADDON + "resources/long.md";
            write(root, longFile, "α".repeat(6000));
            Map<String, Object> first = WorkbenchSourceTools.read(longFile, 0);
            check(((String) first.get("content")).length() == 4000, "Bounded source chunk");
            check(((Number) first.get("nextOffset")).intValue() == 4000, "Chunk continuation");
            String privateText = ADDON + "resources/private.md";
            write(root, privateText, root + "\n" + System.getProperty("user.home"));
            Map<String, Object> redacted = WorkbenchSourceTools.read(privateText, 0);
            check(redacted.get("redacted").equals(true) && !redacted.toString().contains(root.toString()), "Read path redaction");
            // Four near-limit originals fit; a fifth must fail without changing it.
            for (int index = 0; index < 5; index++) {
                String name = ADDON + "resources/budget" + index + ".md";
                String content = "MARK" + index + "x".repeat(65515);
                write(root, name, content);
                if (index < 4) {
                    WorkbenchSourceTools.replace(name, hash(content), "MARK" + index, "EDIT" + index);
                } else {
                    rejects(() -> WorkbenchSourceTools.replace(name, hash(content), "MARK4", "EDIT4"));
                    check(Files.readString(root.resolve(name)).equals(content), "Budget failure preserves source");
                }
            }
            for (int index = 0; index < 4; index++) {
                String name = ADDON + "resources/budget" + index + ".md";
                WorkbenchSourceTools.rollback(name, (String) WorkbenchSourceTools.read(name, 0).get("sha256"));
            }
            Path installation = installation();
            if (installation != null) {
                Map<String, Object> verified = WorkbenchSourceTools.verify(installation, () -> false);
                check(verified.get("verificationPassed").equals(true), "Valid source compilation");
                check(verified.get("testsRun").equals(false) && verified.get("codeExecuted").equals(false), "Compile-only labels");
                check(!verified.toString().contains(root.toString()), "Verification path redaction");
                write(root, file, "public class Example { invalid source }\n");
                Map<String, Object> failed = WorkbenchSourceTools.verify(installation, () -> false);
                check(failed.get("verificationPassed").equals(false), "Compilation error is failure");
                check(!((List<?>) failed.get("diagnostics")).isEmpty(), "Relative error locations");
                check(!failed.toString().contains(root.toString()), "Compiler paths excluded");
                rejects(() -> WorkbenchSourceTools.verify(installation, () -> true));
            }
            WorkbenchSourceTools.choose(other);
            WorkbenchSourceTools.stop();
            rejects(WorkbenchSourceTools::list);
            System.out.println("Source workspace tests passed; secure edits, rollback, scope and compile-only checks exercised");
        } finally {
            WorkbenchSourceTools.stop();
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void fixture(@NotNull Path root) throws IOException {
        Files.createDirectories(root.resolve(ADDON + "src"));
        Files.createDirectories(root.resolve(ADDON + "resources"));
        Files.createDirectories(root.resolve("scripts"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve(ADDON + "pom.xml"), "<project/>");
        Files.writeString(root.resolve("scripts/build-workbench-tools.py"), "raise RuntimeError('MUST_NOT_EXECUTE')");
    }

    private static void write(@NotNull Path root, @NotNull String relative, @NotNull String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @NotNull
    private static String hash(@NotNull String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Nullable
    private static Path installation() {
        Path path = Path.of(System.getProperty("java.home"));
        for (int index = 0; path != null && index < 6; index++, path = path.getParent()) {
            if (Files.isDirectory(path.resolve("plugins"))) {
                return path;
            }
        }
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            path = Path.of(entry).toAbsolutePath().getParent();
            if (path != null && path.getFileName().toString().equals("plugins")) {
                return path.getParent();
            }
        }
        return null;
    }

    private static void rejects(@NotNull Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected source operation rejection");
        } catch (IOException expected) {
            // Expected policy or concurrency rejection.
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
