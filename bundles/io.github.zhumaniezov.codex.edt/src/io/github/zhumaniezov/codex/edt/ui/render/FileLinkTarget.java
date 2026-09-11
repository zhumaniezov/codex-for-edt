package io.github.zhumaniezov.codex.edt.ui.render;

import java.net.URI;
import java.nio.file.*;
import java.util.regex.Pattern;

public record FileLinkTarget(Path path, int line) {
    private static final Pattern LINE = Pattern.compile("(?:#L(\\d+)(?:C\\d+)?|:(\\d+)(?::\\d+)?)$");
    public static boolean candidate(String link) {
        if (link == null || link.isBlank() || link.chars().anyMatch(c -> c < 32)) { return false; }
        return !link.matches("(?i)^[a-z][a-z0-9+.-]*:.*") || link.matches("^[A-Za-z]:[\\\\/].*") || link.startsWith("file:///");
    }
    public static FileLinkTarget resolve(String link, Path project) throws java.io.IOException {
        if (!candidate(link) || project == null) { throw new java.io.IOException("Invalid project file link"); }
        var match = LINE.matcher(link); int line = 1;
        if (match.find()) { line = Integer.parseInt(match.group(1) == null ? match.group(2) : match.group(1)); link = link.substring(0, match.start()); }
        Path root = project.toRealPath();
        Path target = link.startsWith("file:///") ? Path.of(URI.create(link)) : Path.of(link);
        if (!target.isAbsolute()) { target = root.resolve(target); }
        target = target.normalize().toRealPath();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) { throw new java.io.IOException("File link outside project or missing"); }
        return new FileLinkTarget(target, Math.max(1, line));
    }
}
