package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.context.EditorContext;

public class TurnRoutingTest {
    @Test public void lateInterruptedTurnAndCompletedItemCannotContaminateNextTurn() throws Exception {
        var directory = Files.createTempDirectory("codex-routing-");
        var first = new CopyOnWriteArrayList<String>(); var second = new CopyOnWriteArrayList<String>();
        try (var session = new CodexSessionService(TestServer.command("late-turn"), "test", text -> { })) {
            session.connect().toCompletableFuture().get(15, TimeUnit.SECONDS);
            var request = new ChatRequest("Запрос", new EditorContext("Проект", "", "", directory.toString()));
            var delta = new CompletableFuture<String>();
            var a = session.send(request, text -> { first.add(text); delta.complete(text); }).toCompletableFuture();
            assertEquals("PARTIAL_A", delta.get(15, TimeUnit.SECONDS));
            session.interrupt().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals("PARTIAL_A", a.get(15, TimeUnit.SECONDS));
            assertEquals("ONLY_B_END", session.send(request, second::add).toCompletableFuture().get(15, TimeUnit.SECONDS));
            assertTrue(first.stream().allMatch("PARTIAL_A"::equals));
            assertFalse(second.isEmpty());
            assertTrue(second.stream().allMatch(text -> text.equals("ONLY_B") || text.equals("ONLY_B_END")));
            assertEquals(SessionData.State.READY, session.snapshot().state()); assertTrue(session.processAlive());
        } finally { Files.delete(directory); }
    }
}
