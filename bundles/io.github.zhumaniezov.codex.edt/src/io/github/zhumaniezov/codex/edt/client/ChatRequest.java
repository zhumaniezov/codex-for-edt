package io.github.zhumaniezov.codex.edt.client;

import java.util.Objects;
import io.github.zhumaniezov.codex.edt.context.EditorContext;

/** Неизменяемые данные запроса между интерфейсом Eclipse и будущим транспортом. */
public record ChatRequest(String message, EditorContext context) {
    public ChatRequest {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(context, "context");
    }
}
