package io.github.zhumaniezov.codex.edt.client;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import static io.github.zhumaniezov.codex.edt.client.SessionData.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.google.gson.JsonObject;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.process.CodexExecutable;
import io.github.zhumaniezov.codex.edt.protocol.CodexAppServerClient;

/** Сериализует команды сессии; SWT и чтение stdout выполняются на других потоках. */
public final class CodexSessionService implements CodexClient {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
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
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private ConnectionInfo connection;
    private List<Model> models = List.of();
    private Account account = Account.NONE;
    private String effort = "";
    private String threadId = "";
    private Path projectDirectory;
    private boolean resumed;
    private long generation;
    private ActiveTurn active;

    private static final class ActiveTurn {
        final CompletableFuture<String> future;
        final Consumer<String> onText;
        final LinkedHashMap<String, String> items = new LinkedHashMap<>();
        String id = "";
        ActiveTurn(CompletableFuture<String> future, Consumer<String> onText) { this.future = future; this.onText = onText; }
        String text() { return String.join("\n\n", items.values()); }
    }
    @FunctionalInterface private interface Operation<T> { T run() throws Exception; }

    public CodexSessionService(Consumer<String> diagnostics) { this(null, "", diagnostics); }
    public CodexSessionService(List<String> command, String version, Consumer<String> diagnostics) {
        suppliedCommand = command == null ? null : List.copyOf(command);
        suppliedVersion = version; this.diagnostics = diagnostics;
    }
    @Override public void setListener(Listener listener) { this.listener = listener; }
    @Override public Snapshot snapshot() { return snapshot; }
    public String model() { return snapshot.model(); }
    public boolean processAlive() { return rpc != null && rpc.processAlive(); }
    public CompletableFuture<Void> termination() { return rpc == null ? CompletableFuture.completedFuture(null) : rpc.termination(); }
    private void state(State state) {
        snapshot = new Snapshot(state, connection == null ? "" : connection.version(), models,
            connection == null ? "" : connection.model(), effort, threadId,
            projectDirectory == null ? "" : projectDirectory.toString(), account);
        listener.status(state.label()); listener.changed(snapshot);
    }
    private void enqueue(Runnable task) {
        try { executor.execute(task); } catch (RejectedExecutionException ignored) { }
    }
    private void submit(CompletableFuture<?> future, Runnable task) {
        try {
            executor.execute(() -> {
                if (closed) { future.completeExceptionally(new IOException("Панель Codex закрыта.")); }
                else { task.run(); }
            });
        } catch (RejectedExecutionException error) { future.completeExceptionally(new IOException("Панель Codex закрыта.")); }
    }
    private <T> CompletionStage<T> operation(Operation<T> operation) {
        var future = new CompletableFuture<T>();
        submit(future, () -> {
            try { requireConnection(); future.complete(operation.run()); }
            catch (Throwable error) { future.completeExceptionally(unwrap(error)); }
        });
        return future;
    }
    private void requireConnection() throws IOException {
        if (connection == null || rpc == null || !rpc.isAlive()) { throw new IOException("Codex отключён. Нажмите «Переподключить»."); }
    }
    private void requireIdle() throws IOException {
        if (active != null) { throw new IOException("Сначала остановите текущий запрос или дождитесь ответа."); }
    }
    private JsonObject call(String method, JsonObject params) throws Exception { return rpc.request(method, params).get(50, TimeUnit.SECONDS); }

