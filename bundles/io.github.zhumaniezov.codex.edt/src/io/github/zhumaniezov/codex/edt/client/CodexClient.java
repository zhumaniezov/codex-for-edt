package io.github.zhumaniezov.codex.edt.client;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Метод должен возвращаться без ожидания; завершение допускается на любом потоке. */
public interface CodexClient extends AutoCloseable {
    CompletionStage<String> send(ChatRequest request);

    default CompletionStage<String> send(ChatRequest request, Consumer<String> onText) {
        return send(request).thenApply(text -> { onText.accept(text); return text; });
    }

    CompletionStage<ConnectionInfo> connect();

    void setListener(Listener listener);

    default SessionData.Snapshot snapshot() { return SessionData.Snapshot.EMPTY; }
    default CompletionStage<SessionData.ThreadPage> threads(String cursor) {
        return java.util.concurrent.CompletableFuture.completedFuture(new SessionData.ThreadPage(java.util.List.of(), ""));
    }
    default CompletionStage<Void> newThread() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    default CompletionStage<SessionData.HistoryPage> resume(String id) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException(tr("text051")));
    }
    default CompletionStage<SessionData.HistoryPage> history(String cursor) {
        return java.util.concurrent.CompletableFuture.completedFuture(new SessionData.HistoryPage(java.util.List.of(), ""));
    }
    default CompletionStage<Void> select(String model, String effort) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    default CompletionStage<Void> interrupt() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    default CompletionStage<Void> logout() {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException(tr("text052")));
    }

    default CompletionStage<com.google.gson.JsonObject> manage(io.github.zhumaniezov.codex.edt.settings.ManagementRequest request, com.google.gson.JsonObject params) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException(tr("unavailable")));
    }

    interface Listener {
        default void changed(SessionData.Snapshot snapshot) { }

        void status(String status);
        void disconnected(Throwable error);
    }

    @Override
    void close();
}
