package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.context.*;

public class AgentModeTest {
    private static <T> T await(CompletionStage<T> s) throws Exception {
        return s.toCompletableFuture().get(12, TimeUnit.SECONDS);
    }

    @Test
    public void mapsOfficialModes() {
        assertEquals("on-request", PermissionMode.ASK.approval);
        assertEquals("auto_review", PermissionMode.AUTO.reviewer);
        assertEquals("untrusted", PermissionMode.STRICT.approval);
        assertEquals("never", PermissionMode.READ_ONLY.approval);
    }

    @Test
    public void filtersRequirementsAndProfiles() {
        var profiles = List.of(object("id", ":workspace", "allowed", true), object("id", ":read-only", "allowed", true),
                object("id", ":danger-full-access", "allowed", false));
        var options = PermissionOptions.read(object("requirements", object("allowedSandboxModes",
                List.of("read-only", "workspace-write"), "allowedApprovalPolicies", List.of("never", "on-request"))),
                profiles, "test");
        assertTrue(options.allowed().contains(PermissionMode.ASK));
        assertFalse(options.allowed().contains(PermissionMode.FULL));
        assertFalse(options.allowed().contains(PermissionMode.STRICT));
    }

    @Test
    public void emptyAllowedListFailsClosed() {
        assertTrue(PermissionOptions.read(object("requirements", object("allowedSandboxModes", List.of())),
                List.of(object("id", ":workspace", "allowed", true)), "test").allowed().isEmpty());
    }

    @Test
    public void sandboxHasOnlyCurrentRootAndNoTemporaryRoots() throws Exception {
        var root = Files.createTempDirectory("agent-root-");
        try {
            var p = AgentPolicy.sandbox(PermissionMode.ASK, root);
            assertEquals(1, p.getAsJsonArray("writableRoots").size());
            assertEquals(root.toString(), p.getAsJsonArray("writableRoots").get(0).getAsString());
            assertTrue(bool(p, "excludeSlashTmp"));
            assertTrue(bool(p, "excludeTmpdirEnvVar"));
            assertFalse(bool(p, "networkAccess"));
        } finally {
            Files.delete(root);
        }
    }

    @Test
    public void traversalAndSiblingRejected() throws Exception {
        var root = Files.createTempDirectory("boundary-");
        try {
            assertTrue(AgentPolicy.inside(root, "new.txt"));
            assertFalse(AgentPolicy.inside(root, "../outside.txt"));
            assertFalse(AgentPolicy.inside(root, root + "-other/test.txt"));
        } finally {
            Files.delete(root);
        }
    }

    @Test
    public void acceptsCanonicalImplicitCwdButRejectsForeignRoots() throws Exception {
        var root = Files.createTempDirectory("effective-policy-");
        try {
            var response = object("cwd", root.toString(), "model", "test", "approvalPolicy", "on-request",
                    "approvalsReviewer", "user", "sandbox", object("type", "workspaceWrite", "writableRoots", List.of(),
                            "networkAccess", false, "excludeSlashTmp", true, "excludeTmpdirEnvVar", true));
            AgentPolicy.verify(response, PermissionMode.ASK, root, "test");
            response.getAsJsonObject("sandbox").add("writableRoots",
                    JSON.toJsonTree(List.of(root.getParent().toString())));
            assertThrows(java.io.IOException.class,
                    () -> AgentPolicy.verify(response, PermissionMode.ASK, root, "test"));
        } finally {
            Files.delete(root);
        }
    }

    @Test
    public void fileAccept() throws Exception {
        scenario("file", "accept");
    }

    @Test
    public void fileDecline() throws Exception {
        scenario("file", "decline");
    }

    @Test
    public void fileSession() throws Exception {
        scenario("file", "acceptForSession");
    }

    @Test
    public void commandAccept() throws Exception {
        scenario("command", "accept");
    }

    @Test
    public void commandDecline() throws Exception {
        scenario("command", "decline");
    }

    @Test
    public void permissionsSubsetAndScope() throws Exception {
        scenario("permissions", "acceptForSession");
    }

