package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;

public class ViewCallbackTest {
    private static final class ControlledClient implements CodexClient {
        final CopyOnWriteArrayList<Consumer<String>> consumers = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<CompletableFuture<String>> answers = new CopyOnWriteArrayList<>();

        public CompletionStage<ConnectionInfo> connect() {
            return new MockCodexClient().connect();
        }

        public void setListener(Listener listener) {
        }

        public CompletionStage<String> send(ChatRequest request) {
            return send(request, text -> {
            });
        }

        public CompletionStage<String> send(ChatRequest request, Consumer<String> text) {
            var future = new CompletableFuture<String>();
            answers.add(future);
            consumers.add(text);
            return future;
        }

        public void close() {
        }
    }

    @Test
    public void lateUiCallbackCannotEnterNextResponseOrDisposedView() throws Exception {
        var client = new ControlledClient();
        var registration = FrameworkUtil.getBundle(getClass()).getBundleContext()
                .registerService(CodexClientFactory.class, () -> client, null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                .getProject("codex-callback-" + java.util.UUID.randomUUID());
        project.create(null);
        project.open(null);
        var file = project.getFile("Module.bsl");
        file.create(new java.io.ByteArrayInputStream(new byte[0]), true, null);
        var editor = org.eclipse.ui.ide.IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor");
        var view = page.showView("io.github.zhumaniezov.codex.edt.views.Codex");
        try {
            var shell = view.getSite().getShell();
            var prompt = (Text) find(shell, "prompt");
            var send = find(shell, "send");
            var response = (StyledText) find(shell, "response");
            prompt.setText("Вопрос A");
            waitFor(send::isEnabled, 10, () -> {
            });
            send.notifyListeners(SWT.Selection, new Event());
            waitFor(() -> client.consumers.size() == 1, 5, () -> {
            });
            client.consumers.get(0).accept("PARTIAL_A");
            client.answers.get(0).complete("PARTIAL_A");
            prompt.setText("Вопрос B");
            waitFor(send::isEnabled, 10, () -> {
            });
            send.notifyListeners(SWT.Selection, new Event());
            waitFor(() -> client.consumers.size() == 2, 5, () -> {
            });
            client.consumers.get(0).accept("STALE_A");
            client.consumers.get(1).accept("ONLY_B");
            client.answers.get(1).complete("ONLY_B");
            waitFor(() -> response.getText().contains("ONLY_B"), 10, () -> {
            });
            assertTrue(response.getText().contains("PARTIAL_A"));
            assertFalse(response.getText().contains("STALE_A"));
            client.consumers.get(1).accept("QUEUED_AFTER_COMPLETION");
            page.hideView(view);
            client.consumers.get(0).accept("AFTER_DISPOSE");
            while (shell.getDisplay().readAndDispatch()) {
            }
            assertTrue(response.isDisposed());
        } finally {
            page.hideView(view);
            registration.unregister();
            page.closeEditor(editor, false);
            project.delete(true, true, null);
        }
    }
}
