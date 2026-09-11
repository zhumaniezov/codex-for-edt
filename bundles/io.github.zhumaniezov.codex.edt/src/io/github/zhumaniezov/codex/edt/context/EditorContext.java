package io.github.zhumaniezov.codex.edt.context;

import java.util.Objects;

/** Передаёт клиенту только данные, без ссылок на живые объекты Eclipse. */
public record EditorContext(String projectName, String modulePath, String selectedText, String projectDirectory) {
    public static final EditorContext EMPTY = new EditorContext("", "", "", "");

    public EditorContext {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(modulePath, "modulePath");
        Objects.requireNonNull(selectedText, "selectedText");
        Objects.requireNonNull(projectDirectory, "projectDirectory");
    }
}
