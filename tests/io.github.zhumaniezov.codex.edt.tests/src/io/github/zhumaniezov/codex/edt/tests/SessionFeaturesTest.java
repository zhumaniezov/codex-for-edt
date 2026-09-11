package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.*;
import io.github.zhumaniezov.codex.edt.context.EditorContext;

public class SessionFeaturesTest {
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(15, TimeUnit.SECONDS); }
    @Test public void mapsDisplayNamesDefaultsAndReasoningCapabilities() throws Exception {
        var values = SessionData.models(List.of(
            object("model", "hidden", "hidden", true, "isDefault", true),
            object("model", "fast", "displayName", "Быстрая модель", "defaultReasoningEffort", "low",
                "supportedReasoningEfforts", List.of(object("reasoningEffort", "low", "description", "Быстро"))),
            object("model", "smart", "displayName", "Умная модель", "isDefault", true, "defaultReasoningEffort", "medium",
                "supportedReasoningEfforts", List.of(object("reasoningEffort", "medium"), object("reasoningEffort", "high")))));
        assertEquals(2, values.size()); assertEquals("Умная модель", SessionData.defaultModel(values).displayName());
        assertEquals("smart", SessionData.defaultModel(values).id());
        assertEquals("high", values.get(1).compatibleEffort("high"));
        assertEquals("low", values.get(0).compatibleEffort("high"));
        assertEquals("medium", values.get(1).compatibleEffort("unknown"));
        assertEquals("fast", SessionData.defaultModel(List.of(values.get(0))).id());
    }
    @Test public void mapsThreadTitlesTimesAndHidesAgentChildren() {
        var page = SessionData.threads(object("nextCursor", "next", "data", List.of(
            object("id", "1", "name", "Название", "preview", "Другой текст", "updatedAt", 1234, "cwd", "C:/project"),
            object("id", "2", "name", null, "preview", "Текст\nзапроса", "updatedAt", 1235),
            object("id", "3", "parentThreadId", "1"))));
        assertEquals(2, page.threads().size()); assertEquals("Название", page.threads().get(0).title());
        assertEquals("Текст запроса", page.threads().get(1).title()); assertEquals(1234, page.threads().get(0).updatedAt());
        assertEquals("next", page.cursor());
    }
    @Test public void historyIsChronologicalAndRemovesOnlyOurIdeEnvelope() {
        var page = SessionData.history(object("data", List.of(
            object("items", List.of(object("type", "agentMessage", "text", "Ответ"))),
            object("items", List.of(object("type", "userMessage", "content", List.of(object("type", "text",
                "text", "[Контекст 1C:EDT]\nСекретов здесь нет\n\n[Запрос пользователя]\nВопрос"))))))));
        assertEquals("Вы", page.messages().get(0).role()); assertEquals("Вопрос", page.messages().get(0).text());
        assertEquals("Ответ", page.messages().get(1).text());
    }
    @Test public void createsListsResumesAndContinuesSameServerThread() throws Exception {
        var directory = Files.createTempDirectory("codex-history-");
        try (var session = new CodexSessionService(TestServer.command("normal"), "test", line -> { })) {
            await(session.connect());
            var request = new ChatRequest("Первый вопрос", new EditorContext("Проект", "", "", directory.toString()));
            assertTrue(await(session.send(request)).endsWith("thread-1"));
            var first = await(session.threads("")); assertEquals("Первый вопрос", first.threads().get(0).title());
            await(session.newThread()); assertEquals("", session.snapshot().threadId()); assertTrue(session.processAlive());
            assertTrue(await(session.send(request)).endsWith("thread-2"));
            var history = await(session.resume("thread-1"));
            assertEquals("Первый вопрос", history.messages().get(0).text());
            assertEquals("Ответ из истории thread-1", history.messages().get(1).text());
            assertTrue(await(session.send(request)).endsWith("thread-1"));
            assertEquals(4, await(session.history("")).messages().size());
            assertEquals(2, await(session.threads("")).threads().size());
        } finally { Files.delete(directory); }
    }
    @Test public void refusesToInjectOtherProjectIntoResumedChat() throws Exception {
        var first = Files.createTempDirectory("codex-first-"); var second = Files.createTempDirectory("codex-second-");
        try (var session = new CodexSessionService(TestServer.command("normal"), "test", line -> { })) {
            await(session.connect());
            await(session.send(new ChatRequest("Вопрос", new EditorContext("Проект", "", "", first.toString()))));
            await(session.resume("thread-1"));
            assertThrows(ExecutionException.class, () -> await(session.send(new ChatRequest("Другой", new EditorContext("Другой", "", "", second.toString())))));
            assertEquals("thread-1", session.snapshot().threadId());
        } finally { Files.delete(first); Files.delete(second); }
    }
    @Test public void interruptsAndKeepsThreadUsableWithStateTransitions() throws Exception {
        var directory = Files.createTempDirectory("codex-stop-");
        var states = new CopyOnWriteArrayList<State>();
        try (var session = new CodexSessionService(TestServer.command("interrupt"), "test", line -> { })) {
            session.setListener(new CodexClient.Listener() {
                public void status(String status) { }
                public void disconnected(Throwable error) { }
                public void changed(Snapshot value) { states.add(value.state()); }
            });
            await(session.connect());
            var firstDelta = new CompletableFuture<String>();
            var request = new ChatRequest("Вопрос", new EditorContext("Проект", "", "", directory.toString()));
            var result = session.send(request, firstDelta::complete);
            await(firstDelta); await(session.interrupt());
            assertEquals("Сообщить выводит ", await(result)); assertEquals(State.STOPPED, session.snapshot().state());
            assertTrue(session.processAlive()); assertEquals("thread-1", session.snapshot().threadId());
            assertTrue(await(session.send(request)).contains("thread-1"));
            assertTrue(states.containsAll(List.of(State.CONNECTING, State.READY, State.WORKING, State.STOPPING, State.STOPPED)));
        } finally { Files.delete(directory); }
    }
    @Test public void modelSwitchUsesNewCapabilitiesInNextTurn() throws Exception {
        var directory = Files.createTempDirectory("codex-model-");
        try (var session = new CodexSessionService(TestServer.command("normal"), "test", line -> { })) {
            await(session.connect()); assertEquals("Default Model", SessionData.defaultModel(session.snapshot().models()).displayName());
            await(session.select("default-model", "high")); assertEquals("high", session.snapshot().effort());
            await(session.select("fallback-model", "high")); assertEquals("low", session.snapshot().effort());
            assertTrue(await(session.send(new ChatRequest("Вопрос", new EditorContext("Проект", "", "", directory.toString())))).contains("Привет"));
            assertThrows(ExecutionException.class, () -> await(session.select("invented", "low")));
        } finally { Files.delete(directory); }
    }
    @Test public void accountUsesOnlyServerAndLogoutStopsOwnedProcess() throws Exception {
        try (var session = new CodexSessionService(TestServer.command("normal"), "test", line -> { })) {
            await(session.connect()); assertEquals("tester@example.invalid", session.snapshot().account().email());
            assertEquals("plus", session.snapshot().account().plan()); await(session.logout());
            assertEquals(State.AUTH_REQUIRED, session.snapshot().state());
            session.termination().get(10, TimeUnit.SECONDS); assertFalse(session.processAlive());
            await(session.connect()); assertEquals(State.READY, session.snapshot().state());
        }
    }
    @Test public void errorAndReconnectDoNotLeaveOldProcess() throws Exception {
        var directory = Files.createTempDirectory("codex-reconnect-");
        try (var session = new CodexSessionService(TestServer.command("deny"), "test", line -> { })) {
            await(session.connect());
            assertThrows(ExecutionException.class, () -> await(session.send(new ChatRequest("Вопрос", new EditorContext("П", "", "", directory.toString())))));
            session.termination().get(10, TimeUnit.SECONDS); assertFalse(session.processAlive());
            await(session.connect()); assertTrue(session.processAlive()); assertEquals(State.READY, session.snapshot().state());
        } finally { Files.delete(directory); }
    }
    @Test public void externalLogoutRequiresAuthenticationAndClosesProcess() throws Exception {
        var directory = Files.createTempDirectory("codex-auth-update-");
        try (var session = new CodexSessionService(TestServer.command("auth-update"), "test", line -> { })) {
            await(session.connect());
            assertThrows(ExecutionException.class, () -> await(session.send(new ChatRequest("Вопрос", new EditorContext("П", "", "", directory.toString())))));
            session.termination().get(10, TimeUnit.SECONDS);
            assertEquals(State.AUTH_REQUIRED, session.snapshot().state()); assertFalse(session.processAlive());
        } finally { Files.delete(directory); }
    }
}
