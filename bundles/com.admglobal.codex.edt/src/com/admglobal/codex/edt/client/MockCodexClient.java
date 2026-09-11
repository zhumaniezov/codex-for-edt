package com.admglobal.codex.edt.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Формирует локальный ответ без запуска процессов и обращения к сети. */
public final class MockCodexClient implements CodexClient {
    @Override
    public CompletionStage<String> send(ChatRequest request) {
        var context = request.context();
        String response = "Тестовый ответ Codex. Сообщение получено локально.\n"
            + "Запрос: " + request.message() + "\n\n"
            + "Проект: " + orUnavailable(context.projectName()) + "\n"
            + "BSL-модуль: " + orUnavailable(context.modulePath()) + "\n"
            + "Выделение:\n" + (context.selectedText().isEmpty()
                ? "(нет выделенного текста)" : context.selectedText());
        return CompletableFuture.completedFuture(response);
    }

    private static String orUnavailable(String value) {
        return value.isEmpty() ? "(не определён)" : value;
    }
}
