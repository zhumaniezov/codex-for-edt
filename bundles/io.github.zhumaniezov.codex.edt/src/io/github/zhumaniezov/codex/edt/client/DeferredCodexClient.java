package io.github.zhumaniezov.codex.edt.client;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.*;
import io.github.zhumaniezov.codex.edt.Messages;

/**
 * Создание клиента и вызовы его методов не выполняются на потоке владельца
 * View.
 */
public final class DeferredCodexClient implements CodexClient {
    private final Supplier<CodexClient> factory;
    private final Consumer<CodexClient> release;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "codex-view-connection");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile CodexClient delegate;
    private volatile CompletionStage<Void> termination = CompletableFuture.completedFuture(null);

    @Override
    public CompletionStage<Void> termination() {
        var c = delegate;
        return c == null ? termination : c.termination();
    }

    private volatile Listener listener = new Listener() {
        public void status(String value) {
        }

        public void disconnected(Throwable error) {
        }
    };

    public DeferredCodexClient(Supplier<CodexClient> factory, Consumer<CodexClient> release) {
        this.factory = factory;
        this.release = release;
    }

    private CodexClient client() throws IOException {
        if (closed) {
            throw new IOException(Messages.CLOSED());
        }
        if (delegate == null) {
            var created = java.util.Objects.requireNonNull(factory.get());
            synchronized (this) {
                if (closed) {
                    release.accept(created);
                    throw new IOException(Messages.CLOSED());
                }
                delegate = created;
            }
            created.setListener(new Listener() {
                public void semanticApproval(io.github.zhumaniezov.codex.edt.semantic.SemanticApproval value) {
                    if (closed) {
                        value.answer(false);
                    } else {
                        listener.semanticApproval(value);
                    }
                }

                public void semanticResult(com.google.gson.JsonObject value) {
                    if (!closed) {
                        listener.semanticResult(value);
                    }
                }

                public void approval(AgentApproval value) {
                    if (!closed) {
                        listener.approval(value);
                    }
                }

                public void approvalResolved(String key) {
                    if (!closed) {
                        listener.approvalResolved(key);
                    }
                }

                public void activity(AgentActivity.Snapshot value) {
                    if (!closed) {
                        listener.activity(value);
                    }
                }

                public void resourcesChanged(String directory) {
                    if (!closed) {
                        listener.resourcesChanged(directory);
                    }
                }

                public void status(String value) {
                    if (!closed) {
                        listener.status(value);
                    }
                }

                public void changed(SessionData.Snapshot value) {
                    if (!closed) {
                        listener.changed(value);
                    }
                }

                public void disconnected(Throwable error) {
                    if (!closed) {
                        listener.disconnected(error);
                    }
                }
            });
        }
        return delegate;
    }

    private <T> CompletionStage<T> call(Function<CodexClient, CompletionStage<T>> operation) {
        var future = new CompletableFuture<T>();
        pending.add(future);
        future.whenComplete((value, error) -> pending.remove(future));
        if (closed) {
            future.completeExceptionally(new IOException(Messages.CLOSED()));
            return future;
        }
        try {
            executor.execute(() -> {
                try {
                    operation.apply(client()).whenComplete((value, error) -> {
                        if (error == null) {
                            future.complete(value);
                        } else {
                            future.completeExceptionally(error);
                        }
                    });
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(new IOException(Messages.CLOSED(), error));
        }
        return future;
    }

    @Override
    public void setListener(Listener value) {
        listener = value;
    }

    @Override
    public SessionData.Snapshot snapshot() {
        var current = delegate;
        return current == null ? SessionData.Snapshot.EMPTY : current.snapshot();
    }

    @Override
    public CompletionStage<ConnectionInfo> connect() {
        return call(CodexClient::connect);
    }

    @Override
    public PermissionMode permissionMode() {
        var c = delegate;
        return c == null ? PermissionMode.READ_ONLY : c.permissionMode();
    }

    @Override
    public PermissionOptions permissionOptions() {
        var c = delegate;
        return c == null ? PermissionOptions.CLOSED : c.permissionOptions();
    }

    @Override
    public CompletionStage<Void> selectPermission(PermissionMode mode) {
        return call(c -> c.selectPermission(mode));
    }

    @Override
    public CompletionStage<Void> approve(String key, String decision) {
        return call(c -> c.approve(key, decision));
    }

    @Override
    public CompletionStage<String> send(ChatRequest request) {
        return send(request, text -> {
        });
    }

    @Override
    public CompletionStage<String> send(ChatRequest request, Consumer<String> onText) {
        return call(client -> client.send(request, text -> {
            if (!closed) {
                onText.accept(text);
            }
        }));
    }

    @Override
    public CompletionStage<SessionData.ThreadPage> threads(String cursor) {
        return call(client -> client.threads(cursor));
    }

    @Override
    public CompletionStage<Void> newThread() {
        return call(CodexClient::newThread);
    }

    @Override
    public CompletionStage<SessionData.HistoryPage> resume(String id) {
        return call(client -> client.resume(id));
    }

    @Override
    public CompletionStage<SessionData.HistoryPage> history(String cursor) {
        return call(client -> client.history(cursor));
    }

    @Override
    public CompletionStage<Void> select(String model, String effort) {
        return call(client -> client.select(model, effort));
    }

    @Override
    public CompletionStage<Void> interrupt() {
        return call(CodexClient::interrupt);
    }

    @Override
    public CompletionStage<Void> logout() {
        return call(CodexClient::logout);
    }

    @Override
    public CompletionStage<com.google.gson.JsonObject> manage(
            io.github.zhumaniezov.codex.edt.settings.ManagementRequest request, com.google.gson.JsonObject params) {
        return call(client -> client.manage(request, params));
    }

    @Override
    public void close() {
        CodexClient current;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            current = delegate;
            delegate = null;
        }
        pending.forEach(future -> future.completeExceptionally(new IOException(Messages.CLOSED())));
        executor.shutdownNow();
        if (current != null) {
            termination = current.termination();
            release.accept(current);
        }
    }
}
