package io.github.zhumaniezov.codex.edt.client;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
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

/**
 * Сериализует команды сессии; SWT и чтение stdout выполняются на других
 * потоках.
 */
public final class CodexSessionService implements CodexClient {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "codex-edt-session");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> suppliedCommand;
    private final String suppliedVersion;
    private final Consumer<String> diagnostics;
    private volatile CodexAppServerClient rpc;
    private volatile boolean closed;
    private volatile Listener listener = new Listener() {
        public void status(String status) {
        }

        public void disconnected(Throwable error) {
        }
    };
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private ConnectionInfo connection;
    private List<Model> models = List.of();
    private Account account = Account.NONE;
    private String effort = "";
    private String threadId = "";
    private Path projectDirectory;
    private boolean resumed;
    private boolean initialized;
    private boolean hostedThreads;
    private long generation;
    private ActiveTurn active;
    private volatile PermissionMode permissionMode = PermissionMode.READ_ONLY;
    private volatile PermissionOptions permissionOptions = PermissionOptions.CLOSED;
    private final LinkedHashMap<String, AgentApproval> approvals = new LinkedHashMap<>();
    private boolean reloadPermissions;
    private AgentActivity activity;
    private volatile io.github.zhumaniezov.codex.edt.semantic.SemanticSession semantic;
    private volatile CompletableFuture<Void> bridgeTermination = CompletableFuture.completedFuture(null);

    @Override
    public PermissionMode permissionMode() {
        return permissionMode;
    }

    @Override
    public PermissionOptions permissionOptions() {
        return permissionOptions;
    }

    private void readPermissions() throws Exception {
        var requirements = call("configRequirements/read", object());
        var profiles = new ArrayList<JsonObject>();
        String cursor = "";
        var seen = new HashSet<String>();
        do {
            var params = object();
            if (!cursor.isBlank()) {
                params.addProperty("cursor", cursor);
            }
            var page = call("permissionProfile/list", params);
            page.getAsJsonArray("data").forEach(p -> profiles.add(p.getAsJsonObject()));
            cursor = string(page, "nextCursor");
            if (!cursor.isBlank() && !seen.add(cursor)) {
                throw new IOException(tr("agentPolicyMismatch"));
            }
        } while (!cursor.isBlank());
        permissionOptions = PermissionOptions.read(requirements, profiles,
                connection == null ? "" : connection.model());
    }

    @Override
    public CompletionStage<Void> selectPermission(PermissionMode mode) {
        return operation(() -> {
            requireIdle();
            readPermissions();
            if (!permissionOptions.allowed().contains(mode)) {
                throw new IOException(tr("modeForbidden"));
            }
            if (mode != permissionMode) {
                reloadPermissions = true;
            }
            permissionMode = mode;
            state(State.READY);
            return null;
        });
    }

    @Override
    public CompletionStage<Void> approve(String key, String decision) {
        return operation(() -> {
            var request = approvals.get(key);
            if (request == null || active == null || snapshot.state() == State.STOPPING
                    || !active.id.equals(request.turn()) || !threadId.equals(request.thread())) {
                throw new IOException(tr("approvalExpired"));
            }
            rpc.respond(request.id(), request.response(decision));
            approvals.remove(key);
            listener.approvalResolved(key);
            return null;
        });
    }

    private void clearApprovals() {
        for (var request : List.copyOf(approvals.values())) {
            try {
                if (rpc != null && rpc.isAlive()) {
                    rpc.respond(request.id(), request.response("decline"));
                }
            } catch (IOException ignored) {
            }
            listener.approvalResolved(request.key());
        }
        approvals.clear();
    }

    private void serverRequest(JsonObject message) {
        try {
            var p = message.getAsJsonObject("params");
            String method = string(message, "method");
            if(method.equals(NativeMcpApproval.METHOD)) {
                if(!permissionMode.writes() || active==null || snapshot.state()==State.STOPPING || semantic==null
                        || !NativeMcpApproval.supported(p,semantic.serverName(),threadId,active.id)) {
                    rpc.respond(message.get("id"),object("action","decline"));return;
                }
                String key=generation+":"+CodexAppServerClient.idKey(message.get("id"));
                var request=new AgentApproval(key,message.get("id"),method,threadId,active.id,"",p,projectDirectory,permissionMode);
                approvals.put(key,request);listener.approval(request);return;
            }
            if (p == null || string(p, "threadId").isBlank() || string(p, "turnId").isBlank()
                    || string(p, "itemId").isBlank()
                    || !List.of("item/fileChange/requestApproval", "item/commandExecution/requestApproval",
                            "item/permissions/requestApproval").contains(method)) {
                throw new IOException(tr("text092") + method);
            }
            String key = generation + ":" + CodexAppServerClient.idKey(message.get("id"));
            var request = new AgentApproval(key, message.get("id"), method, string(p, "threadId"), string(p, "turnId"),
                    string(p, "itemId"), p, projectDirectory, permissionMode);
            if (!permissionMode.writes() || active == null || !threadId.equals(request.thread())
                    || !active.id.equals(request.turn())) {
                rpc.respond(request.id(), request.response("decline"));
                return;
            }
            if (!method.equals("item/permissions/requestApproval") && (activity == null || activity.snapshot().items()
                    .stream()
                    .noneMatch(item -> request.item().equals(string(item, "id"))
                            && (method.equals("item/fileChange/requestApproval") ? "fileChange" : "commandExecution")
                                    .equals(string(item, "type"))))) {
                rpc.respond(request.id(), request.response("decline"));
                listener.status(tr("approvalItemMissing"));
                return;
            }
            approvals.put(key, request);
            listener.approval(request);
        } catch (Throwable error) {
            connectionLost(generation, error);
        }
    }

    private static final class ActiveTurn {
        final CompletableFuture<String> future;
        final Consumer<String> onText;
        final java.util.Set<String> completedItems = new java.util.HashSet<>();
        final LinkedHashMap<String, String> items = new LinkedHashMap<>();
        String id = "";

        ActiveTurn(CompletableFuture<String> future, Consumer<String> onText) {
            this.future = future;
            this.onText = onText;
        }

        String text() {
            return String.join("\n\n", items.values());
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run() throws Exception;
    }

    public CodexSessionService(Consumer<String> diagnostics) {
        this(null, "", diagnostics);
    }

    public CodexSessionService(List<String> command, String version, Consumer<String> diagnostics) {
        suppliedCommand = command == null ? null : List.copyOf(command);
        suppliedVersion = version;
        this.diagnostics = diagnostics;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    public String model() {
        return snapshot.model();
    }

    public boolean processAlive() {
        return rpc != null && rpc.processAlive();
    }

    public CompletableFuture<Void> termination() {
        var tools = semantic;
        return CompletableFuture.allOf(rpc == null ? CompletableFuture.completedFuture(null) : rpc.termination(),
                tools == null ? CompletableFuture.completedFuture(null) : tools.idle().toCompletableFuture(),
                bridgeTermination);
    }

    private void state(State state) {
        snapshot = new Snapshot(state, connection == null ? "" : connection.version(), models,
                connection == null ? "" : connection.model(), effort, threadId,
                projectDirectory == null ? "" : projectDirectory.toString(), account,semantic==null?object("status","unavailable"):semantic.diagnostic());
        listener.status(state.label());
        listener.changed(snapshot);
    }

    private void enqueue(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void submit(CompletableFuture<?> future, Runnable task) {
        try {
            executor.execute(() -> {
                if (closed) {
                    future.completeExceptionally(new IOException(tr("text003")));
                } else {
                    task.run();
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(new IOException(tr("text003")));
        }
    }

    private <T> CompletionStage<T> operation(Operation<T> operation) {
        var future = new CompletableFuture<T>();
        submit(future, () -> {
            try {
                requireConnection();
                future.complete(operation.run());
            } catch (Throwable error) {
                future.completeExceptionally(unwrap(error));
            }
        });
        return future;
    }

    private void requireConnection() throws IOException {
        if (connection == null || rpc == null || !rpc.isAlive()) {
            throw new IOException(tr("text053"));
        }
    }

    private void requireIdle() throws IOException {
        if (active != null) {
            throw new IOException(tr("text054"));
        }
    }

    private JsonObject call(String method, JsonObject params) throws Exception {
        return rpc.request(method, params).get(50, TimeUnit.SECONDS);
    }

    @Override
    public CompletionStage<ConnectionInfo> connect() {
        var future = new CompletableFuture<ConnectionInfo>();
        submit(future, () -> {
            if (active != null) {
                future.completeExceptionally(new IOException(tr("text055")));
                return;
            }
            try {
                generation++;
                clearApprovals();
                permissionMode = PermissionMode.READ_ONLY;
                permissionOptions = PermissionOptions.CLOSED;
                reloadPermissions = false;
                long current = generation;
                state(State.CONNECTING);
                if (rpc != null) {
                    rpc.close();
                    rpc.termination().get(8, TimeUnit.SECONDS);
                }
                initialized = false;
                hostedThreads = false;
                connection = null;
                threadId = "";
                projectDirectory = null;
                resumed = false;
                models = List.of();
                account = Account.NONE;
                effort = "";
                List<String> command = suppliedCommand;
                String version = suppliedVersion;
                if (command == null) {
                    Path executable = CodexExecutable.find();
                    version = CodexExecutable.version(executable);
                    command = ReadOnlyPolicy.command(executable);
                }
                if (semantic == null) {
                    semantic = new io.github.zhumaniezov.codex.edt.semantic.SemanticSession(() -> listener);
                }
                rpc = new CodexAppServerClient(command, null, (method, params) -> enqueue(() -> {
                    if (!closed && current == generation) {
                        notification(method, params);
                    }
                }), error -> enqueue(() -> connectionLost(current, error)), diagnostics, Duration.ofSeconds(45),
                        semantic.environment());
                rpc.onServerRequest(message -> enqueue(() -> {
                    if (!closed && current == generation) {
                        serverRequest(message);
                    }
                }));
                if (closed) {
                    rpc.close();
                    throw new IOException(tr("text003"));
                }
                var bundle = FrameworkUtil.getBundle(CodexSessionService.class);
                call("initialize", object("clientInfo", object("name", "codex_edt", "title", "Codex for 1C:EDT",
                        "version", bundle == null ? "0.8.0" : bundle.getVersion().toString())));
                rpc.notify("initialized");
                initialized = true;
                JsonObject response = call("account/read", object("refreshToken", false));
                account = SessionData.account(response);
                if (bool(response, "requiresOpenaiAuth") && account.type().isEmpty()) {
                    state(State.AUTH_REQUIRED);
                    throw new IOException(tr("text056"));
                }
                var values = new ArrayList<JsonObject>();
                String cursor = "";
                var visited = new HashSet<String>();
                do {
                    JsonObject params = object("includeHidden", false);
                    if (!cursor.isEmpty()) {
                        params.addProperty("cursor", cursor);
                    }
                    JsonObject page = call("model/list", params);
                    page.getAsJsonArray("data").forEach(model -> values.add(model.getAsJsonObject()));
                    cursor = string(page, "nextCursor");
                    if (!cursor.isEmpty() && !visited.add(cursor)) {
                        throw new IOException(tr("text057"));
                    }
                } while (!cursor.isEmpty());
                models = SessionData.models(values);
                Model model = defaultModel(models);
                effort = model.compatibleEffort("");
                connection = new ConnectionInfo(version, model.id());
                readPermissions();
                state(State.READY);
                future.complete(connection);
            } catch (Throwable error) {
                if (rpc != null && snapshot.state() != State.AUTH_REQUIRED) {
                    rpc.close();
                    initialized = false;
                }
                if (closed && semantic != null) {
                    bridgeTermination = CompletableFuture.runAsync(semantic::close);
                }
                connection = null;
                if (snapshot.state() != State.AUTH_REQUIRED) {
                    state(State.ERROR);
                }
                future.completeExceptionally(unwrap(error));
            }
        });
        return future;
    }

    @Override
    public CompletionStage<JsonObject> manage(io.github.zhumaniezov.codex.edt.settings.ManagementRequest request,
            JsonObject params) {
        var future = new CompletableFuture<JsonObject>();
        submit(future, () -> {
            try {
                if (!initialized || rpc == null || !rpc.isAlive()) {
                    throw new IOException(tr("unavailable"));
                }
                requireIdle();
                if (hostedThreads && (request == io.github.zhumaniezov.codex.edt.settings.ManagementRequest.CONFIG_WRITE
                        || request == io.github.zhumaniezov.codex.edt.settings.ManagementRequest.MCP_RELOAD
                        || request == io.github.zhumaniezov.codex.edt.settings.ManagementRequest.MCP_LOGIN)) {
                    throw new IOException(tr("mcpDedicated"));
                }
                future.complete(call(request.method(), params));
            } catch (Throwable error) {
                future.completeExceptionally(unwrap(error));
            }
        });
        return future;
    }

    public static String selectModel(List<JsonObject> models) throws IOException {
        return defaultModel(SessionData.models(models)).id();
    }

    @Override
    public CompletionStage<Void> select(String id, String requestedEffort) {
        return operation(() -> {
            requireIdle();
            Model selected = models.stream().filter(model -> model.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IOException(tr("text058")));
            effort = selected.compatibleEffort(requestedEffort);
            connection = new ConnectionInfo(connection.version(), selected.id());
            state(State.READY);
            return null;
        });
    }

    @Override
    public CompletionStage<ThreadPage> threads(String cursor) {
        return operation(() -> {
            var params = object("limit", 15, "sortKey", "updated_at", "sortDirection", "desc", "archived", false,
                    "sourceKinds", List.of("cli", "vscode", "appServer", "unknown"));
            if (!cursor.isBlank()) {
                params.addProperty("cursor", cursor);
            }
            return SessionData.threads(call("thread/list", params));
        });
    }

    private void detach() throws Exception {
        if (semantic != null) {
            semantic.end();
        }
        clearApprovals();
        if (!threadId.isBlank()) {
            call("thread/unsubscribe", object("threadId", threadId));
        }
        threadId = "";
        projectDirectory = null;
        resumed = false;
    }

    @Override
    public CompletionStage<Void> newThread() {
        return operation(() -> {
            requireIdle();
            detach();
            state(State.READY);
            return null;
        });
    }

    private JsonObject callThreadStart(JsonObject params) throws Exception {
        hostedThreads = true;
        return call("thread/start", params);
    }

    private JsonObject safeConfig(Path directory) throws Exception {
        var config = ReadOnlyPolicy
                .config(call("config/read", object("includeLayers", false, "cwd", directory.toString()))
                        .getAsJsonObject("config"));
        if (semantic != null) {
            semantic.configure(config, directory);
        }
        return config;
    }

    @Override
    public CompletionStage<HistoryPage> resume(String id) {
        return operation(() -> {
            requireIdle();
            var metadata = call("thread/read", object("threadId", id, "includeTurns", false)).getAsJsonObject("thread");
            if (metadata.has("parentThreadId") && !metadata.get("parentThreadId").isJsonNull()) {
                throw new IOException(tr("text059"));
            }
            Path directory = ReadOnlyPolicy.directory(string(metadata, "cwd"));
            permissionMode = PermissionMode.READ_ONLY;
            reloadPermissions = false;
            var params = AgentPolicy.thread(permissionMode, directory, connection.model(), safeConfig(directory));
            params.remove("ephemeral");
            params.addProperty("threadId", id);
            params.addProperty("excludeTurns", true);
            hostedThreads = true;
            var result = call("thread/resume", params);
            try {
                ReadOnlyPolicy.verify(result, directory, connection.model());
            } catch (IOException error) {
                connectionLost(generation, error);
                throw error;
            }
            String actual = string(result.getAsJsonObject("thread"), "id");
            if (!id.equals(actual)) {
                throw new IOException(tr("text060"));
            }
            HistoryPage history;
            try {
                history = readHistory(id, "");
            } catch (Exception error) {
                if (!id.equals(threadId)) {
                    call("thread/unsubscribe", object("threadId", id));
                }
                throw error;
            }
            if (!threadId.isBlank() && !threadId.equals(id)) {
                detach();
            }
            threadId = id;
            projectDirectory = directory;
            resumed = true;
            state(State.READY);
            return history;
        });
    }

    private HistoryPage readHistory(String id, String cursor) throws Exception {
        if (id.isBlank()) {
            return new HistoryPage(List.of(), "");
        }
        var params = object("threadId", id, "limit", 10, "sortDirection", "desc", "itemsView", "full");
        if (!cursor.isBlank()) {
            params.addProperty("cursor", cursor);
        }
        return SessionData.history(call("thread/turns/list", params));
    }

    @Override
    public CompletionStage<HistoryPage> history(String cursor) {
        return operation(() -> {
            requireIdle();
            return readHistory(threadId, cursor);
        });
    }

    @Override
    public CompletionStage<Void> logout() {
        return operation(() -> {
            requireIdle();
            call("account/logout", object());
            account = Account.NONE;
            connection = null;
            threadId = "";
            projectDirectory = null;
            state(State.AUTH_REQUIRED);
            rpc.close();
            initialized = false;
            return null;
        });
    }

    @Override
    public CompletionStage<Void> interrupt() {
        if (semantic != null) {
            semantic.end();
        }
        return operation(() -> {
            if (active != null) {
                // turn/start мог создать permit между нажатием Stop и этой задачей.
                if (semantic != null) {
                    semantic.end();
                }
                clearApprovals();
                state(State.STOPPING);
                try {
                    call("turn/interrupt", object("threadId", threadId, "turnId", active.id));
                } catch (Exception error) {
                    connectionLost(generation, error);
                    throw error;
                }
            }
            return null;
        });
    }

    @Override
    public CompletionStage<String> send(ChatRequest request) {
        return send(request, text -> {
        });
    }

    @Override
    public CompletionStage<String> send(ChatRequest request, Consumer<String> onText) {
        var future = new CompletableFuture<String>();
        submit(future, () -> {
            if (active != null) {
                future.completeExceptionally(new IOException(tr("text061")));
                return;
            }
            try {
                requireConnection();
                readPermissions();
                if (!permissionOptions.allowed().contains(permissionMode)) {
                    throw new IOException(tr("modeForbidden"));
                }
                if (permissionMode.writes() && request.context().dirty()) {
                    throw new IOException(tr("dirtyRequired"));
                }
                Path directory = request.context().projectDirectory().isBlank() && resumed ? projectDirectory
                        : ReadOnlyPolicy.directory(request.context().projectDirectory());
                if (!threadId.isBlank() && !directory.equals(projectDirectory)) {
                    throw new IOException(tr("text062"));
                }
                if (!directory.equals(projectDirectory) || threadId.isEmpty()) {
                    detach();
                    var started = callThreadStart(
                            AgentPolicy.thread(permissionMode, directory, connection.model(), safeConfig(directory)));
                    try {
                        AgentPolicy.verify(started, permissionMode, directory, connection.model());
                    } catch (IOException error) {
                        connectionLost(generation, error);
                        throw error;
                    }
                    threadId = string(started.getAsJsonObject("thread"), "id");
                    if (threadId.isEmpty()) {
                        throw new IOException(tr("text063"));
                    }
                    projectDirectory = directory;
                    String title = request.message().replaceAll("\\s+", " ").strip();
                    call("thread/name/set",
                            object("threadId", threadId, "name", title.substring(0, Math.min(80, title.length()))));
                    reloadPermissions = false;
                }
                if (reloadPermissions) {
                    call("thread/unsubscribe", object("threadId", threadId));
                    var resumeParams = AgentPolicy.thread(permissionMode, directory, connection.model(),
                            safeConfig(directory));
                    resumeParams.remove("ephemeral");
                    resumeParams.addProperty("threadId", threadId);
                    resumeParams.addProperty("excludeTurns", true);
                    var result = call("thread/resume", resumeParams);
                    try {
                        AgentPolicy.verify(result, permissionMode, directory, connection.model());
                    } catch (IOException error) {
                        connectionLost(generation, error);
                        throw error;
                    }
                    reloadPermissions = false;
                }
                ActiveTurn turn = new ActiveTurn(future, onText);
                active = turn;
                state(State.WORKING);
                var params = AgentPolicy.turn(permissionMode, threadId, projectDirectory, connection.model(), request);
                if (semantic != null) {
                    String key = semantic.begin(threadId, permissionMode);
                    if (!key.isBlank()) {
                        var input = params.getAsJsonArray("input").get(0).getAsJsonObject();
                        input.addProperty("text", "[EDT native tools: current turnKey=" + key
                                + ". Pass this turnKey in EVERY edt_* call. Use native EDT tools for metadata; never create/edit metadata XML manually.]\n"
                                + string(input, "text"));
                    }
                }
                if (!effort.isBlank()) {
                    params.addProperty("effort", effort);
                }
                JsonObject result = call("turn/start", params);
                turn.id = string(result.getAsJsonObject("turn"), "id");
                if (turn.id.isEmpty()) {
                    throw new IOException(tr("text064"));
                }
                if (semantic != null) {
                    semantic.bind(turn.id);
                }
                activity = new AgentActivity(threadId, turn.id);
                listener.activity(activity.snapshot());
                var watchdog = executor.schedule(() -> {
                    if (active == turn && approvals.isEmpty()) {
                        connectionLost(generation, new IOException(tr("text065")));
                    }
                }, 15, TimeUnit.MINUTES);
                future.whenComplete((value, error) -> watchdog.cancel(false));
                if (!"inProgress".equals(string(result.getAsJsonObject("turn"), "status"))) {
                    notification("turn/completed", object("threadId", threadId, "turn", result.get("turn")));
                }
            } catch (Throwable error) {
                if (active != null && active.future == future) {
                    connectionLost(generation, unwrap(error));
                }
                if (connection != null) {
                    state(State.ERROR);
                }
                future.completeExceptionally(unwrap(error));
            }
        });
        return future;
    }

    private void notification(String method, JsonObject params) {
        try {
            if (method.equals("serverRequest/resolved")) {
                String key = generation + ":" + CodexAppServerClient.idKey(params.get("requestId"));
                var request = approvals.get(key);
                if (request != null && request.thread().equals(string(params, "threadId"))) {
                    approvals.remove(key);
                    rpc.resolved(params.get("requestId"));
                    listener.approvalResolved(key);
                }
                return;
            }
            if (method.equals("account/updated") && connection != null) {
                var response = call("account/read", object("refreshToken", false));
                account = SessionData.account(response);
                if (bool(response, "requiresOpenaiAuth") && account.type().isBlank()) {
                    connectionLost(generation, new IOException(tr("text066")));
                    state(State.AUTH_REQUIRED);
                } else {
                    state(snapshot.state());
                }
                return;
            }
            if (method.equals("account/login/completed")) {
                if (bool(params, "success")) {
                    connect();
                } else {
                    listener.status(tr("error"));
                }
                return;
            }
            ActiveTurn turn = active;
            if (turn == null || !threadId.equals(string(params, "threadId"))) {
                return;
            }
            String id = method.equals("turn/completed") || method.equals("turn/started")
                    ? string(params.getAsJsonObject("turn"), "id")
                    : string(params, "turnId");
            // Идентификатор принимается только из ответа turn/start; уведомление не может
            // переназначить turn.
            if (turn.id.isEmpty() || !turn.id.equals(id)) {
                return;
            }
            if (activity != null) {
                activity.event(method, params);
                if (method.equals("item/started") || method.equals("item/completed")
                        || method.equals("item/commandExecution/outputDelta") || method.equals("turn/diff/updated")
                        || method.equals("item/fileChange/patchUpdated")) {
                    listener.activity(activity.snapshot());
                }
            }
            switch (method) {
            case "turn/started" -> {
            }
            case "item/agentMessage/delta" -> {
                String itemId = string(params, "itemId");
                if (!itemId.isBlank() && !turn.completedItems.contains(itemId)) {
                    turn.items.merge(itemId, string(params, "delta"), String::concat);
                    publish(turn);
                }
            }
            case "item/completed" -> {
                var item = params.getAsJsonObject("item");
                if (permissionMode.writes() && "completed".equals(string(item, "status"))
                        && List.of("fileChange", "commandExecution").contains(string(item, "type"))) {
                    listener.resourcesChanged(projectDirectory.toString());
                }
                String itemId = string(item, "id");
                if ("agentMessage".equals(string(item, "type")) && !itemId.isBlank()
                        && turn.completedItems.add(itemId)) {
                    turn.items.put(itemId, string(item, "text"));
                    publish(turn);
                }
            }
            case "error" -> {
                if (bool(params, "willRetry")) {
                    listener.status(tr("text067"));
                } else {
                    connectionLost(generation, new IOException(
                            tr("text068") + redact(string(params.getAsJsonObject("error"), "message"))));
                }
            }
            case "turn/completed" -> {
                clearApprovals();
                if (activity != null) {
                    activity.finish();
                    listener.activity(activity.snapshot());
                }
                var completed = params.getAsJsonObject("turn");
                if (semantic != null) {
                    semantic.end();
                }
                var idle = semantic == null ? CompletableFuture.<Void>completedFuture(null) : semantic.idle();
                idle.whenComplete((ignored, idleError) -> enqueue(() -> finishTurn(turn, completed)));
            }
            default -> {
            }
            }
        } catch (Throwable error) {
            connectionLost(generation, unwrap(error));
        }
    }

    private void finishTurn(ActiveTurn turn, JsonObject completed) {
        if (closed || active != turn) {
            return;
        }
        active = null;
        String status = string(completed, "status");
        if (status.equals("completed") || status.equals("interrupted")) {
            state(status.equals("interrupted") ? State.STOPPED : State.READY);
            turn.future.complete(turn.text());
        } else {
            state(State.ERROR);
            String message = completed.has("error") && completed.get("error").isJsonObject()
                    ? redact(string(completed.getAsJsonObject("error"), "message"))
                    : status;
            turn.future.completeExceptionally(new IOException(tr("text069") + message));
        }
    }

    private void publish(ActiveTurn turn) throws IOException {
        String text = turn.text();
        if (text.length() > 1024 * 1024) {
            throw new IOException(tr("text070"));
        }
        turn.onText.accept(text);
    }

    private void connectionLost(long current, Throwable error) {
        if (closed || current != generation || !initialized && connection == null && !snapshot.state().running()) {
            return;
        }
        connection = null;
        if (semantic != null) {
            semantic.end();
        }
        clearApprovals();
        if (rpc != null) {
            rpc.close();
        }
        state(State.DISCONNECTED);
        if (active != null) {
            active.future.completeExceptionally(error);
            active = null;
        }
        listener.disconnected(error);
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    @Override
    public void close() {
        closed = true;
        var tools = semantic;
        if (tools != null) {
            tools.end();
            bridgeTermination = CompletableFuture.runAsync(tools::close);
        }
        var current = rpc;
        if (current != null) {
            current.close();
        }
        enqueue(() -> {
            if (rpc != null) {
                rpc.close();
            }
            if (active != null) {
                active.future.completeExceptionally(new IOException(tr("text071")));
                active = null;
            }
        });
        executor.shutdown();
    }
}
