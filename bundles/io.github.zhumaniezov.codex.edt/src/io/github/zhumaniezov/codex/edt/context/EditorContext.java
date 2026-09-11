package io.github.zhumaniezov.codex.edt.context;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.util.Objects;

/** Передаёт клиенту данные без ссылок на живые объекты Eclipse. */
public record EditorContext(String projectName, String modulePath, String selectedText, String projectDirectory,
        boolean dirty, EditorBuffer buffer, int selectionOffset, int selectionLength) {
    public static final EditorContext EMPTY = new EditorContext("", "", "", "");
    public EditorContext(String projectName, String modulePath, String selectedText, String projectDirectory) {
        this(projectName, modulePath, selectedText, projectDirectory, false, null, 0, selectedText.length());
    }
    public EditorContext {
        Objects.requireNonNull(projectName); Objects.requireNonNull(modulePath);
        Objects.requireNonNull(selectedText); Objects.requireNonNull(projectDirectory);
        if (dirty && buffer == null) { throw new IllegalArgumentException(tr("text079")); }
    }
}