    private void scenario(String message, String decision) throws Exception {
        var root = Files.createTempDirectory("approval-");
        var requested = new CompletableFuture<AgentApproval>();
        var activities = new CopyOnWriteArrayList<AgentActivity.Snapshot>();
        try (var c = new CodexSessionService(TestServer.command("agent"), "test", line -> {
        })) {
            c.setListener(new CodexClient.Listener() {
                public void status(String s) {
                }

                public void disconnected(Throwable t) {
                }

                public void approval(AgentApproval a) {
                    requested.complete(a);
                }

                public void activity(AgentActivity.Snapshot a) {
                    activities.add(a);
                }
            });
            await(c.connect());
            await(c.selectPermission(PermissionMode.STRICT));
            var turn = c.send(new ChatRequest(message, new EditorContext("test", "", "", root.toString())));
            var a = await(requested);
            assertEquals("t", a.thread());
            assertEquals("turn-1", a.turn());
            assertEquals("item", a.item());
            assertEquals(new JsonPrimitive("approval"), a.id());
            await(c.approve(a.key(), decision));
            var answer = await(turn);
            assertTrue(answer.contains(message.equals("permissions") ? "session" : decision));
            assertTrue(activities.stream().anyMatch(v -> v.diff().contains("+new")));
            assertTrue(activities.get(activities.size() - 1).complete());
            assertThrows(ExecutionException.class, () -> await(c.approve(a.key(), "accept")));
        } finally {
            Files.delete(root);
        }
    }

    @Test
    public void readonlyWriteReadonlyKeepsThread() throws Exception {
        var root = Files.createTempDirectory("mode-switch-");
        try (var c = new CodexSessionService(TestServer.command("agent"), "test", l -> {
        })) {
            c.setListener(new CodexClient.Listener() {
                public void status(String s) {
                }

                public void disconnected(Throwable t) {
                }

                public void approval(AgentApproval a) {
                    c.approve(a.key(), "accept");
                }
            });
            await(c.connect());
            assertEquals(PermissionMode.READ_ONLY, c.permissionMode());
            var request = new ChatRequest("file", new EditorContext("test", "", "", root.toString()));
            assertEquals("READ_ONLY", await(c.send(request)));
            await(c.selectPermission(PermissionMode.ASK));
            assertTrue(await(c.send(request)).contains("accept"));
            await(c.selectPermission(PermissionMode.READ_ONLY));
            assertEquals("READ_ONLY", await(c.send(request)));
            assertEquals("t", c.snapshot().threadId());
        } finally {
            Files.delete(root);
        }
    }

    @Test
    public void crashDuringApprovalDisconnects() throws Exception {
        var root = Files.createTempDirectory("approval-crash-");
        var disconnected = new CompletableFuture<Throwable>();
        try (var c = new CodexSessionService(TestServer.command("agent"), "test", l -> {
        })) {
            c.setListener(new CodexClient.Listener() {
                public void status(String s) {
                }

                public void disconnected(Throwable t) {
                    disconnected.complete(t);
                }
            });
            await(c.connect());
            await(c.selectPermission(PermissionMode.ASK));
            assertThrows(ExecutionException.class,
                    () -> await(c.send(new ChatRequest("crash", new EditorContext("test", "", "", root.toString())))));
            await(disconnected);
            c.termination().get(8, TimeUnit.SECONDS);
            assertFalse(c.processAlive());
        } finally {
            Files.delete(root);
        }
    }

    @Test
    public void lateApprovalAfterCloseCannotBeAccepted() throws Exception {
        var root = Files.createTempDirectory("approval-close-");
        var request = new CompletableFuture<AgentApproval>();
        var c = new CodexSessionService(TestServer.command("agent"), "test", l -> {
        });
        try {
            c.setListener(new CodexClient.Listener() {
                public void status(String s) {
                }

                public void disconnected(Throwable t) {
                }

                public void approval(AgentApproval a) {
                    request.complete(a);
                }
            });
            await(c.connect());
            await(c.selectPermission(PermissionMode.ASK));
            c.send(new ChatRequest("file", new EditorContext("test", "", "", root.toString())));
            var a = await(request);
            c.close();
            assertThrows(ExecutionException.class, () -> await(c.approve(a.key(), "accept")));
            c.termination().get(8, TimeUnit.SECONDS);
            assertFalse(c.processAlive());
        } finally {
            c.close();
            Files.delete(root);
        }
    }

