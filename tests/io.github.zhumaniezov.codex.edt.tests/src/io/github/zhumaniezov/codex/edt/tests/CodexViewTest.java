package io.github.zhumaniezov.codex.edt.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import io.github.zhumaniezov.codex.edt.client.CodexClientFactory;
import static org.junit.Assert.*;
import io.github.zhumaniezov.codex.edt.context.EclipseContextProvider;

/** Проверяет реальные SWT, реестр расширений, ресурсы и выделение редактора. */
public class CodexViewTest {
    private static final String VIEW_ID = "io.github.zhumaniezov.codex.edt.views.Codex";
    private ServiceRegistration<CodexClientFactory> factory;

    @Before
    public void localClient() {
        factory = FrameworkUtil.getBundle(getClass()).getBundleContext()
            .registerService(CodexClientFactory.class, MockCodexClient::new, null);
    }

    @After
    public void releaseFactory() { factory.unregister(); }

    @Test
    public void opensViewAndReadsEditorSelectionAfterChatGetsFocus() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        var page = window.getActivePage();
        var view = page.showView(VIEW_ID);
        var shell = window.getShell();
        Text prompt = (Text) find(shell, "prompt");
        Text response = (Text) find(shell, "response");
        Button send = (Button) find(shell, "send");
        assertEquals("Codex", view.getTitle());
        assertFalse(send.isEnabled());
        prompt.setText("  ");
        assertFalse(send.isEnabled());
        prompt.setText("Проверка");
        await(() -> send.isEnabled());
        send.notifyListeners(SWT.Selection, new Event());
        await(() -> send.isEnabled());
        assertTrue(response.getText().contains("Тестовый ответ Codex"));

        IProject project = ResourcesPlugin.getWorkspace().getRoot()
            .getProject("codex-smoke-" + UUID.randomUUID());
        try {
            project.create(null);
            project.open(null);
            var file = project.getFile("Module.bsl");
            file.create(new ByteArrayInputStream("// Сохранённый текст".getBytes(StandardCharsets.UTF_8)), true, null);
            var editor = (ITextEditor) IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor");
            var document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            document.set("// Несохранённый текст");
            editor.getSelectionProvider().setSelection(new TextSelection(document, 3, 18));
            String selected = ((org.eclipse.jface.text.ITextSelection)
                editor.getSelectionProvider().getSelection()).getText();
            var provider = new EclipseContextProvider();
            var context = provider.capture(page);
            assertEquals(project.getName(), context.projectName());
            assertEquals(java.nio.file.Path.of(project.getLocationURI()).toString(), context.projectDirectory());
            assertEquals(file.getFullPath().toPortableString(), context.modulePath());
            assertEquals(selected, context.selectedText());

            page.activate(view);
            prompt.setText("Контекст");
            send.notifyListeners(SWT.Selection, new Event());
            await(() -> send.isEnabled());
            assertTrue(response.getText().contains(selected));
            assertTrue(response.getText().contains(project.getName()));

            // Новое выделение заменяет прежнее, в том числе при снятии выделения.
            editor.getSelectionProvider().setSelection(new TextSelection(document, 0, 0));
            assertEquals("", provider.capture(page).selectedText());

            var otherFile = project.getFile("notes.txt");
            otherFile.create(new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)), true, null);
            var other = IDE.openEditor(page, otherFile, "org.eclipse.ui.DefaultTextEditor");
            assertEquals("", provider.capture(page).modulePath());
            assertEquals("", provider.capture(page).selectedText());
            page.closeEditor(other, false);
            page.closeEditor(editor, false);
            assertEquals("", provider.capture(page).modulePath());

            page.hideView(view);
            view = page.showView(VIEW_ID);
            assertEquals("Codex", view.getTitle());
            page.hideView(view);
        } finally {
            page.closeAllEditors(false);
            // Удаляется только созданный тестом проект в отдельной рабочей области.
            project.delete(true, true, null);
            ResourcesPlugin.getWorkspace().save(true, null);
        }
    }

    private static Control find(Composite root, String role) {
        for (Control child : root.getChildren()) {
            if (role.equals(child.getData("codex.role"))) {
                return child;
            }
            if (child instanceof Composite composite) {
                Control found = find(composite, role);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Display display = Display.getCurrent();
        display.timerExec(10000, () -> { });
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        assertTrue("Timed out waiting for the mock response", condition.getAsBoolean());
    }
}
