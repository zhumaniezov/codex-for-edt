package io.github.zhumaniezov.codex.edt.context;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

/** Ограниченный снимок документа, не удерживающий живой редактор. */
public record EditorBuffer(String text, int offset, int totalLength, int firstLine, int totalLines) {
    public static final int LIMIT = 48000;
    public boolean partial() { return offset != 0 || text.length() != totalLength; }

    public static EditorBuffer capture(IDocument document, int caret) {
        if (document == null) { throw new IllegalStateException(tr("text077")); }
        int total = document.getLength();
        int length = Math.min(total, LIMIT);
        int start = Math.max(0, Math.min(Math.max(0, caret) - length / 2, total - length));
        try {
            // Граница UTF-16 не должна разрезать surrogate pair.
            if (start > 0 && Character.isLowSurrogate(document.getChar(start))) { start--; length--; }
            if (start + length < total && length > 0 && Character.isHighSurrogate(document.getChar(start + length - 1))) { length--; }
            return new EditorBuffer(document.get(start, length), start, total,
                document.getLineOfOffset(start) + 1, document.getNumberOfLines());
        } catch (BadLocationException error) { throw new IllegalStateException(tr("text078"), error); }
    }
}
