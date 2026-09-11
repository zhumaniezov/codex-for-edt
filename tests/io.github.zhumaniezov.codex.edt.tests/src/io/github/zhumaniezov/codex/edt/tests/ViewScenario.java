package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.CodexClientFactory;
import io.github.zhumaniezov.codex.edt.client.CodexSessionService;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

public final class ViewScenario {
    private ViewScenario() { }

    public static void run(boolean live) throws Exception {
        var session = live ? new CodexSessionService(line ->
            Platform.getLog(FrameworkUtil.getBundle(ViewScenario.class)).log(Status.info("app-server: " + line)))
            : new CodexSessionService(TestServer.command("normal"), "test", line -> { });
        var registration = FrameworkUtil.getBundle(ViewScenario.class).getBundleContext()
            .registerService(CodexClientFactory.class, () -> session, null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("codex-readonly-" + UUID.randomUUID());
        org.eclipse.ui.IViewPart view = null;
        ITextEditor editor = null;
        try {
            project.create(null);
            project.open(null);
            var file = project.getFile("Module.bsl");
            String code = "Сообщить(\"Привет\");";
            file.create(new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)), true, null);
            Path directory = Path.of(project.getLocationURI());
            Map<String, String> before = hashes(directory);
            editor = (ITextEditor) IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor");
            var document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            editor.getSelectionProvider().setSelection(new TextSelection(document, 0, code.length()));
            view = page.showView("io.github.zhumaniezov.codex.edt.views.Codex");
            var shell = view.getSite().getShell();
            Text prompt = (Text) find(shell, "prompt");
            Text response = (Text) find(shell, "response");
            Button send = (Button) find(shell, "send");
            Label diagnostic = (Label) find(shell, "diagnostic");
            Label status = (Label) find(shell, "status");
            prompt.setText("Что делает выделенный код?");
            waitFor(() -> send.isEnabled() || status.getText().contains("ошибка") || status.getText().contains("требуется вход"), 60, () -> { });
            assertTrue(response.getText(), send.isEnabled());
            page.activate(view);
            send.notifyListeners(SWT.Selection, new Event());
            assertFalse(send.isEnabled());
            boolean[] partial = { false };
            waitFor(send::isEnabled, live ? 180 : 20, () -> {
                if (!send.isEnabled() && !response.getText().isBlank()) { partial[0] = true; }
            });
            assertTrue("Ответ должен появиться до окончания выполнения", partial[0]);
            assertTrue("Не получены потоковые обновления", ((Integer) response.getData("codex.streamingUpdates")) > 0);
            assertTrue("В ответе отсутствует ожидаемое объяснение", response.getText().contains("Привет"));
            assertTrue(diagnostic.getText().contains(directory.toString()));
            assertEquals("Файлы проекта изменились", before, hashes(directory));
            System.out.println((live ? "CODEX_LIVE" : "CODEX_STREAMING_TEST")
                + " model=" + session.model() + " deltas=" + response.getData("codex.streamingUpdates")
                + " cwdVerified=true filesUnchanged=true");
            page.hideView(view);
            view = null;
            session.termination().get(10, TimeUnit.SECONDS);
            assertFalse(session.processAlive());
        } finally {
            if (view != null) { page.hideView(view); }
            session.close();
            registration.unregister();
            if (editor != null) { page.closeEditor(editor, false); }
            if (project.exists()) { project.delete(true, true, null); }
            ResourcesPlugin.getWorkspace().save(true, null);
        }
    }

    private static Map<String, String> hashes(Path root) throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }

    private static Control find(Composite parent, String role) {
        for (Control control : parent.getChildren()) {
            if (role.equals(control.getData("codex.role"))) { return control; }
            if (control instanceof Composite composite) {
                Control found = find(composite, role);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private static void waitFor(BooleanSupplier condition, int seconds, Runnable progress) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Display display = Display.getCurrent();
        Runnable wakeup = new Runnable() {
            public void run() {
                if (System.nanoTime() < deadline && !condition.getAsBoolean()) { display.timerExec(50, this); }
            }
        };
        display.timerExec(50, wakeup);
        try {
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
                if (!display.readAndDispatch()) { display.sleep(); }
                progress.run();
            }
        } finally { display.timerExec(-1, wakeup); }
        assertTrue("Истекло время ожидания интерфейса Codex", condition.getAsBoolean());
    }
}
