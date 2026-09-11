package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.client.*;

public class DeferredClientTest {
    @Test public void synchronousFailureIsAsyncAndNextConnectionCanRetry() throws Exception {
        var calls = new AtomicInteger(); var owner = Thread.currentThread();
        try (var client = new DeferredCodexClient(() -> {
            assertNotSame(owner, Thread.currentThread());
            if (calls.getAndIncrement() == 0) { throw new IllegalStateException("Тестовая ошибка фабрики"); }
            return new CodexClient() {
                private boolean first = true;
                public CompletionStage<ConnectionInfo> connect() {
                    if (first) { first = false; throw new IllegalStateException("Тестовая синхронная ошибка connect"); }
                    return new MockCodexClient().connect();
                }
                public CompletionStage<String> send(ChatRequest request) { return CompletableFuture.completedFuture(""); }
                public void setListener(Listener value) { }
                public void close() { }
            };
        }, CodexClient::close)) {
            assertEquals(0, calls.get());
            assertThrows(ExecutionException.class, () -> client.connect().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> client.connect().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertNotNull(client.connect().toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test public void closingDuringFactoryDiscoveryReleasesLateClientOnce() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var released = new CountDownLatch(1);
        var releases = new AtomicInteger();
        var client = new DeferredCodexClient(() -> {
            entered.countDown();
            boolean complete = false;
            while (!complete) { try { complete = finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
            return new MockCodexClient();
        }, value -> { value.close(); releases.incrementAndGet(); released.countDown(); });
        try {
            var pending = client.connect().toCompletableFuture(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            client.close(); client.close();
            assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
        } finally { finish.countDown(); client.close(); }
        assertTrue(released.await(5, TimeUnit.SECONDS)); assertEquals(1, releases.get());
    }

    @Test public void closingPendingConnectionSuppressesCallbacksAndClosesProcess() throws Exception {
        var session = new CodexSessionService(TestServer.command("normal"), "test", text -> { });
        var client = new DeferredCodexClient(() -> session, CodexClient::close);
        var callbacksAfterClose = new AtomicInteger(); var closed = new AtomicBoolean();
        client.setListener(new CodexClient.Listener() {
            public void status(String text) { if (closed.get()) { callbacksAfterClose.incrementAndGet(); } }
            public void disconnected(Throwable error) { if (closed.get()) { callbacksAfterClose.incrementAndGet(); } }
        });
        client.connect(); client.close(); closed.set(true);
        session.close(); session.termination().get(12, TimeUnit.SECONDS);
        assertFalse(session.processAlive()); assertEquals(0, callbacksAfterClose.get());
    }
}
