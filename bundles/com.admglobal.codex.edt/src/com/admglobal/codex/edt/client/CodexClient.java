package com.admglobal.codex.edt.client;

import java.util.concurrent.CompletionStage;

/** Метод должен возвращаться без ожидания; завершение допускается на любом потоке. */
public interface CodexClient extends AutoCloseable {
    CompletionStage<String> send(ChatRequest request);

    @Override
    default void close() {
        // У тестовой реализации нет ресурсов для освобождения.
    }
}
