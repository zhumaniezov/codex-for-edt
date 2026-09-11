package io.github.zhumaniezov.codex.edt.tests;

import org.junit.Assume;
import org.junit.Test;

public class CodexLiveTest {
    @Test
    public void answersThroughRealCodexInEdt() throws Exception {
        Assume.assumeTrue("Реальный запрос включается только явно", Boolean.getBoolean("codex.edt.live"));
        ViewScenario.run(true);
    }
    @org.junit.Test
    public void dirtyBufferAndServerHistory() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("codex.edt.live"));
        DirtyLiveScenario.run();
    }
    @Test public void readsRealSettingsWithoutChangingGlobalConfiguration() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("codex.edt.live"));
        try (var client = new io.github.zhumaniezov.codex.edt.client.CodexSessionService(line -> { })) {
            client.connect().toCompletableFuture().get(90, java.util.concurrent.TimeUnit.SECONDS);
            var config = new io.github.zhumaniezov.codex.edt.settings.CodexSettingsService(client).read().toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
            var servers = new io.github.zhumaniezov.codex.edt.settings.McpService(client).list(config).toCompletableFuture().get(120, java.util.concurrent.TimeUnit.SECONDS);
            var skills = new io.github.zhumaniezov.codex.edt.settings.SkillsService(client).list().toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
            var account = new io.github.zhumaniezov.codex.edt.settings.AccountService(client);
            org.junit.Assert.assertFalse(account.read().toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS).type().isBlank());
            var limits = account.limits().toCompletableFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("SETTINGS LIVE: configRead=true versionPresent=" + !config.version().isBlank()
                + " mcpServers=" + servers.size() + " skills=" + skills.size() + " limitWindows=" + limits.size());
            org.junit.Assert.assertTrue(client.snapshot().threadId().isBlank());
        }
    }
}

