package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.UUID;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.State;
import io.github.zhumaniezov.codex.edt.context.EclipseContextProvider;

public class IdeViewTest {
    @Test public void nativeComposerModelsStopNewResumeAndDirtyContext() throws Exception { run("interrupt"); }
    @Test public void interruptedAnswerRemainsInOwnUiHistoryAndNextAnswerContainsOnlyItsEvents() throws Exception { run("late-turn"); }
    private void run(String mode) throws Exception {
        var session = new CodexSessionService(TestServer.command(mode), "test", line -> { });
        var registration = FrameworkUtil.getBundle(getClass()).getBundleContext().registerService(CodexClientFactory.class, () -> session, null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("codex-ide-" + UUID.randomUUID());
        org.eclipse.ui.IViewPart view = null;
        try {
            project.create(null); project.open(null);
            var file = project.getFile("Module.bsl"); file.create(new ByteArrayInputStream(new byte[0]), true, null);
            var directory = Path.of(project.getLocationURI()); var before = hashes(directory);
            var editor = (ITextEditor) IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor");
            var document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            assertFalse(editor.isDirty()); assertNull(new EclipseContextProvider().capture(page).buffer());
            String code = "Процедура Тест()\n    Сообщить(\"Привет\");\nКонецПроцедуры";
            document.set(code); editor.getSelectionProvider().setSelection(new TextSelection(document, 0, 0));
            assertTrue(editor.isDirty());
            var context = new EclipseContextProvider().capture(page);
            assertEquals(code, context.buffer().text()); assertEquals("", context.selectedText());
            assertEquals("", java.nio.file.Files.readString(Path.of(file.getLocationURI())));
            view = page.showView("io.github.zhumaniezov.codex.edt.views.Codex");
            var shell = view.getSite().getShell();
            var prompt = (Text) find(shell, "prompt"); var response = (StyledText) find(shell, "response");
            var send = (Button) find(shell, "send"); var models = (Combo) find(shell, "model"); var levels = (Combo) find(shell, "reasoning");
            assertEquals("Спросите Codex", ((Label) find(shell, "placeholder")).getText());
            waitFor(models::isEnabled, 30, () -> { });
            assertEquals("Default Model", models.getText()); assertEquals("Среднее", levels.getText());
            models.select(0); models.notifyListeners(SWT.Selection, new Event());
            waitFor(() -> "fallback-model".equals(session.model()) && models.isEnabled(), 10, () -> { });
            assertEquals("Лёгкое", levels.getText()); assertEquals(1, levels.getItemCount());
            prompt.setText("Какой код сейчас находится в открытом модуле?");
            var enter = new Event(); enter.keyCode = SWT.CR; enter.doit = true;
            prompt.notifyListeners(SWT.KeyDown, enter); assertFalse(enter.doit);
            waitFor(() -> session.snapshot().state() == State.WORKING && "■".equals(send.getText())
                && response.getData("codex.streamingUpdates") instanceof Integer n && n > 0, 15, () -> { });
            assertEquals("", prompt.getText()); send.notifyListeners(SWT.Selection, new Event());
            waitFor(() -> session.snapshot().state() == State.STOPPED && "↑".equals(send.getText()), 15, () -> { });
            assertTrue(session.processAlive()); assertEquals("thread-1", session.snapshot().threadId());
            String selection = "Сообщить(\"Привет\");";
            editor.getSelectionProvider().setSelection(new TextSelection(document, code.indexOf(selection), selection.length()));
            assertEquals(selection, new EclipseContextProvider().capture(page).selectedText());
            var prefs = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode("io.github.zhumaniezov.codex.edt");
            String previous = prefs.get("enterSends", null);
            try {
                prefs.putBoolean("enterSends", false);
                var normalEnter = new Event(); normalEnter.keyCode = SWT.CR; normalEnter.doit = true;
                prompt.notifyListeners(SWT.KeyDown, normalEnter); assertTrue(normalEnter.doit);
            } finally { if (previous == null) { prefs.remove("enterSends"); } else { prefs.put("enterSends", previous); } }
            prompt.setText("Что делает выделенный код и в каком контексте он находится?");
            var shiftEnter = new Event(); shiftEnter.keyCode = SWT.CR; shiftEnter.stateMask = SWT.SHIFT; shiftEnter.doit = true;
            prompt.notifyListeners(SWT.KeyDown, shiftEnter); assertTrue(shiftEnter.doit); assertEquals(State.STOPPED, session.snapshot().state());
            waitFor(send::isEnabled, 10, () -> { });
            send.notifyListeners(SWT.Selection, new Event());
            waitFor(() -> session.snapshot().state() == State.READY && response.getText().contains(mode.equals("late-turn") ? "ONLY_B_END" : "Привет"), 15, () -> { });
            if (mode.equals("late-turn")) {
                String transcript = response.getText();
                assertTrue(transcript.contains("PARTIAL_A"));
                assertTrue(transcript.indexOf("PARTIAL_A") < transcript.indexOf("ONLY_B_END"));
                assertEquals(transcript.indexOf("PARTIAL_A"), transcript.lastIndexOf("PARTIAL_A"));
                assertFalse(transcript.contains("LATE_A")); assertFalse(transcript.contains("AFTER_ITEM_COMPLETE"));
                assertFalse(transcript.contains("DUPLICATE_ITEM"));
            }
            var newChat = (Button) find(shell, "newThread"); var chats = (Table) find(shell, "threads");
            waitFor(() -> chats.getItemCount() == 1 && newChat.isEnabled(), 10, () -> { });
            prompt.setText("Черновик"); newChat.notifyListeners(SWT.Selection, new Event());
            waitFor(() -> session.snapshot().threadId().isEmpty() && newChat.isEnabled(), 10, () -> { });
            assertEquals("", prompt.getText()); assertTrue(session.processAlive());
            chats.setSelection(0); chats.notifyListeners(SWT.Selection, new Event());
            waitFor(() -> response.getText().contains("Ответ из истории thread-1") && newChat.isEnabled(), 15, () -> { });
            assertEquals("thread-1", session.snapshot().threadId());
            prompt.setText("Продолжим"); send.notifyListeners(SWT.Selection, new Event());
            waitFor(() -> session.snapshot().state() == State.READY && response.getText().contains("Продолжим")
                && newChat.isEnabled(), 15, () -> { });
            assertEquals("thread-1", session.snapshot().threadId()); assertTrue(editor.isDirty()); assertEquals(before, hashes(directory));
            // Снимок только нашего SWT-компонента для визуальной проверки.
            Composite panel = response.getParent();
            Composite originalParent = panel.getParent();
            var preview = new Shell(shell, SWT.SHELL_TRIM);
            preview.setText("Проверка Codex View — 460 px"); preview.setLayout(new org.eclipse.swt.layout.FillLayout());
            try {
                assertTrue(panel.setParent(preview));
                var trim = preview.computeTrim(0, 0, 460, 750); preview.setSize(trim.width, trim.height); preview.open();
                preview.layout(true, true); panel.layout(true, true);
                waitFor(() -> response.getSize().y > 100, 5, () -> { });
                panel.redraw(); panel.update();
                var bounds = panel.getClientArea();
                var origin = panel.toDisplay(0, 0); var sendPoint = send.toDisplay(0, 0);
                assertEquals(460, bounds.width);
                assertTrue("Send должен помещаться в узкой боковой панели", sendPoint.x + send.getSize().x <= origin.x + bounds.width);
                System.out.println("CODEX_UI_LAYOUT width=" + bounds.width + " height=" + bounds.height + " responseHeight=" + response.getSize().y);
                var image = new org.eclipse.swt.graphics.Image(shell.getDisplay(), bounds.width, bounds.height);
                var gc = new org.eclipse.swt.graphics.GC(panel);
                try {
                    gc.copyArea(image, 0, 0); var loader = new org.eclipse.swt.graphics.ImageLoader();
                    loader.data = new org.eclipse.swt.graphics.ImageData[] { image.getImageData() };
                    loader.save(Path.of(System.getProperty("java.io.tmpdir"), "codex-view-stage3.png").toString(), SWT.IMAGE_PNG);
                } finally { gc.dispose(); image.dispose(); }
            } finally { panel.setParent(originalParent); preview.dispose(); originalParent.layout(true, true); }

        } finally {
            if (view != null) { page.hideView(view); } session.close(); session.termination().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(session.processAlive()); registration.unregister(); page.closeAllEditors(false);
            if (project.exists()) { project.delete(true, true, null); } ResourcesPlugin.getWorkspace().save(true, null);
        }
    }
}
