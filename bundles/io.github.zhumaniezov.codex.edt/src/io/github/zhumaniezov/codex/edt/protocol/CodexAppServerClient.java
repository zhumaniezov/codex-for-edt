package io.github.zhumaniezov.codex.edt.protocol;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.zhumaniezov.codex.edt.process.CodexProcessManager;

public final class CodexAppServerClient implements AutoCloseable {
    private final CodexProcessManager process;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.ScheduledExecutorService timeouts = Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "codex-edt-rpc-timeouts");
                t.setDaemon(true);
                return t;
            });
    private final BiConsumer<String, JsonObject> notifications;
    private final Consumer<Throwable> disconnected;
    private final Duration timeout;
    private volatile Consumer<JsonObject> serverRequests;
    private final ConcurrentHashMap<String, JsonElement> incoming = new ConcurrentHashMap<>();

    public void onServerRequest(Consumer<JsonObject> handler) {
        serverRequests = handler;
    }

    public void respond(JsonElement id, JsonObject response) throws IOException {
        if (closed.get() || incoming.remove(idKey(id)) == null) {
            throw new IOException(tr("approvalExpired"));
        }
        process.write(object("id", id, "result", response).toString());
    }

    public void resolved(JsonElement id) {
        incoming.remove(idKey(id));
    }

    public CodexAppServerClient(List<String> command, Path directory, BiConsumer<String, JsonObject> notifications,
            Consumer<Throwable> disconnected, Consumer<String> diagnostics, Duration timeout) throws IOException {
        this.notifications = notifications;
        this.disconnected = disconnected;
        this.timeout = timeout;
        try {
            process = new CodexProcessManager(command, directory);
        } catch (IOException error) {
            timeouts.shutdownNow();
            throw error;
        }
        process.read(this::receive,
                line -> diagnostics.accept(redact(line.length() > 4096 ? line.substring(0, 4096) : line)), this::fail);
    }

    public CompletableFuture<JsonObject> request(String method, JsonObject params) {
        var id = JSON.toJsonTree(sequence.incrementAndGet());
        String key = idKey(id);
        var future = new CompletableFuture<JsonObject>();
        if (closed.get()) {
            future.completeExceptionally(new IOException(tr("text088")));
            return future;
        }
        pending.put(key, future);
        if (closed.get()) {
            pending.remove(key, future);
            future.completeExceptionally(new IOException(tr("text088")));
            return future;
        }
        try {
            var timer = timeouts.schedule(() -> {
                if (pending.remove(key, future)) {
                    future.completeExceptionally(new IOException(tr("text089") + method + "."));
                    fail(new IOException(tr("text090") + method + "."));
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            future.whenComplete((result, error) -> timer.cancel(false));
            process.write(object("id", id, "method", method, "params", params).toString());
        } catch (Exception error) {
            fail(error);
        }
        return future;
    }

    public void notify(String method) throws IOException {
        process.write(object("method", method).toString());
    }

    private void receive(String line) {
        if (closed.get()) {
            return;
        }
        try {
            JsonObject message = parse(line);
            if (message.has("method")) {
                String method = string(message, "method");
                if (message.has("id")) {
                    if (serverRequests != null) {
                        String key = idKey(message.get("id"));
                        if (incoming.putIfAbsent(key, message.get("id").deepCopy()) != null) {
                            throw new IOException(tr("approvalDuplicate"));
                        }
                        serverRequests.accept(message.deepCopy());
                        return;
                    }
                    process.write(
                            object("id", message.get("id"), "error", object("code", -32601, "message", tr("text091")))
                                    .toString());
                    fail(new IOException(tr("text092") + method));
                    return;
                }
                JsonObject params = message.has("params") && message.get("params").isJsonObject()
                        ? message.getAsJsonObject("params")
                        : new JsonObject();
                notifications.accept(method, params);
                return;
            }
            if (!message.has("id") || message.has("result") == message.has("error")) {
                throw new IOException(tr("text093"));
            }
            if (message.has("error") && !message.get("error").isJsonObject()) {
                throw new IOException(tr("text094"));
            }
            String key = idKey(message.get("id"));
            var future = pending.get(key);
            if (future == null) {
                return;
            }
            try {
                if (message.has("error")) {
                    var error = message.getAsJsonObject("error");
                    future.completeExceptionally(new IOException(
                            "Codex RPC " + string(error, "code") + ": " + redact(string(error, "message"))));
                } else if (message.get("result").isJsonObject()) {
                    future.complete(message.getAsJsonObject("result"));
                } else {
                    future.completeExceptionally(new IOException(tr("text095")));
                }
            } catch (RuntimeException error) {
                future.completeExceptionally(new IOException(tr("text096")));
                throw error;
            } finally {
                pending.remove(key, future);
            }
        } catch (Exception error) {
            fail(error);
        }
    }

    public static String idKey(JsonElement id) {
        if (id.isJsonPrimitive()) {
            var value = id.getAsJsonPrimitive();
            if (value.isString()) {
                return "s:" + value.getAsString();
            }
            if (value.isNumber()) {
                return "n:" + value.getAsBigDecimal().toBigIntegerExact();
            }
        }
        throw new IllegalArgumentException(tr("text097"));
    }

    private void fail(Throwable error) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.values().forEach(f -> f.completeExceptionally(error));
        pending.clear();
        incoming.clear();
        process.close();
        timeouts.shutdownNow();
        disconnected.accept(error);
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    public boolean processAlive() {
        return process.isAlive();
    }

    public CompletableFuture<Void> termination() {
        return process.termination();
    }

    @Override
    public void close() {
        incoming.clear();
        if (closed.compareAndSet(false, true)) {
            pending.values().forEach(f -> f.completeExceptionally(new IOException(tr("text098"))));
            pending.clear();
        }
        process.close();
        timeouts.shutdownNow();
    }
}
