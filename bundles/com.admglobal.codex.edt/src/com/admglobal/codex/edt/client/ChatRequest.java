package com.admglobal.codex.edt.client;

import java.util.Objects;
import com.admglobal.codex.edt.context.EditorContext;

/** Неизменяемые данные запроса между интерфейсом Eclipse и будущим транспортом. */
public record ChatRequest(String message, EditorContext context) {
    public ChatRequest {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(context, "context");
    }
}
