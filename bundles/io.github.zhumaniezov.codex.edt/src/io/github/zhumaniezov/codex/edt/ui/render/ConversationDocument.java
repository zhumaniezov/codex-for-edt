package io.github.zhumaniezov.codex.edt.ui.render;

import java.util.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.Message;

/** Роли задаются данными UI, а не распознаются в тексте ответа модели. */
public final class ConversationDocument {
    private ConversationDocument() {
    }

    public static MarkdownDocument parse(List<Message> messages) {
        var text = new StringBuilder();
        var spans = new ArrayList<MarkdownDocument.Span>();
        var blocks = new ArrayList<MarkdownDocument.Block>();
        for (var message : messages) {
            int start = text.length();
            text.append(message.role()).append("\n");
            spans.add(new MarkdownDocument.Span(start, message.role().length(),
                    new MarkdownDocument.Style(true, false, false, "")));
            var body = MarkdownDocument.parse(message.text(),
                    link -> MarkdownDocument.safeLink(link) || FileLinkTarget.candidate(link));
            int offset = text.length();
            String content = body.text().stripTrailing();
            text.append(content);
            body.spans().stream().filter(span -> span.start() < content.length())
                    .forEach(span -> spans.add(new MarkdownDocument.Span(span.start() + offset,
                            Math.min(span.length(), content.length() - span.start()), span.style())));
            if (text.length() == 0 || text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
            blocks.add(new MarkdownDocument.Block(start, text.length() - start, !"Codex".equals(message.role())));
            text.append("\n");
        }
        return new MarkdownDocument(text.toString(), List.copyOf(spans), List.copyOf(blocks));
    }
}
