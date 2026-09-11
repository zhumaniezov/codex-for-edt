package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.protocol.CodexAppServerClient;
import io.github.zhumaniezov.codex.edt.process.CodexExecutable;
import io.github.zhumaniezov.codex.edt.process.CodexProcessManager;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.context.EditorContext;

public class AppServerClientTest {
    private static <T> T await(CompletionStage<T> result) throws Exception {
        return result.toCompletableFuture().get(12, TimeUnit.SECONDS);
    }

    private CodexAppServerClient rpc(String mode, java.util.function.BiConsumer<String, com.google.gson.JsonObject> events,
            java.util.function.Consumer<String> diagnostics) throws Exception {
        var client = new CodexAppServerClient(TestServer.command(mode), null, events, error -> { }, diagnostics, Duration.ofSeconds(2));
        try {
            await(client.request("initialize", object("clientInfo", object("name", "codex_edt", "version", "test"))));
            client.notify("initialized");
            return client;
        } catch (Exception error) { client.close(); throw error; }
    }

    @Test
    public void strictJsonParserAndUnicode() throws Exception {
        assertEquals("Привет\nмир", string(parse("{\"text\":\"Привет\\nмир\"}"), "text"));
        for (String invalid : List.of("{bad}", "[]", "{\"a\":1} garbage", "{'a':1}", "{\"a\":1,}", "null")) {
            assertThrows(invalid, IOException.class, () -> parse(invalid));
        }
    }

