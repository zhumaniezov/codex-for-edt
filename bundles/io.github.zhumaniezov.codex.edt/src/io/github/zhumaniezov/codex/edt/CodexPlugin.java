package io.github.zhumaniezov.codex.edt;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.BundleContext;
import io.github.zhumaniezov.codex.edt.client.CodexClient;
import io.github.zhumaniezov.codex.edt.client.CodexClientFactory;
import io.github.zhumaniezov.codex.edt.client.CodexSessionService;
import io.github.zhumaniezov.codex.edt.protocol.CodexProtocol;

public final class CodexPlugin extends Plugin {
    public static final String ID = "io.github.zhumaniezov.codex.edt";
    private static CodexPlugin instance;
    private final Set<CodexClient> clients = ConcurrentHashMap.newKeySet();

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    public static CodexClient createClient() {
        var context = instance.getBundle().getBundleContext();
        var reference = context.getServiceReference(CodexClientFactory.class);
        CodexClient client;
        if (reference != null) {
            var factory = context.getService(reference);
            try { client = factory == null ? productionClient() : factory.create(); }
            finally { context.ungetService(reference); }
        } else {
            client = productionClient();
        }
        instance.clients.add(client);
        return client;
    }

    private static CodexClient productionClient() {
        return new CodexSessionService(line -> log("Codex stderr: " + line, null));
    }

    public static void release(CodexClient client) {
        client.close();
        if (instance != null) { instance.clients.remove(client); }
    }

    public static void log(String message, Throwable error) {
        var plugin = instance;
        if (plugin != null) {
            plugin.getLog().log(new Status(error == null ? Status.INFO : Status.ERROR, ID,
                CodexProtocol.redact(message), error));
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        clients.forEach(CodexClient::close);
        clients.clear();
        instance = null;
        super.stop(context);
    }
}

