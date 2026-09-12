package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import com.google.gson.JsonObject;
import org.eclipse.core.resources.IProject;
import io.github.zhumaniezov.codex.edt.client.CodexClient;
import io.github.zhumaniezov.codex.edt.client.PermissionMode;
import io.github.zhumaniezov.codex.edt.context.ProjectContextResolver;
import io.github.zhumaniezov.codex.edt.context.ProjectWriteGuard;

/**
 * Связывает инструменты с конкретным проектом и turn; право записи отдельно от
 * HTTP auth.
 */
public final class SemanticSession implements AutoCloseable {
    private final LocalMcpBridge bridge;
    private final Supplier<CodexClient.Listener> listener;
    private volatile Permit active;
    private volatile boolean closed;
    private IProject project;
    private Path cwd;
    private String route;
    private final String serverName = "codex_edt_native_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    public String serverName() {
        return serverName;
    }

    private final Set<CompletableFuture<Void>> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<SemanticApproval> approvals = ConcurrentHashMap.newKeySet();

    private static final class Permit {
        final String key = UUID.randomUUID().toString();
        final String thread;
        final CompletableFuture<String> turn = new CompletableFuture<>();
        final PermissionMode mode;
        final IProject project;
        final Path cwd;
        final AtomicBoolean valid = new AtomicBoolean(true);

        Permit(String thread, PermissionMode mode, IProject project, Path cwd) {
            this.thread = thread;
            this.mode = mode;
            this.project = project;
            this.cwd = cwd;
        }
    }

    public SemanticSession(Supplier<CodexClient.Listener> listener) throws Exception {
        this.listener = listener;
        bridge = new LocalMcpBridge();
    }

    public Map<String, String> environment() {
        return bridge.environment();
    }

    public synchronized void configure(JsonObject config, Path directory) {
        if (closed) {
            throw new IllegalStateException(tr("text003"));
        }
        if (directory.equals(cwd) && route != null) {
            config.getAsJsonObject("mcp_servers").add(serverName, bridge.config(route));
            return;
        }
        end();
        bridge.unregister(route);
        route = null;
        cwd = null;
        project = null;
        IProject candidate = ProjectContextResolver.find(directory.toString());
        if (candidate == null) {
            return;
        }
        try (var services = new EdtServices()) {
            if (!(services.projects()
                    .getProject(candidate) instanceof com._1c.g5.v8.dt.core.platform.IConfigurationProject)) {
                return;
            }
        }
        cwd = directory;
        project = candidate;
        route = bridge.register(this::call);
        config.getAsJsonObject("mcp_servers").add(serverName, bridge.config(route));
    }

    public String begin(String thread, PermissionMode mode) {
        end();
        if (route == null) {
            return "";
        }
        var permit = new Permit(thread, mode, project, cwd);
        active = permit;
        return permit.key;
    }

    public void bind(String turn) {
        var p = active;
        if (p != null) {
            p.turn.complete(turn);
        }
    }

    public void end() {
        var permit = active;
        active = null;
        if (permit != null) {
            permit.valid.set(false);
            permit.turn.completeExceptionally(new CancellationException(tr("approvalExpired")));
        }
        approvals.forEach(a -> a.answer(false));
    }

    public CompletionStage<Void> idle() {
        return CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new));
    }

    private boolean valid(Permit p) {
        return !closed && active == p && p.valid.get() && p.project != null && p.project.isOpen() && p.cwd != null
                && p.cwd.toString().equals(ProjectContextResolver.directory(p.project));
    }

    private JsonObject call(String tool, JsonObject input) throws Exception {
        var permit = active;
        if (permit == null || !permit.key.equals(string(input, "turnKey")) || !valid(permit)) {
            throw new IllegalStateException(tr("approvalExpired"));
        }
        var finished = new CompletableFuture<Void>();
        inFlight.add(finished);
        try {
            IProject project = permit.project;
            String turn = permit.turn.get(45, TimeUnit.SECONDS);
            if (!valid(permit)) {
                throw new IllegalStateException(tr("approvalExpired"));
            }
            var arguments = input.deepCopy();
            arguments.remove("turnKey");
            try (var metadata = new EdtMetadataService(project)) {
                if (SemanticTools.READ.contains(tool)) {
                    listener.get().semanticResult(object("threadId", permit.thread, "turnId", turn, "project",
                            project.getName(), "state", "reading"));
                    return metadata.read(tool, arguments);
                }
                if (!tool.equals("edt_apply_metadata_plan")) {
                    throw new IllegalArgumentException("Unknown EDT tool");
                }
                if (!permit.mode.writes()) {
                    throw new IllegalStateException(tr("semanticReadOnly"));
                }
                if (!ProjectWriteGuard.protectedProject(project)) {
                    throw new IllegalStateException(tr("dirtyRequired"));
                }
                var plan = new MetadataPlan(arguments);
                if (permit.mode == PermissionMode.STRICT || permit.mode == PermissionMode.ASK) {
                    var approval = new SemanticApproval(permit.thread, turn, project.getName(), plan);
                    approvals.add(approval);
                    try {
                        listener.get().semanticApproval(approval);
                        if (!approval.decision().get(14, TimeUnit.MINUTES)) {
                            return object("status", "declined");
                        }
                    } finally {
                        approvals.remove(approval);
                        approval.answer(false);
                    }
                }
                if (!valid(permit) || !ProjectWriteGuard.protectedProject(project)) {
                    throw new IllegalStateException(tr("approvalExpired"));
                }
                listener.get().semanticResult(object("threadId", permit.thread, "turnId", turn, "project",
                        project.getName(), "state", "applying"));
                var result = metadata.apply(plan, () -> valid(permit) && ProjectWriteGuard.protectedProject(project));
                listener.get().semanticResult(object("threadId", permit.thread, "turnId", turn, "project",
                        project.getName(), "result", result));
                return result;
            }
        } finally {
            finished.complete(null);
            inFlight.remove(finished);
        }
    }

    @Override
    public void close() {
        closed = true;
        end();
        bridge.close();
    }
}
