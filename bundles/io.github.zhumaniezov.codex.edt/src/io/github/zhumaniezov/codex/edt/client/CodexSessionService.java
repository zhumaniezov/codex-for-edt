package io.github.zhumaniezov.codex.edt.client;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.google.gson.JsonObject;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.process.CodexExecutable;
import io.github.zhumaniezov.codex.edt.protocol.CodexAppServerClient;

public final class CodexSessionService implements CodexClient {
    private final java.util.concurrent.ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "codex-edt-session"); thread.setDaemon(true); return thread;
        });
    private final List<String> suppliedCommand;
    private final String suppliedVersion;
    private final Consumer<String> diagnostics;
    private volatile CodexAppServerClient rpc;
    private volatile boolean closed;
    private volatile Listener listener = new Listener() {
        public void status(String status) { }
        public void disconnected(Throwable error) { }
    };
    private volatile ConnectionInfo connection;
    private String threadId = "";
    private Path projectDirectory;
    private long generation;
    private ActiveTurn active;

    private static final class ActiveTurn {
        final CompletableFuture<String> future;
        final Consumer<String> onText;
        final LinkedHashMap<String, String> items = new LinkedHashMap<>();
        String id = "";
        ActiveTurn(CompletableFuture<String> future, Consumer<String> onText) {
            this.future = future;
            this.onText = onText;
        }
        String text() { return String.join("\n\n", items.values()); }
    }

    public CodexSessionService(Consumer<String> diagnostics) { this(null, "", diagnostics); }

    public CodexSessionService(List<String> command, String version, Consumer<String> diagnostics) {
        suppliedCommand = command == null ? null : List.copyOf(command);
        suppliedVersion = version;
        this.diagnostics = diagnostics;
    }

    @Override
    public void setListener(Listener listener) { this.listener = listener; }

    public String model() { return connection == null ? "" : connection.model(); }
    public boolean processAlive() { return rpc != null && rpc.processAlive(); }
    public CompletableFuture<Void> termination() {
        return rpc == null ? CompletableFuture.completedFuture(null) : rpc.termination();
    }

    private void enqueue(Runnable task) {
        try { executor.execute(task); }
        catch (RejectedExecutionException ignored) { }
    }

    private void submit(CompletableFuture<?> future, Runnable task) {
        try {
            executor.execute(() -> {
                if (closed) { future.completeExceptionally(new IOException("Панель Codex закрыта.")); }
                else { task.run(); }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(new IOException("Панель Codex закрыта."));
        }
    }

    @Override
    public CompletionStage<ConnectionInfo> connect() {
        var future = new CompletableFuture<ConnectionInfo>();
        if (closed) { return CompletableFuture.failedFuture(new IOException("Панель Codex закрыта.")); }
        submit(future, () -> {
            try {
                if (active != null) { throw new IOException("Дождитесь окончания текущего ответа."); }
                listener.status("Codex: подключение...");
                generation++;
                long current = generation;
                if (rpc != null) { rpc.close(); rpc.termination().get(8, TimeUnit.SECONDS); }
                connection = null;
                threadId = "";
                projectDirectory = null;
                List<String> command = suppliedCommand;
                String version = suppliedVersion;
                if (command == null) {
                    Path executable = CodexExecutable.find();
                    version = CodexExecutable.version(executable);
                    command = ReadOnlyPolicy.command(executable);
                }
                rpc = new CodexAppServerClient(command, null,
                    (method, params) -> enqueue(() -> {
                        if (!closed && current == generation) { notification(method, params); }
                    }),
                    error -> enqueue(() -> connectionLost(current, error)), diagnostics, Duration.ofSeconds(45));
                if (closed) { rpc.close(); throw new IOException("Панель Codex закрыта."); }
                var bundle = FrameworkUtil.getBundle(CodexSessionService.class);
                String clientVersion = bundle == null ? "0.2.0" : bundle.getVersion().toString();
                call("initialize", object("clientInfo", object("name", "codex_edt",
                    "title", "Codex for 1C:EDT", "version", clientVersion)));
                rpc.notify("initialized");
                JsonObject account = call("account/read", object("refreshToken", false));
                if (bool(account, "requiresOpenaiAuth")
                        && (!account.has("account") || account.get("account").isJsonNull())) {
                    throw new IOException("Требуется вход в Codex. Выполните codex login и нажмите «Переподключить».");
                }
                List<JsonObject> models = new ArrayList<>();
                String cursor = "";
                var visited = new HashSet<String>();
                do {
                    JsonObject params = object("includeHidden", false);
                    if (!cursor.isEmpty()) { params.addProperty("cursor", cursor); }
                    JsonObject page = call("model/list", params);
                    page.getAsJsonArray("data").forEach(model -> models.add(model.getAsJsonObject()));
                    cursor = string(page, "nextCursor");
                    if (!cursor.isEmpty() && !visited.add(cursor)) { throw new IOException("Повтор курсора model/list."); }
                } while (!cursor.isEmpty());
                String model = selectModel(models);
                connection = new ConnectionInfo(version, model);
                listener.status("Codex: подключён — только чтение");
                future.complete(connection);
            } catch (Throwable error) {
                if (rpc != null) { rpc.close(); }
                connection = null;
                listener.status("Codex: нет соединения");
                future.completeExceptionally(unwrap(error));
            }
        });
        return future;
    }

    public static String selectModel(List<JsonObject> models) throws IOException {
        var available = models.stream().filter(model -> !bool(model, "hidden")).toList();
        var selected = available.stream().filter(model -> bool(model, "isDefault")).findFirst()
            .or(() -> available.stream().findFirst());
        if (selected.isEmpty() || string(selected.get(), "model").isBlank()) {
            throw new IOException("Codex не вернул доступную модель.");
        }
        return string(selected.get(), "model");
    }

    private JsonObject call(String method, JsonObject params) throws Exception {
        return rpc.request(method, params).get(50, TimeUnit.SECONDS);
    }

    @Override
    public CompletionStage<String> send(ChatRequest request) { return send(request, text -> { }); }

    @Override
    public CompletionStage<String> send(ChatRequest request, Consumer<String> onText) {
        if (closed) { return CompletableFuture.failedFuture(new IOException("Панель Codex закрыта.")); }
        var future = new CompletableFuture<String>();
        submit(future, () -> {
            try {
                if (connection == null || rpc == null || !rpc.isAlive()) {
                    throw new IOException("Codex отключён. Нажмите «Переподключить».");
                }
                if (active != null) { throw new IOException("Codex уже выполняет запрос."); }
                Path directory = ReadOnlyPolicy.directory(request.context().projectDirectory());
                if (!directory.equals(projectDirectory) || threadId.isEmpty()) {
                    listener.status("Codex: создание диалога для проекта " + request.context().projectName());
                    JsonObject config = call("config/read", object("includeLayers", false, "cwd", directory.toString()))
                        .getAsJsonObject("config");
                    JsonObject started = call("thread/start", ReadOnlyPolicy.thread(directory.toString(),
                        connection.model(), ReadOnlyPolicy.config(config)));
                    try { ReadOnlyPolicy.verify(started, directory, connection.model()); }
                    catch (IOException error) { connectionLost(generation, error); throw error; }
                    threadId = string(started.getAsJsonObject("thread"), "id");
                    if (threadId.isEmpty()) { throw new IOException("Codex не вернул thread id."); }
                    projectDirectory = directory;
                }
                ActiveTurn turn = new ActiveTurn(future, onText);
                active = turn;
                listener.status("Codex работает... — только чтение");
                JsonObject result = call("turn/start", ReadOnlyPolicy.turn(threadId, projectDirectory.toString(),
                    connection.model(), ReadOnlyPolicy.prompt(request)));
                turn.id = string(result.getAsJsonObject("turn"), "id");
                if (turn.id.isEmpty()) { throw new IOException("Codex не вернул turn id."); }
                var watchdog = executor.schedule(() -> {
                    if (active == turn) {
                        connectionLost(generation, new IOException("Codex не завершил ответ за 5 минут."));
                    }
                }, 5, TimeUnit.MINUTES);
                future.whenComplete((value, error) -> watchdog.cancel(false));
                if (!"inProgress".equals(string(result.getAsJsonObject("turn"), "status"))) {
                    enqueue(() -> notification("turn/completed", object("threadId", threadId, "turn", result.get("turn"))));
                }
            } catch (Throwable error) {
                if (active != null && active.future == future) { active = null; }
                future.completeExceptionally(unwrap(error));
            }
        });
        return future;
    }

    private void notification(String method, JsonObject params) {
        try {
            ActiveTurn turn = active;
            if (turn == null || !threadId.equals(string(params, "threadId"))) { return; }
            String id = method.equals("turn/completed") || method.equals("turn/started")
                ? string(params.getAsJsonObject("turn"), "id") : string(params, "turnId");
            if (!turn.id.isEmpty() && !turn.id.equals(id)) { return; }
            switch (method) {
                case "turn/started" -> turn.id = id;
                case "item/agentMessage/delta" -> {
                    String item = string(params, "itemId");
                    turn.items.merge(item, string(params, "delta"), String::concat);
                    publish(turn);
                }
                case "item/completed" -> {
                    var item = params.getAsJsonObject("item");
                    if ("agentMessage".equals(string(item, "type"))) {
                        turn.items.put(string(item, "id"), string(item, "text"));
                        publish(turn);
                    }
                }
                case "error" -> {
                    String message = redact(string(params.getAsJsonObject("error"), "message"));
                    if (bool(params, "willRetry")) { listener.status("Codex: повтор соединения после ошибки"); }
                    else { connectionLost(generation, new IOException("Ошибка Codex: " + message)); }
                }
                case "turn/completed" -> {
                    var completed = params.getAsJsonObject("turn");
                    active = null;
                    if ("completed".equals(string(completed, "status"))) {
                        listener.status("Codex: ответ готов — только чтение");
                        turn.future.complete(turn.text());
                    } else {
                        String message = completed.has("error") && completed.get("error").isJsonObject()
                            ? redact(string(completed.getAsJsonObject("error"), "message")) : string(completed, "status");
                        turn.future.completeExceptionally(new IOException("Codex не завершил ответ: " + message));
                    }
                }
                default -> { }
            }
        } catch (Throwable error) { connectionLost(generation, unwrap(error)); }
    }

    private void publish(ActiveTurn turn) throws IOException {
        String text = turn.text();
        if (text.length() > 1024 * 1024) { throw new IOException("Ответ Codex превысил допустимый размер панели."); }
        turn.onText.accept(text);
    }

    private void connectionLost(long current, Throwable error) {
        if (closed || current != generation) { return; }
        connection = null;
        if (rpc != null) { rpc.close(); }
        if (active != null) { active.future.completeExceptionally(error); active = null; }
        listener.status("Codex: отключён");
        listener.disconnected(error);
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof java.util.concurrent.ExecutionException
                || error instanceof java.util.concurrent.CompletionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    @Override
    public void close() {
        closed = true;
        var current = rpc;
        if (current != null) { current.close(); }
        enqueue(() -> {
            if (rpc != null) { rpc.close(); }
            if (active != null) { active.future.completeExceptionally(new IOException("Панель закрыта.")); active = null; }
        });
        executor.shutdown();
    }
}
