package io.github.zhumaniezov.codex.edt.client;

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

    interface Listener {
        void status(String status);
        void disconnected(Throwable error);
    }

    @Override
    void close();
}
