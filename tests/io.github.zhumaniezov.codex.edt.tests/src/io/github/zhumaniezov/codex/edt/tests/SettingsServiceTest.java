package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import com.google.gson.*;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.settings.*;

public class SettingsServiceTest {
    private static <T> T await(CompletionStage<T> value) throws Exception { return value.toCompletableFuture().get(20, TimeUnit.SECONDS); }
    @Test public void russianAndEnglishResourcesHaveParity() {
        var ru = ResourceBundle.getBundle("io.github.zhumaniezov.codex.edt.messages", Locale.forLanguageTag("ru"), org.eclipse.core.runtime.Platform.getBundle("io.github.zhumaniezov.codex.edt").adapt(org.osgi.framework.wiring.BundleWiring.class).getClassLoader(), ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        var en = ResourceBundle.getBundle("io.github.zhumaniezov.codex.edt.messages", Locale.ENGLISH, org.eclipse.core.runtime.Platform.getBundle("io.github.zhumaniezov.codex.edt").adapt(org.osgi.framework.wiring.BundleWiring.class).getClassLoader(), ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        assertEquals(ru.keySet(), en.keySet());
        assertEquals("Учётная запись", LocalizationService.text("account", Locale.forLanguageTag("ru")));
        assertEquals("Account", LocalizationService.text("account", Locale.ENGLISH));
        assertEquals("Read only", LocalizationService.text("readOnly", Locale.ENGLISH));
        assertEquals("Только чтение", LocalizationService.text("readOnly", Locale.forLanguageTag("ru")));
        en.keySet().forEach(key -> { assertFalse(en.getString(key).isBlank()); assertFalse(en.getString(key).matches(".*[А-Яа-яЁё].*")); });
    }
    @Test public void languagePreferenceOverridesEdtAndUnknownLocaleFallsBackToEnglish() {
        assertEquals("ru", LocalizationService.resolve("auto", "ru_RU").getLanguage());
        assertEquals("en", LocalizationService.resolve("en", "ru_RU").getLanguage());
        assertEquals("ru", LocalizationService.resolve("ru", "en_US").getLanguage());
        assertEquals("en", LocalizationService.resolve("auto", "fr_FR").getLanguage());
    }
    @Test public void preferenceStorePersistsAndDoesNotCreateCodexConfig() throws Exception {
        var store = EdtPreferencesService.store(); String previous = store.getString(EdtPreferencesService.LANGUAGE);
        try {
            store.setValue(EdtPreferencesService.LANGUAGE, "en"); store.save();
            assertEquals("en", EdtPreferencesService.store().getString(EdtPreferencesService.LANGUAGE));
            assertEquals("Account", LocalizationService.tr("account"));
        } finally { store.setValue(EdtPreferencesService.LANGUAGE, previous); store.save(); }
    }
    @Test public void accountLimitsPreserveUnknownAndMultipleWindows() {
        var rows = AccountService.mapLimits(object("rateLimitsByLimitId", object("codex", object("primary", object("usedPercent", 29)), "other", object("secondary", object("usedPercent", 104, "resetsAt", 1900000000)))));
        assertEquals(2, rows.size()); assertEquals(29, rows.get(0).usedPercent()); assertNull(rows.get(0).resetsAt()); assertNull(rows.get(0).minutes());
        assertEquals(100, rows.get(1).usedPercent());
        assertEquals(SessionData.Account.NONE, SessionData.account(object("account", null)));
    }
    @Test public void mcpMappingDoesNotInventConnectionStateOrToolCount() {
        var rows = McpService.map(object("mcp_servers", object("off", object("enabled", false, "url", "https://example.invalid"))), List.of(object("name", "runtime", "runtimeStatus", "failed", "authStatus", "notLoggedIn", "tools", object())));
        assertEquals(Boolean.FALSE, rows.get(0).enabled()); assertEquals("HTTP", rows.get(0).transport()); assertEquals("", rows.get(0).status()); assertNull(rows.get(0).tools());
        assertNull(rows.get(1).enabled()); assertEquals("failed", rows.get(1).status()); assertEquals(Integer.valueOf(0), rows.get(1).tools());
    }
    @Test public void configReadAndVersionedWritesUseServerAndDetectConflict() throws Exception {
        try (var client = new CodexSessionService(TestServer.command("normal"), "test", line -> { })) {
            await(client.connect()); var service = new CodexSettingsService(client); var config = await(service.read());
            assertEquals("v1", config.version()); assertEquals("default-model", string(config.values(), "model"));
            await(service.defaults(config, "fallback-model", "low"));
            var changed = await(service.read()); assertEquals("fallback-model", string(changed.values(), "model"));
            assertThrows(ExecutionException.class, () -> await(service.defaults(config, "default-model", "medium")));
            await(service.server(changed, "new-server", object("command", "example", "enabled", false)));
            changed = await(service.read()); assertFalse(bool(changed.values().getAsJsonObject("mcp_servers").getAsJsonObject("new-server"), "enabled"));
            await(service.server(changed, "new-server", JsonNull.INSTANCE));
            assertFalse(await(service.read()).values().getAsJsonObject("mcp_servers").has("new-server"));
            assertEquals("default-model", client.snapshot().model());
        }
    }
    @Test public void mcpSkillsAndAccountUseStableRpc() throws Exception {
        try (var client = new CodexSessionService(TestServer.command("normal"), "test", line -> { })) {
            await(client.connect()); var config = await(new CodexSettingsService(client).read());
            var mcp = new McpService(client); var rows = await(mcp.list(config));
            assertEquals("connected", rows.get(0).status()); assertEquals(Integer.valueOf(1), rows.get(0).tools());
            await(mcp.reload()); assertEquals("https://example.invalid/oauth", await(mcp.login("external")));
            var skill = await(new SkillsService(client).list()).get(0); assertEquals("example-skill", skill.name()); assertTrue(skill.enabled());
            var account = new AccountService(client); assertEquals("plus", await(account.read()).plan()); assertEquals(25, await(account.limits()).get(0).usedPercent());
            var login = await(account.login()); assertEquals("login-1", login.id()); await(account.cancel(login.id()));
            assertTrue(client.processAlive());
        }
    }
    @Test public void accountSettingsRemainAvailableWhenSignInRequired() throws Exception {
        try (var client = new CodexSessionService(TestServer.command("auth"), "test", line -> { })) {
            assertThrows(ExecutionException.class, () -> await(client.connect()));
            assertTrue(client.processAlive()); var account = new AccountService(client);
            assertEquals(SessionData.Account.NONE, await(account.read()));
            assertEquals("login-1", await(account.login()).id());
        }
    }
    @Test public void unavailableOrMalformedSettingsDoNotChangeSession() throws Exception {
        try (var client = new DeferredCodexClient(() -> new CodexSessionService(List.of("missing-codex.exe"), "test", line -> { }), CodexClient::close)) {
            assertThrows(ExecutionException.class, () -> await(new CodexSettingsService(client).read()));
        }
        var noVersion = CodexSettingsService.map(object("config", object(), "origins", object()));
        assertThrows(IllegalStateException.class, () -> new CodexSettingsService(new MockCodexClient()).defaults(noVersion, "model", "medium"));
    }
    @Test public void mcpManagementCannotReloadAnAgentProcessEvenAfterNewChat() throws Exception {
        try (var client = new CodexSessionService(TestServer.command("normal"), "test", line -> { })) {
            await(client.connect());
            var directory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
            await(client.send(new ChatRequest("test", new io.github.zhumaniezov.codex.edt.context.EditorContext("project", "", "", directory.toString()))));
            assertThrows(ExecutionException.class, () -> await(new McpService(client).reload()));
            await(client.newThread());
            assertThrows(ExecutionException.class, () -> await(new McpService(client).reload()));
            assertTrue(client.processAlive());
        }
    }
}