    @Override public CompletionStage<ConnectionInfo> connect() {
        var future = new CompletableFuture<ConnectionInfo>();
        submit(future, () -> {
            if (active != null) { future.completeExceptionally(new IOException("Сначала остановите текущий запрос.")); return; }
            try {
                generation++;
                long current = generation;
                state(State.CONNECTING);
                if (rpc != null) { rpc.close(); rpc.termination().get(8, TimeUnit.SECONDS); }
                connection = null; threadId = ""; projectDirectory = null; resumed = false;
                models = List.of(); account = Account.NONE; effort = "";
                List<String> command = suppliedCommand;
                String version = suppliedVersion;
                if (command == null) {
                    Path executable = CodexExecutable.find();
                    version = CodexExecutable.version(executable); command = ReadOnlyPolicy.command(executable);
                }
                rpc = new CodexAppServerClient(command, null,
                    (method, params) -> enqueue(() -> { if (!closed && current == generation) { notification(method, params); } }),
                    error -> enqueue(() -> connectionLost(current, error)), diagnostics, Duration.ofSeconds(45));
                if (closed) { rpc.close(); throw new IOException("Панель Codex закрыта."); }
                var bundle = FrameworkUtil.getBundle(CodexSessionService.class);
                call("initialize", object("clientInfo", object("name", "codex_edt", "title", "Codex for 1C:EDT",
                    "version", bundle == null ? "0.3.0" : bundle.getVersion().toString())));
                rpc.notify("initialized");
                JsonObject response = call("account/read", object("refreshToken", false));
                account = SessionData.account(response);
                if (bool(response, "requiresOpenaiAuth") && account.type().isEmpty()) {
                    state(State.AUTH_REQUIRED);
                    throw new IOException("Требуется вход в Codex. Выполните codex login в терминале, затем нажмите «Переподключить».");
                }
                var values = new ArrayList<JsonObject>();
                String cursor = "";
                var visited = new HashSet<String>();
                do {
                    JsonObject params = object("includeHidden", false);
                    if (!cursor.isEmpty()) { params.addProperty("cursor", cursor); }
                    JsonObject page = call("model/list", params);
                    page.getAsJsonArray("data").forEach(model -> values.add(model.getAsJsonObject()));
                    cursor = string(page, "nextCursor");
                    if (!cursor.isEmpty() && !visited.add(cursor)) { throw new IOException("Повтор курсора model/list."); }
                } while (!cursor.isEmpty());
                models = SessionData.models(values);
                Model model = defaultModel(models);
                effort = model.compatibleEffort("");
                connection = new ConnectionInfo(version, model.id());
                state(State.READY); future.complete(connection);
            } catch (Throwable error) {
                if (rpc != null) { rpc.close(); }
                connection = null;
                if (snapshot.state() != State.AUTH_REQUIRED) { state(State.ERROR); }
                future.completeExceptionally(unwrap(error));
            }
        });
        return future;
    }
    public static String selectModel(List<JsonObject> models) throws IOException { return defaultModel(SessionData.models(models)).id(); }

