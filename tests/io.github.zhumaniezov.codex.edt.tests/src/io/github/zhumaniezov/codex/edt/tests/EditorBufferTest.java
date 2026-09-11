package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import org.eclipse.jface.text.Document;
import io.github.zhumaniezov.codex.edt.context.*;
import io.github.zhumaniezov.codex.edt.client.*;

public class EditorBufferTest {
    private static String prompt(boolean dirty, String code, int offset, int length) {
        var doc = new Document(code);
        var context = new EditorContext("Проект", "/Проект/Module.bsl", code.substring(offset, offset + length), "C:/Проект",
            dirty, dirty ? EditorBuffer.capture(doc, offset) : null, offset, length);
        return ReadOnlyPolicy.prompt(new ChatRequest("Объясни", context));
    }
    @Test public void dirtyWithoutSelectionIncludesActualBufferAndPriority() {
        String text = prompt(true, "Процедура Тест()\nСообщить(\"Привет\");\nКонецПроцедуры", 0, 0);
        assertTrue(text.contains("Процедура Тест()")); assertTrue(text.contains("имеет приоритет"));
        assertTrue(text.contains("Не сохраняй")); assertFalse(text.contains("[Выделенный код]"));
    }
    @Test public void savedDocumentDoesNotSendEntireModule() {
        String text = prompt(false, "Весь модуль содержит Сообщить();", 20, 10);
        assertFalse(text.contains("Весь модуль")); assertFalse(text.contains("IDE buffer"));
        assertTrue(text.contains("[Выделенный код]"));
    }
    @Test public void dirtySelectionReferencesBufferWithoutDuplicatingCode() {
        String code = "Начало\nСообщить();\nКонец";
        String text = prompt(true, code, 7, 11);
        assertEquals(text.indexOf("Сообщить();"), text.lastIndexOf("Сообщить();"));
        assertTrue(text.contains("Символы 8–18")); assertTrue(text.contains("Начало")); assertTrue(text.contains("Конец"));
    }
    @Test public void hugeBufferUsesExplicitWindowAroundCaret() {
        String code = "а".repeat(70000) + "ЦЕЛЬ" + "б".repeat(70000);
        var buffer = EditorBuffer.capture(new Document(code), 70000);
        assertTrue(buffer.partial()); assertTrue(buffer.text().contains("ЦЕЛЬ")); assertTrue(buffer.text().length() <= EditorBuffer.LIMIT);
        String text = prompt(true, code, 70000, 0);
        assertTrue(text.contains("ФРАГМЕН")); assertTrue(text.contains("НЕ передан")); assertTrue(text.length() < 50000);
    }
    @Test public void emptyDirtyDocumentIsStillAnAuthoritativeBuffer() {
        assertTrue(prompt(true, "", 0, 0).contains("несохранённые изменения"));
        assertFalse(EditorBuffer.capture(new Document(""), 0).partial());
        assertThrows(IllegalArgumentException.class, () -> new EditorContext("", "", "", "", true, null, 0, 0));
    }
    @Test public void truncationPreservesUnicodePairs() {
        String code = "😀".repeat(70000);
        var buffer = EditorBuffer.capture(new Document(code), 70001);
        assertFalse(Character.isLowSurrogate(buffer.text().charAt(0)));
        assertFalse(Character.isHighSurrogate(buffer.text().charAt(buffer.text().length() - 1)));
    }
}