    @Test
    public void correlatesOutOfOrderResponsesAndNotifications() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var diagnostics = new CopyOnWriteArrayList<String>();
        try (var client = rpc("normal", (method, params) -> received.add(method), diagnostics::add)) {
            var first = client.request("echo/first", object());
            var second = client.request("echo/second", object());
            assertEquals("second", string(await(second), "value"));
            assertEquals("first", string(await(first), "value"));
            assertTrue(received.contains("test/notification"));
            assertFalse(diagnostics.isEmpty());
        }
    }

    @Test
    public void rpcErrorCompletesOnlyMatchingRequest() throws Exception {
        try (var client = rpc("normal", (method, params) -> { }, line -> { })) {
            assertThrows(ExecutionException.class, () -> await(client.request("error", object())));
            assertTrue(await(client.request("account/read", object("refreshToken", false))).has("account"));
        }
    }

    @Test
    public void malformedJsonAndTimeoutCloseConnection() throws Exception {
        for (String method : List.of("broken", "timeout")) {
            var client = rpc("normal", (name, params) -> { }, line -> { });
            try {
                assertThrows(ExecutionException.class, () -> await(client.request(method, object())));
                client.termination().get(8, TimeUnit.SECONDS);
                assertFalse(client.isAlive());
            } finally { client.close(); }
        }
    }

    @Test
    public void shutsDownOnlyOwnedProcessAndDescendants() throws Exception {
        Process unrelated = new ProcessBuilder(TestServer.command("linger")).start();
        try (var client = rpc("hang", (method, params) -> { }, line -> { })) {
            long child = await(client.request("child", object())).get("pid").getAsLong();
            long parent = client.pid();
            client.close();
            client.termination().get(9, TimeUnit.SECONDS);
            assertFalse(ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(unrelated.isAlive());
        } finally {
            unrelated.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void executableMissingAndUnavailableServer() throws Exception {
        assertThrows(IOException.class, () -> CodexExecutable.find(null, Map.of()));
        assertThrows(IOException.class, () -> CodexExecutable.find("missing-codex.exe", Map.of()));
        assertThrows(IOException.class, () -> new CodexAppServerClient(List.of("missing-codex-edt-server.exe"),
            null, (method, params) -> { }, error -> { }, line -> { }, Duration.ofSeconds(1)));
        try (var session = new CodexSessionService(TestServer.command("exit"), "test", line -> { })) {
            assertThrows(ExecutionException.class, () -> await(session.connect()));
        }
    }

    @Test
    public void shutdownDoesNotWaitForBlockedStdin() throws Exception {
        try (var process = new CodexProcessManager(TestServer.command("linger"), null)) {
            var writing = CompletableFuture.runAsync(() -> {
                try { process.write("x".repeat(1024 * 1024)); }
                catch (IOException expected) { }
            });
            assertThrows(TimeoutException.class, () -> writing.get(300, TimeUnit.MILLISECONDS));
            process.close();
            process.termination().get(9, TimeUnit.SECONDS);
            writing.get(3, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }

    @Test
    public void closeCompletesQueuedOperationsAndAllowsReconnect() throws Exception {
        var session = new CodexSessionService(TestServer.command("normal"), "test", line -> { });
        try {
            await(session.connect());
            assertEquals("default-model", await(session.connect()).model());
            var connecting = session.connect();
            var sending = session.send(new ChatRequest("test", EditorContext.EMPTY));
            session.close();
            try { await(connecting); } catch (ExecutionException expected) { }
            assertThrows(ExecutionException.class, () -> await(sending));
            assertThrows(ExecutionException.class, () -> await(session.connect()));
            session.termination().get(10, TimeUnit.SECONDS);
            assertFalse(session.processAlive());
        } finally { session.close(); }
    }

    @Test
    public void authenticatesDiscoversModelStreamsAndChangesProject() throws Exception {
        Path first = Files.createTempDirectory("codex-edt-project-");
        Path second = Files.createTempDirectory("codex-edt-project-");
        try (var session = new CodexSessionService(TestServer.command("normal"), "test", line -> { })) {
            assertEquals("default-model", await(session.connect()).model());
            var updates = new ArrayList<String>();
            var request = new ChatRequest("Что делает код?", new EditorContext("Проект", "/Проект/Module.bsl", "Сообщить();", first.toString()));
            assertEquals("Сообщить выводит «Привет». thread-1", await(session.send(request, updates::add)));
            assertTrue(updates.size() >= 2);
            assertEquals("Сообщить выводит ", updates.get(0));
            assertEquals("Сообщить выводит «Привет». thread-1", await(session.send(request)));
            var other = new ChatRequest("Объясни", new EditorContext("Другой", "", "", second.toString()));
            assertThrows(ExecutionException.class, () -> await(session.send(other)));
            assertEquals("thread-1", session.snapshot().threadId());
            await(session.newThread());
            assertTrue(await(session.send(other)).endsWith("thread-2"));
        } finally { Files.delete(first); Files.delete(second); }
    }

    @Test
    public void rejectsMissingAuthAndUnsafeSandbox() throws Exception {
        try (var session = new CodexSessionService(TestServer.command("auth"), "test", line -> { })) {
            var error = assertThrows(ExecutionException.class, () -> await(session.connect()));
            assertTrue(error.getCause().getMessage().contains("Требуется вход"));
        }
        Path path = Files.createTempDirectory("codex-edt-readonly-");
        try (var session = new CodexSessionService(TestServer.command("unsafe"), "test", line -> { })) {
            await(session.connect());
            assertThrows(ExecutionException.class, () -> await(session.send(
                new ChatRequest("test", new EditorContext("Проект", "", "", path.toString())))));
        } finally { Files.delete(path); }
    }

    @Test
    public void rejectsServerActionAndReportsTurnError() throws Exception {
        Path path = Files.createTempDirectory("codex-edt-errors-");
        try {
            for (String mode : List.of("deny", "fail", "retry")) {
                try (var session = new CodexSessionService(TestServer.command(mode), "test", line -> { })) {
                    await(session.connect());
                    var response = session.send(new ChatRequest("test", new EditorContext("Проект", "", "", path.toString())));
                    if (mode.equals("retry")) { assertTrue(await(response).contains("Привет")); }
                    else { assertThrows(ExecutionException.class, () -> await(response)); }
                }
            }
        } finally { Files.delete(path); }
    }

    @Test
    public void validatesCwdAndCompactContext() throws Exception {
        assertThrows(IOException.class, () -> ReadOnlyPolicy.directory(""));
        assertThrows(IOException.class, () -> ReadOnlyPolicy.directory("/not-an-edt-filesystem-project"));
        var empty = new ChatRequest("Объясни", new EditorContext("Проект", "/Проект/Module.bsl", "", ""));
        assertFalse(ReadOnlyPolicy.prompt(empty).contains("Выделенный код"));
        var huge = new ChatRequest("Объясни", new EditorContext("Проект", "", "я".repeat(20000), ""));
        assertTrue(ReadOnlyPolicy.prompt(huge).length() < 17000);
        assertTrue(ReadOnlyPolicy.prompt(huge).contains("сокращено"));
        assertEquals("first", CodexSessionService.selectModel(List.of(
            object("model", "hidden", "hidden", true, "isDefault", true),
            object("model", "first", "hidden", false, "isDefault", false))));
    }
}