    @Override public CompletionStage<Void> select(String id, String requestedEffort) {
        return operation(() -> {
            requireIdle();
            Model selected = models.stream().filter(model -> model.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("Модель отсутствует в model/list."));
            effort = selected.compatibleEffort(requestedEffort);
            connection = new ConnectionInfo(connection.version(), selected.id());
            state(State.READY); return null;
        });
    }
    @Override public CompletionStage<ThreadPage> threads(String cursor) {
        return operation(() -> {
            var params = object("limit", 15, "sortKey", "updated_at", "sortDirection", "desc", "archived", false,
                "sourceKinds", List.of("cli", "vscode", "appServer", "unknown"));
            if (!cursor.isBlank()) { params.addProperty("cursor", cursor); }
            return SessionData.threads(call("thread/list", params));
        });
    }
    private void detach() throws Exception {
        if (!threadId.isBlank()) { call("thread/unsubscribe", object("threadId", threadId)); }
        threadId = ""; projectDirectory = null; resumed = false;
    }
    @Override public CompletionStage<Void> newThread() {
        return operation(() -> { requireIdle(); detach(); state(State.READY); return null; });
    }
    private JsonObject safeConfig(Path directory) throws Exception {
        return ReadOnlyPolicy.config(call("config/read", object("includeLayers", false, "cwd", directory.toString())).getAsJsonObject("config"));
    }
    @Override public CompletionStage<HistoryPage> resume(String id) {
        return operation(() -> {
            requireIdle();
            var metadata = call("thread/read", object("threadId", id, "includeTurns", false)).getAsJsonObject("thread");
            if (metadata.has("parentThreadId") && !metadata.get("parentThreadId").isJsonNull()) {
                throw new IOException("Дочерние agent thread нельзя продолжать в этой панели.");
            }
            Path directory = ReadOnlyPolicy.directory(string(metadata, "cwd"));
            var params = ReadOnlyPolicy.thread(directory.toString(), connection.model(), safeConfig(directory));
            params.remove("ephemeral"); params.addProperty("threadId", id); params.addProperty("excludeTurns", true);
            var result = call("thread/resume", params);
            try { ReadOnlyPolicy.verify(result, directory, connection.model()); }
            catch (IOException error) { connectionLost(generation, error); throw error; }
            String actual = string(result.getAsJsonObject("thread"), "id");
            if (!id.equals(actual)) { throw new IOException("Codex вернул другой thread id."); }
            HistoryPage history;
            try { history = readHistory(id, ""); }
            catch (Exception error) {
                if (!id.equals(threadId)) { call("thread/unsubscribe", object("threadId", id)); }
                throw error;
            }
            if (!threadId.isBlank() && !threadId.equals(id)) { detach(); }
            threadId = id; projectDirectory = directory; resumed = true;
            state(State.READY);
            return history;
        });
    }
    private HistoryPage readHistory(String id, String cursor) throws Exception {
        if (id.isBlank()) { return new HistoryPage(List.of(), ""); }
        var params = object("threadId", id, "limit", 10, "sortDirection", "desc", "itemsView", "full");
        if (!cursor.isBlank()) { params.addProperty("cursor", cursor); }
        return SessionData.history(call("thread/turns/list", params));
    }
    @Override public CompletionStage<HistoryPage> history(String cursor) { return operation(() -> { requireIdle(); return readHistory(threadId, cursor); }); }
    @Override public CompletionStage<Void> logout() {
        return operation(() -> {
            requireIdle(); call("account/logout", object());
            account = Account.NONE; connection = null; threadId = ""; projectDirectory = null;
            state(State.AUTH_REQUIRED); rpc.close(); return null;
        });
    }
    @Override public CompletionStage<Void> interrupt() {
        return operation(() -> {
            if (active != null) {
                state(State.STOPPING);
                call("turn/interrupt", object("threadId", threadId, "turnId", active.id));
            }
            return null;
        });
    }
    @Override public CompletionStage<String> send(ChatRequest request) { return send(request, text -> { }); }
    @Override public CompletionStage<String> send(ChatRequest request, Consumer<String> onText) {
        var future = new CompletableFuture<String>();
        submit(future, () -> {
            if (active != null) { future.completeExceptionally(new IOException("Codex уже выполняет запрос.")); return; }
            try {
                requireConnection();
                Path directory = request.context().projectDirectory().isBlank() && resumed ? projectDirectory
                    : ReadOnlyPolicy.directory(request.context().projectDirectory());
                if (resumed && !directory.equals(projectDirectory)) {
                    throw new IOException("Открытый редактор относится к другому проекту. Откройте модуль проекта чата или создайте новый чат.");
                }
                if (!directory.equals(projectDirectory) || threadId.isEmpty()) {
                    detach();
                    var started = call("thread/start", ReadOnlyPolicy.thread(directory.toString(), connection.model(), safeConfig(directory)));
                    try { ReadOnlyPolicy.verify(started, directory, connection.model()); }
                    catch (IOException error) { connectionLost(generation, error); throw error; }
                    threadId = string(started.getAsJsonObject("thread"), "id");
                    if (threadId.isEmpty()) { throw new IOException("Codex не вернул thread id."); }
                    projectDirectory = directory;
                    String title = request.message().replaceAll("\\s+", " ").strip();
                    call("thread/name/set", object("threadId", threadId, "name", title.substring(0, Math.min(80, title.length()))));
                }
                ActiveTurn turn = new ActiveTurn(future, onText); active = turn;
                state(State.WORKING);
                var params = ReadOnlyPolicy.turn(threadId, projectDirectory.toString(), connection.model(), ReadOnlyPolicy.prompt(request));
                if (!effort.isBlank()) { params.addProperty("effort", effort); }
                JsonObject result = call("turn/start", params);
                turn.id = string(result.getAsJsonObject("turn"), "id");
                if (turn.id.isEmpty()) { throw new IOException("Codex не вернул turn id."); }
                var watchdog = executor.schedule(() -> {
                    if (active == turn) { connectionLost(generation, new IOException("Codex не завершил ответ за 5 минут.")); }
                }, 5, TimeUnit.MINUTES);
                future.whenComplete((value, error) -> watchdog.cancel(false));
                if (!"inProgress".equals(string(result.getAsJsonObject("turn"), "status"))) {
                    notification("turn/completed", object("threadId", threadId, "turn", result.get("turn")));
                }
            } catch (Throwable error) {
                if (active != null && active.future == future) { active = null; }
                if (connection != null) { state(State.ERROR); }
                future.completeExceptionally(unwrap(error));
            }
        });
        return future;
    }
    private void notification(String method, JsonObject params) {
        try {
            if (method.equals("account/updated") && connection != null) {
                var response = call("account/read", object("refreshToken", false));
                account = SessionData.account(response);
                if (bool(response, "requiresOpenaiAuth") && account.type().isBlank()) {
                    connection = null;
                    if (active != null) {
                        active.future.completeExceptionally(new IOException("Требуется вход в Codex.")); active = null;
                    }
                    state(State.AUTH_REQUIRED); rpc.close();
                } else { state(snapshot.state()); }
                return;
            }
            ActiveTurn turn = active;
            if (turn == null || !threadId.equals(string(params, "threadId"))) { return; }
            String id = method.equals("turn/completed") || method.equals("turn/started")
                ? string(params.getAsJsonObject("turn"), "id") : string(params, "turnId");
            if (!turn.id.isEmpty() && !turn.id.equals(id)) { return; }
            switch (method) {
                case "turn/started" -> turn.id = id;
                case "item/agentMessage/delta" -> { turn.items.merge(string(params, "itemId"), string(params, "delta"), String::concat); publish(turn); }
                case "item/completed" -> {
                    var item = params.getAsJsonObject("item");
                    if ("agentMessage".equals(string(item, "type"))) { turn.items.put(string(item, "id"), string(item, "text")); publish(turn); }
                }
                case "error" -> {
                    if (bool(params, "willRetry")) { listener.status("Повтор соединения..."); }
                    else {
                        active = null; state(State.ERROR);
                        turn.future.completeExceptionally(new IOException("Ошибка Codex: " + redact(string(params.getAsJsonObject("error"), "message"))));
                    }
                }
                case "turn/completed" -> {
                    var completed = params.getAsJsonObject("turn"); active = null;
                    String status = string(completed, "status");
                    if (status.equals("completed") || status.equals("interrupted")) {
                        state(status.equals("interrupted") ? State.STOPPED : State.READY); turn.future.complete(turn.text());
                    } else {
                        state(State.ERROR);
                        String message = completed.has("error") && completed.get("error").isJsonObject()
                            ? redact(string(completed.getAsJsonObject("error"), "message")) : status;
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
        if (closed || current != generation || connection == null && !snapshot.state().running()) { return; }
        connection = null;
        if (rpc != null) { rpc.close(); }
        if (active != null) { active.future.completeExceptionally(error); active = null; }
        state(State.DISCONNECTED); listener.disconnected(error);
    }
    private static Throwable unwrap(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) { error = error.getCause(); }
        return error;
    }
    @Override public void close() {
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
