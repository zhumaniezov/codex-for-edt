package io.github.zhumaniezov.codex.edt.process;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class CodexExecutable {
    private CodexExecutable() { }

    public static Path find() throws IOException {
        String override = System.getProperty("codex.edt.executable", System.getenv("CODEX_EDT_EXECUTABLE"));
        return find(override, System.getenv());
    }

    public static Path find(String override, Map<String, String> environment) throws IOException {
        if (override != null && !override.isBlank()) {
            return validate(Path.of(override));
        }
        List<Path> candidates = new ArrayList<>();
        String path = environment.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("PATH"))
            .map(Map.Entry::getValue).findFirst().orElse("");
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                candidates.add(Path.of(entry.replace("\"", ""), "codex.exe"));
            }
        }
        String local = environment.get("LOCALAPPDATA");
        if (local != null) {
            Path bin = Path.of(local, "OpenAI", "Codex", "bin");
            if (Files.isDirectory(bin)) {
                try (var children = Files.list(bin)) {
                    candidates.addAll(children.filter(Files::isDirectory)
                        .sorted(Comparator.comparingLong(CodexExecutable::modified).reversed())
                        .map(p -> p.resolve("codex.exe")).toList());
                }
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return validate(candidate);
            }
        }
        throw new IOException(tr("text080"));
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException error) { return 0; }
    }

    private static Path validate(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            throw new IOException(tr("text081") + path);
        }
        return path.toRealPath();
    }

    public static String version(Path executable) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                throw new IOException(tr("text082"));
            }
            String output = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
            var matcher = java.util.regex.Pattern.compile("codex-cli ([0-9]+\\.[0-9]+\\.[0-9]+[^\\s]*)").matcher(output);
            if (process.exitValue() != 0 || !matcher.find()) {
                throw new IOException(tr("text083"));
            }
            return matcher.group(1);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); }
        }
    }
}

