package io.github.zhumaniezov.codex.edt.tests;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import io.github.zhumaniezov.codex.edt.client.ChatRequest;
import io.github.zhumaniezov.codex.edt.client.CodexClient;
import io.github.zhumaniezov.codex.edt.client.ConnectionInfo;

/** Формирует локальный ответ без запуска процессов и обращения к сети. */
public final class MockCodexClient implements CodexClient {
    @Override
    public CompletionStage<ConnectionInfo> connect() {
        return CompletableFuture.completedFuture(new ConnectionInfo("Тест", "Локальная"));
    }

    @Override
    public void setListener(Listener listener) { }

    @Override
    public void close() { }

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
