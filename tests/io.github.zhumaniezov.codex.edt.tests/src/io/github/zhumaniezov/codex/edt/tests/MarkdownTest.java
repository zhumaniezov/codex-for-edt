package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.ui.render.MarkdownDocument;

public class MarkdownTest {
    @Test public void rendersParagraphsEmphasisCodeListsAndLinksWithoutThemeConstants() {
        var result = MarkdownDocument.parse("**Жирный** *Курсив* `Код`\n\n- Первый\n- Второй\n\n```bsl\nСообщить();\n```\n[Справка](https://edt.1c.ru/dev/)");
        assertTrue(result.text().contains("Жирный")); assertFalse(result.text().contains("**"));
        assertTrue(result.text().contains("• Первый")); assertTrue(result.text().contains("Сообщить();"));
        assertTrue(result.spans().stream().anyMatch(span -> span.style().bold()));
        assertTrue(result.spans().stream().anyMatch(span -> span.style().italic()));
        assertTrue(result.spans().stream().anyMatch(span -> span.style().code()));
        assertTrue(result.spans().stream().anyMatch(span -> span.style().link().equals("https://edt.1c.ru/dev/")));
        int end = 0;
        for (var span : result.spans()) { assertTrue(span.start() >= end); end = span.start() + span.length(); }
    }
    @Test public void rejectsActiveAndFilesystemLinksAndKeepsHtmlInert() {
        for (String link : java.util.List.of("javascript:alert(1)", "file:///C:/secret", "data:text/html,test", "https://user@example.invalid")) {
            assertFalse(MarkdownDocument.safeLink(link));
        }
        assertTrue(MarkdownDocument.safeLink("https://example.com/doc"));
        var result = MarkdownDocument.parse("<script>alert('x')</script>\n\n[Файл](file:///C:/Module.bsl) ![Картинка](https://example.invalid/x.png)");
        assertTrue(result.text().contains("<script>")); assertTrue(result.text().contains("Картинка"));
        assertFalse(result.spans().stream().anyMatch(span -> !span.style().link().isBlank()));
    }
}
