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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** A native-selected addon source workspace, with reviewable edits and in-memory undo. */
public final class WorkbenchSourceTools {
    private static final String ADDON = "plugins/org.jkiss.dbeaver.ui.ai.workbench/";
    private static final String BUILDER = "scripts/build-workbench-tools.py";
    private static final int MAX_FILE = 64 * 1024;
    private static final int MAX_UNDO = 256 * 1024;
    private static final int READ_CHARS = 4000;
    private static final int MAX_FILES = 128;
    private static final Set<String> METADATA = Set.of("plugin.xml", "plugin.properties", "pom.xml",
        "build.properties", "META-INF/MANIFEST.MF");
    private static final String ECJ_VERSION = "3.43.0";
    private static final String ECJ_SHA256 = "c786468c65e906498e7e36ece4e0d04c6d3dd34c9a61b34a3a5b512801911a82";
    private static final Map<String, Undo> UNDO = new LinkedHashMap<>();
    private static Path root;
    private static Path selectedAlias;
    private static Object rootKey;
    private static String sessionId;

    private WorkbenchSourceTools() {
    }

    @NotNull
    public static synchronized Map<String, Object> choose(@NotNull Path selectedDirectory) throws IOException {
        Path candidate = selectedDirectory.toRealPath();
        if (root != null && !root.equals(candidate) && !UNDO.isEmpty()) {
            throw new IOException("Roll back tracked edits before switching source workspaces");
        }
        if (!Files.isRegularFile(candidate.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Choose the DBeaver source repository root");
        }
        final Path oldRoot = root;
        Path oldAlias = selectedAlias;
        Object oldKey = rootKey;
        final String oldSession = sessionId;
        root = candidate;
        selectedAlias = selectedDirectory.toAbsolutePath().normalize();
        rootKey = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        sessionId = oldRoot != null && oldRoot.equals(candidate) ? oldSession : UUID.randomUUID().toString();
        try {
            inFile(ADDON + "pom.xml", (directory, leaf) -> readBytes(directory, leaf));
        } catch (IOException e) {
            root = oldRoot;
            selectedAlias = oldAlias;
            rootKey = oldKey;
            sessionId = oldSession;
            throw new IOException("Choose a source checkout containing the AI workbench addon");
        }
        return list(0);
    }

    @NotNull
    public static synchronized Map<String, Object> list() throws IOException {
        return list(0);
    }

    @NotNull
    public static synchronized Map<String, Object> list(int offset) throws IOException {
        requireRoot();
        if (offset < 0 || offset > MAX_FILES) {
            throw new IOException("Invalid source listing offset");
        }
        Scan scan = scan();
        List<String> page = new ArrayList<>();
        int next = offset;
        int budget = 24000 - UNDO.keySet().stream().mapToInt(path -> path.length() * 6 + 64).sum();
        while (next < scan.files().size() && page.size() < 32) {
            String path = scan.files().get(next);
            int cost = path.length() * 6 + 64;
            if (cost > budget) {
                break;
            }
            page.add(path);
            budget -= cost;
            next++;
        }
        return Map.of("workspaceSelected", true, "sessionId", sessionId, "files", List.copyOf(page),
            "nextOffset", next < scan.files().size() ? next : -1, "truncated", scan.truncated(),
            "pendingEdits", List.copyOf(UNDO.keySet()), "rollbackBytes", undoBytes(),
            "rollbackLifetime", "until addon stop; edited files remain on disk");
    }

    @NotNull
    public static synchronized Map<String, Object> read(@NotNull String relativePath, int offset) throws IOException {
        byte[] bytes = sourceBytes(relativePath);
        String content = text(bytes);
        if (offset < 0 || offset > content.length()
            || offset < content.length() && Character.isLowSurrogate(content.charAt(offset))) {
            throw new IOException("Invalid source chunk offset");
        }
        int end = Math.min(content.length(), offset + READ_CHARS);
        if (end < content.length() && end > offset && Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        String chunk = redact(content.substring(offset, end));
        return Map.of("relativePath", relativePath, "sha256", hash(bytes), "bytes", bytes.length,
            "content", chunk, "offset", offset, "nextOffset", end < content.length() ? end : -1,
            "redacted", !chunk.equals(content.substring(offset, end)), "writable", writable(relativePath));
    }

    @NotNull
    public static synchronized Map<String, Object> replace(@NotNull String relativePath,
        @NotNull String expectedSha256, @NotNull String before, @NotNull String after) throws IOException {
        if (!writable(relativePath) || before.isEmpty() || before.length() > 8192 || after.length() > 8192) {
            throw new IOException("Edit requires an allowed file and a bounded nonempty exact match");
        }
        byte[] original = sourceBytes(relativePath);
        String oldHash = hash(original);
        expectHash(expectedSha256, oldHash);
        Undo prior = UNDO.get(relativePath);
        if (prior != null && !prior.latestHash().equals(oldHash)) {
            throw new IOException("Source changed outside this edit session; preserve and review those changes");
        }
        String content = text(original);
        int start = content.indexOf(before);
        if (start < 0 || content.indexOf(before, start + 1) >= 0) {
            throw new IOException("Expected source segment must occur exactly once");
        }
        String changed = content.substring(0, start) + after + content.substring(start + before.length());
        byte[] replacement = changed.getBytes(StandardCharsets.UTF_8);
        if (changed.isBlank() || replacement.length > MAX_FILE || changed.equals(content)) {
            throw new IOException("Source edit must change content without deleting the entire file");
        }
        if (prior == null && (UNDO.size() >= 8 || undoBytes() + original.length > MAX_UNDO)) {
            throw new IOException("In-memory rollback limit reached; review or roll back existing edits");
        }
        String newHash = hash(replacement);
        atomicReplace(relativePath, oldHash, replacement);
        UNDO.put(relativePath, new Undo(prior == null ? original : prior.original(), newHash));
        return Map.of("relativePath", relativePath, "previousSha256", oldHash, "sha256", newHash,
            "applied", true, "reviewBefore", redact(preview(before)), "reviewAfter", redact(preview(after)),
            "rollbackAvailable", true, "rollbackBytes", undoBytes(), "installed", false);
    }

    @NotNull
    public static synchronized Map<String, Object> rollback(@NotNull String relativePath,
        @NotNull String expectedSha256) throws IOException {
        Undo undo = UNDO.get(relativePath);
        if (undo == null) {
            throw new IOException("No tracked source edit exists for this relative path");
        }
        expectHash(expectedSha256, undo.latestHash());
        atomicReplace(relativePath, undo.latestHash(), undo.original());
        UNDO.remove(relativePath);
        return Map.of("relativePath", relativePath, "sha256", hash(undo.original()),
            "rolledBack", true, "remainingEdits", UNDO.size(), "installed", false);
    }

    /** Clears only session metadata, never edits or deletes workspace files. */
    public static synchronized void stop() {
        UNDO.clear();
        root = null;
        selectedAlias = null;
        rootKey = null;
        sessionId = null;
    }

    @NotNull
    private static byte[] sourceBytes(@NotNull String relativePath) throws IOException {
        requireRoot();
        validate(relativePath);
        return inFile(relativePath, WorkbenchSourceTools::readBytes);
    }

    private static void validate(@NotNull String relativePath) throws IOException {
        if (relativePath.isBlank() || relativePath.length() > 512 || relativePath.startsWith("/")
            || relativePath.contains("\\") || relativePath.contains(":")) {
            throw new IOException("Use an allowed repository-relative source path");
        }
        for (String part : relativePath.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")
                || part.startsWith(".") || part.codePoints().anyMatch(value ->
                    !(Character.isLetterOrDigit(value) || value == '.' || value == '_' || value == '-'))) {
                throw new IOException("Invalid relative source path");
            }
        }
        if (relativePath.equals(BUILDER)) {
            return;
        }
        if (!relativePath.startsWith(ADDON)) {
            throw new IOException("Source path is outside the selected addon scope");
        }
        String inner = relativePath.substring(ADDON.length());
        if (!(inner.startsWith("src/") || inner.startsWith("tests/") || inner.startsWith("resources/")
            || METADATA.contains(inner))) {
            throw new IOException("Source path is outside the selected addon scope");
        }
    }

    private static boolean writable(@NotNull String relativePath) {
        try {
            validate(relativePath);
            return !relativePath.equals(BUILDER);
        } catch (IOException e) {
            return false;
        }
    }

    private static void requireRoot() throws IOException {
        if (root == null) {
            throw new IOException("Choose a source workspace in the native directory dialog first");
        }
    }

    private static int undoBytes() {
        return UNDO.values().stream().mapToInt(value -> value.original().length).sum();
    }

    @NotNull
    private static String text(@NotNull byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (text.codePoints().anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')) {
            throw new IOException("Source must be UTF-8 text without binary control characters");
        }
        return text;
    }

    @NotNull
    private static String preview(@NotNull String text) {
        return text.length() > 1200 ? text.substring(0, 1200) + "…" : text;
    }

    @NotNull
    private static String redact(@NotNull String text) {
        String value = root == null ? text : text.replace(root.toString(), "<workspace>");
        if (selectedAlias != null) {
            value = value.replace(selectedAlias.toString(), "<workspace>");
        }
        String home = System.getProperty("user.home", "");
        return home.isEmpty() ? value : value.replace(home, "<home>");
    }

    @NotNull
    private static String hash(@NotNull byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void expectHash(@NotNull String expected, @NotNull String actual) throws IOException {
        if (!expected.matches("[0-9a-fA-F]{64}") || !actual.equalsIgnoreCase(expected)) {
            throw new IOException("Source SHA-256 changed; read it again before editing");
        }
    }

    @Nullable
    private static <T> T inFile(@NotNull String relativePath, @NotNull FileOperation<T> operation) throws IOException {
        String[] parts = relativePath.split("/");
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
            if (!(directory instanceof SecureDirectoryStream<Path> secure)) {
                throw new IOException("Filesystem does not support safe source operations");
            }
            Object actualKey = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
            if (rootKey == null || !Objects.equals(rootKey, actualKey)) {
                throw new IOException("Selected source root changed; choose it again");
            }
            return descend(secure, parts, 0, operation);
        } catch (SecurityException e) {
            throw new IOException("Source operation was not permitted");
        }
    }

    @Nullable
    private static <T> T descend(@NotNull SecureDirectoryStream<Path> directory, @NotNull String[] parts, int index,
        @NotNull FileOperation<T> operation) throws IOException {
        Path part = Path.of(parts[index]);
        if (index == parts.length - 1) {
            return operation.apply(directory, part);
        }
        try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS)) {
            return descend(child, parts, index + 1, operation);
        }
    }

    @NotNull
    private static byte[] readBytes(@NotNull SecureDirectoryStream<Path> directory, @NotNull Path leaf) throws IOException {
        BasicFileAttributes attributes = directory.getFileAttributeView(leaf, BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isRegularFile() || attributes.size() > MAX_FILE) {
            throw new IOException("Source file must be regular and no larger than 64 KiB");
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = directory.newByteChannel(leaf, options)) {
            ByteBuffer buffer = ByteBuffer.allocate(MAX_FILE + 1);
            while (channel.read(buffer) > 0) {
                if (!buffer.hasRemaining()) {
                    throw new IOException("Source file grew beyond 64 KiB");
                }
            }
            byte[] bytes = new byte[buffer.position()];
            buffer.flip();
            buffer.get(bytes);
            return bytes;
        }
    }

    private static void atomicReplace(@NotNull String relativePath, @NotNull String expected,
        @NotNull byte[] replacement) throws IOException {
        inFile(relativePath, (directory, leaf) -> {
            expectHash(expected, hash(readBytes(directory, leaf)));
            Path temporary = Path.of(".workbench-edit-" + UUID.randomUUID() + ".tmp");
            try {
                Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                try (SeekableByteChannel channel = directory.newByteChannel(temporary, options)) {
                    ByteBuffer bytes = ByteBuffer.wrap(replacement);
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                    if (channel instanceof FileChannel file) {
                        file.force(true);
                    }
                }
                PosixFileAttributeView originalMode = directory.getFileAttributeView(leaf,
                    PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (originalMode != null) {
                    directory.getFileAttributeView(temporary, PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).setPermissions(originalMode.readAttributes().permissions());
                }
                expectHash(expected, hash(readBytes(directory, leaf)));
                directory.move(temporary, directory, leaf);
            } finally {
                try {
                    directory.deleteFile(temporary);
                } catch (NoSuchFileException expectedMissing) {
                    // The atomic rename already consumed the temporary file.
                }
            }
            return null;
        });
    }

    @NotNull
    private static Scan scan() throws IOException {
        List<String> files = new ArrayList<>();
        int[] visited = {0};
        boolean[] truncated = {false};
        Files.walkFileTree(root.resolve(ADDON), new SimpleFileVisitor<>() {
            @NotNull
            @Override
            public FileVisitResult preVisitDirectory(@NotNull Path path, @NotNull BasicFileAttributes attributes) {
                if (++visited[0] > 1024 || files.size() >= MAX_FILES) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                String inner = root.resolve(ADDON).relativize(path).toString().replace('\\', '/');
                if (!inner.isEmpty() && !(inner.equals("src") || inner.startsWith("src/")
                    || inner.equals("tests") || inner.startsWith("tests/")
                    || inner.equals("resources") || inner.startsWith("resources/") || inner.equals("META-INF"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @NotNull
            @Override
            public FileVisitResult visitFile(@NotNull Path path, @NotNull BasicFileAttributes attributes) {
                if (++visited[0] > 1024 || files.size() >= MAX_FILES) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                try {
                    validate(relative);
                    if (attributes.isRegularFile() && attributes.size() <= MAX_FILE) {
                        files.add(relative);
                    }
                } catch (IOException ignored) {
                    // Other addon paths are intentionally not exposed.
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (Files.isRegularFile(root.resolve(BUILDER), LinkOption.NOFOLLOW_LINKS) && files.size() < MAX_FILES) {
            files.add(BUILDER);
        }
        files.sort(Comparator.naturalOrder());
        return new Scan(List.copyOf(files), truncated[0]);
    }

    @NotNull
    public static synchronized Map<String, Object> verify(@NotNull Path installedEclipse,
        @NotNull BooleanSupplier canceled) throws IOException {
        requireRoot();
        if (canceled.getAsBoolean()) {
            throw new IOException("Source verification canceled");
        }
        Scan scan = scan();
        if (scan.truncated()) {
            throw new IOException("Source scan is incomplete; compilation was not attempted");
        }
        List<String> sources = scan.files().stream()
            .filter(path -> path.startsWith(ADDON + "src/") && path.endsWith(".java")).toList();
        if (sources.isEmpty()) {
            throw new IOException("No addon Java source files found");
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        int total = 0;
        for (String source : sources) {
            byte[] bytes = sourceBytes(source);
            total += bytes.length;
            if (total > 1024 * 1024) {
                throw new IOException("Addon source exceeds the bounded compilation budget");
            }
            hashes.put(source, hash(bytes));
        }
        Path installation = installedEclipse.toRealPath();
        List<Path> jars;
        try (var paths = Files.list(installation.resolve("plugins"))) {
            jars = paths.filter(path -> path.getFileName().toString().endsWith(".jar"))
                .filter(Files::isRegularFile).limit(1025).sorted().toList();
        }
        if (jars.isEmpty() || jars.size() > 1024) {
            throw new IOException("Running DBeaver API jars are unavailable or exceed the compile budget");
        }
        String classpath = String.join(java.io.File.pathSeparator, jars.stream().map(Path::toString).toList());
        List<String> command = compilerCommand();
        Path output = Files.createTempDirectory("dbeaver-source-verify-");
        try {
            command.addAll(List.of("-proc:none", "-g:none", "-classpath", classpath,
                "-sourcepath", "", "-d", output.toString()));
            sources.forEach(source -> command.add(root.resolve(source).toString()));
            CompileResult result = compile(command, canceled);
            for (var entry : hashes.entrySet()) {
                expectHash(entry.getValue(), hash(sourceBytes(entry.getKey())));
            }
            List<Map<String, Object>> locations = diagnostics(result.output());
            return Map.of("verificationPassed", result.exitCode() == 0, "compileSucceeded", result.exitCode() == 0,
                "sourceFiles", sources.size(), "diagnostics", locations, "compileOnly", true,
                "testsRun", false, "codeExecuted", false, "installed", false,
                "feedback", result.exitCode() == 0 ? "Addon source compiled against the running DBeaver APIs"
                    : "Compilation failed; inspect source at the reported relative locations");
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @NotNull
    private static List<String> compilerCommand() throws IOException {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String suffix = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? ".exe" : "";
        Path javac = javaHome.resolve("bin/javac" + suffix);
        if (Files.isRegularFile(javac) && Files.isExecutable(javac)) {
            return new ArrayList<>(List.of(javac.toString(), "--release", "21", "-J-Xmx128m"));
        }
        Path ecj = Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "eclipse", "jdt", "ecj",
            ECJ_VERSION, "ecj-" + ECJ_VERSION + ".jar");
        if (!Files.isRegularFile(ecj, LinkOption.NOFOLLOW_LINKS) || Files.size(ecj) > 4 * 1024 * 1024
            || !hash(Files.readAllBytes(ecj)).equals(ECJ_SHA256)) {
            throw new IOException("Use an existing JDK compiler or the trusted ECJ 3.43.0 compiler; no compiler was installed");
        }
        return new ArrayList<>(List.of(javaHome.resolve("bin/java" + suffix).toString(), "-Xmx128m", "-jar",
            ecj.toString(), "-21"));
    }

    @NotNull
    private static CompileResult compile(@NotNull List<String> command, @NotNull BooleanSupplier canceled) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> result = new CompletableFuture<>();
        AtomicBoolean overflow = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] block = new byte[2048];
                int read;
                while ((read = stream.read(block)) != -1) {
                    if (bytes.size() + read > 16 * 1024) {
                        overflow.set(true);
                        throw new IOException("Compiler diagnostic budget exceeded");
                    }
                    bytes.write(block, 0, read);
                }
                result.complete(bytes.toString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        }, "DBeaver source compiler diagnostics");
        reader.setDaemon(true);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            process.getOutputStream().close();
            reader.start();
            while (process.isAlive()) {
                if (canceled.getAsBoolean() || overflow.get() || System.nanoTime() >= deadline) {
                    throw new IOException("Compilation canceled or exceeded its resource limits");
                }
                process.waitFor(20, TimeUnit.MILLISECONDS);
            }
            if (overflow.get()) {
                throw new IOException("Compiler diagnostics exceeded their bounded output budget");
            }
            return new CompileResult(process.exitValue(), result.get(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Compilation interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("Compiler diagnostics were not available");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process.getInputStream().close();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
                reader.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @NotNull
    private static List<Map<String, Object>> diagnostics(@NotNull String output) {
        List<Map<String, Object>> locations = new ArrayList<>();
        Pattern pattern = Pattern.compile("(?m)^\\d+\\. (ERROR|WARNING) in (.+?) \\(at line (\\d+)\\)");
        var matcher = pattern.matcher(output);
        while (matcher.find() && locations.size() < 8) {
            addLocation(locations, matcher.group(2), matcher.group(3), matcher.group(1));
        }
        if (locations.isEmpty()) {
            matcher = Pattern.compile("(?m)^(.+\\.java):(\\d+): (error|warning):").matcher(output);
            while (matcher.find() && locations.size() < 8) {
                addLocation(locations, matcher.group(1), matcher.group(2), matcher.group(3));
            }
        }
        return List.copyOf(locations);
    }

    private static void addLocation(@NotNull List<Map<String, Object>> locations, @NotNull String file,
        @NotNull String line, @NotNull String severity) {
        try {
            Path path = Path.of(file).toAbsolutePath().normalize();
            if (path.startsWith(root)) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                validate(relative);
                locations.add(Map.of("relativePath", relative, "line", Integer.parseInt(line), "severity", severity));
            }
        } catch (IOException | RuntimeException ignored) {
            // Never return unvalidated compiler paths or source snippets.
        }
    }

    private record CompileResult(int exitCode, String output) {
    }

    @FunctionalInterface
    private interface FileOperation<T> {
        @Nullable
        T apply(@NotNull SecureDirectoryStream<Path> directory, @NotNull Path leaf) throws IOException;
    }

    private record Undo(byte[] original, String latestHash) {
    }

    private record Scan(List<String> files, boolean truncated) {
    }
}
