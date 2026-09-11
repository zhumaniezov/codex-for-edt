package io.github.zhumaniezov.codex.edt.client;

import java.util.Objects;
import io.github.zhumaniezov.codex.edt.context.EditorContext;

/** Неизменяемые данные запроса между интерфейсом Eclipse и будущим транспортом. */
public record ChatRequest(String message, EditorContext context, java.util.List<String> attachments) {
    public ChatRequest(String message, EditorContext context) { this(message, context, java.util.List.of()); }
    public ChatRequest {
        attachments = java.util.List.copyOf(attachments);
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(context, "context");
    }
}