    @Test
    public void fatalTurnErrorTerminatesWriterBeforeEditorsCanUnlock() throws Exception {
        var root = Files.createTempDirectory("agent-fatal-");
        try (var c = new CodexSessionService(TestServer.command("agent"), "test", l -> {
        })) {
            await(c.connect());
            await(c.selectPermission(PermissionMode.STRICT));
            assertThrows(ExecutionException.class,
                    () -> await(c.send(new ChatRequest("fatal", new EditorContext("test", "", "", root.toString())))));
            assertEquals(SessionData.State.DISCONNECTED, c.snapshot().state());
            c.termination().get(8, TimeUnit.SECONDS);
            assertFalse(c.processAlive());
        } finally {
            Files.delete(root);
        }
    }

    @Test
    public void commandOutputAndLateTurnIsolation() {
        var a = new AgentActivity("t", "b");
        a.event("item/started", object("threadId", "t", "turnId", "b", "item",
                object("id", "c", "type", "commandExecution", "status", "inProgress")));
        a.event("item/commandExecution/outputDelta",
                object("threadId", "t", "turnId", "a", "itemId", "c", "delta", "WRONG"));
        a.event("item/commandExecution/outputDelta",
                object("threadId", "t", "turnId", "b", "itemId", "c", "delta", "RIGHT"));
        assertEquals("RIGHT", string(a.snapshot().items().get(0), "aggregatedOutput"));
        a.finish();
        a.event("turn/diff/updated", object("threadId", "t", "turnId", "b", "diff", "late"));
        assertEquals("", a.snapshot().diff());
    }

    @Test
    public void permissionsDeclineIsEmpty() {
        var a = new AgentApproval("key", new JsonPrimitive(1), "item/permissions/requestApproval", "t", "u", "i",
                object("permissions", object("network", object("enabled", true))), Path.of("C:/test"),
                PermissionMode.ASK);
        assertEquals(0, a.response("decline").getAsJsonObject("permissions").size());
    }

    @Test
    public void requestDecisionsConstrainButtons() {
        var a = new AgentApproval("k", new JsonPrimitive(1), "item/commandExecution/requestApproval", "t", "u", "i",
                object("availableDecisions", List.of("decline", "cancel")), Path.of("C:/test"), PermissionMode.ASK);
        assertEquals(List.of("decline", "cancel"), a.decisions());
        assertThrows(IllegalArgumentException.class, () -> a.response("accept"));
    }

    @Test
    public void schemaRequestIdsKeepStringAndNumberDistinct() {
        assertNotEquals(io.github.zhumaniezov.codex.edt.protocol.CodexAppServerClient.idKey(new JsonPrimitive(1)),
                io.github.zhumaniezov.codex.edt.protocol.CodexAppServerClient.idKey(new JsonPrimitive("1")));
    }

    @Test
    public void foreignItemAndThreadApprovalAreDeclinedWithoutUi() throws Exception {
        var root = Files.createTempDirectory("approval-routing-");
        var shown = new CopyOnWriteArrayList<AgentApproval>();
        try (var c = new CodexSessionService(TestServer.command("agent"), "test", l -> {
        })) {
            c.setListener(new CodexClient.Listener() {
                public void status(String s) {
                }

                public void disconnected(Throwable t) {
                }

                public void approval(AgentApproval a) {
                    shown.add(a);
                }
            });
            await(c.connect());
            await(c.selectPermission(PermissionMode.STRICT));
            for (var message : List.of("wrong-item", "wrong-thread")) {
                assertTrue(await(c.send(new ChatRequest(message, new EditorContext("test", "", "", root.toString()))))
                        .contains("decline"));
            }
            assertTrue(shown.isEmpty());
        } finally {
            Files.delete(root);
        }
    }
}
