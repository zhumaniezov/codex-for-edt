package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.State;

final class DirtyLiveScenario {
    static void run() throws Exception {
        var session = new CodexSessionService(line -> { });
        var registration = FrameworkUtil.getBundle(DirtyLiveScenario.class).getBundleContext()
            .registerService(CodexClientFactory.class, () -> session, null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("codex-dirty-" + UUID.randomUUID());
        org.eclipse.ui.IViewPart view = null;
        try {
            project.create(null); project.open(null);
            var file = project.getFile("Module.bsl"); file.create(new ByteArrayInputStream(new byte[0]), true, null);
            Path directory = Path.of(project.getLocationURI()); var before = hashes(directory);
            var editor = (ITextEditor) IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor");
            var doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            String code = "Процедура Тест()\n    Сообщить(\"Привет\");\nКонецПроцедуры";
            doc.set(code); editor.getSelectionProvider().setSelection(new TextSelection(doc, 0, 0));
            assertTrue(editor.isDirty());
            view = page.showView("io.github.zhumaniezov.codex.edt.views.Codex"); var shell = view.getSite().getShell();
            var prompt = (Text) find(shell, "prompt"); var send = (Button) find(shell, "send");
            var response = (StyledText) find(shell, "response");
            prompt.setText("Какой код сейчас находится в открытом модуле?"); waitFor(() -> send.isEnabled() && "↑".equals(send.getText()), 60, () -> { });
            waitFor(() -> send.isEnabled() && "↑".equals(send.getText()), 15, () -> { });
            send.notifyListeners(SWT.Selection, new Event());
            assertEquals("Вопрос должен быть отправлен через composer", "", prompt.getText());
            waitFor(() -> session.snapshot().state() == State.READY && response.getText().contains("Привет")
                && response.getText().contains("Тест"), 180, () -> { });
            assertTrue((Integer) response.getData("codex.streamingUpdates") > 0);
            assertEquals(directory.toRealPath().toString(), session.snapshot().cwd());
            String id = session.snapshot().threadId();
            String selected = "Сообщить(\"Привет\");";
            editor.getSelectionProvider().setSelection(new TextSelection(doc, code.indexOf(selected), selected.length()));
            prompt.setText("Что делает выделенный код и в каком контексте он находится?");
            waitFor(() -> send.isEnabled() && "↑".equals(send.getText()), 15, () -> { });
            send.notifyListeners(SWT.Selection, new Event());
            assertEquals("Вопрос должен быть отправлен через composer", "", prompt.getText());
            waitFor(() -> session.snapshot().state() == State.READY && response.getData("codex.streamingUpdates") instanceof Integer n && n > 0
                && response.getText().contains("Привет"), 180, () -> { });
            assertEquals(id, session.snapshot().threadId()); assertTrue(editor.isDirty()); assertEquals(code, doc.get());
            assertEquals(before, hashes(directory)); assertEquals("", java.nio.file.Files.readString(Path.of(file.getLocationURI())));
            var threads = session.threads("").toCompletableFuture(); waitFor(threads::isDone, 30, () -> { });
            assertTrue(threads.get().threads().stream().anyMatch(thread -> thread.id().equals(id)));
            var fresh = session.newThread().toCompletableFuture(); waitFor(fresh::isDone, 30, () -> { }); fresh.get();
            var resume = session.resume(id).toCompletableFuture(); waitFor(resume::isDone, 60, () -> { });
            var messages = resume.get().messages();
            var answers = messages.stream().filter(message -> message.role().equals("Codex")).toList();
            assertTrue(answers.size() >= 2);
            String latest = answers.get(answers.size() - 1).text();
            assertTrue("Ответ должен учитывать окружающую несохранённую процедуру", latest.contains("Тест") && latest.contains("Сообщить"));
            assertEquals(id, session.snapshot().threadId()); assertEquals(before, hashes(directory));
            System.out.println("CODEX_DIRTY_LIVE model=" + session.model()
                + " noSelection=true selectedSurroundingBuffer=true historyResume=true cwdVerified=true filesUnchanged=true");
        } finally {
            if (view != null) { page.hideView(view); } session.close(); session.termination().get(10, TimeUnit.SECONDS);
            assertFalse(session.processAlive()); registration.unregister(); page.closeAllEditors(false);
            if (project.exists()) { project.delete(true, true, null); } ResourcesPlugin.getWorkspace().save(true, null);
        }
    }
}
