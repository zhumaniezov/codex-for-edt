package io.github.zhumaniezov.codex.edt.ui;

import java.util.concurrent.CompletionStage;
import org.eclipse.swt.widgets.Shell;
import io.github.zhumaniezov.codex.edt.CodexPlugin;
import io.github.zhumaniezov.codex.edt.client.*;

/** Настройки используют отдельный процесс без threads: MCP reload не затрагивает read-only диалог. */
final class SettingsAccess {
    private static CodexClient viewClient;
    final CodexClient client;
    final CompletionStage<?> ready;
    final String cwd;
    private SettingsAccess(CodexClient client, CompletionStage<?> ready) { this.client = client; this.ready = ready; this.cwd = viewClient == null ? "" : viewClient.snapshot().cwd(); }
    static void attach(CodexClient client) { viewClient = client; }
    static void detach(CodexClient client) { if (viewClient == client) { viewClient = null; } }
    static SettingsAccess get(Shell shell) {
        if (shell.getData(SettingsAccess.class.getName()) instanceof SettingsAccess access) { return access; }
        var client = new DeferredCodexClient(CodexPlugin::createClient, CodexPlugin::release);
        var access = new SettingsAccess(client, client.connect().handle((value, error) -> null));
        shell.addDisposeListener(event -> client.close());
        shell.setData(SettingsAccess.class.getName(), access); return access;
    }
}
