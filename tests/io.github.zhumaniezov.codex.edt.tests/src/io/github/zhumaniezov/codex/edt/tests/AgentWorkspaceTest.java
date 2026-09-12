package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.waitFor;
import java.nio.file.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.resources.*;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.context.*;
import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;

public class AgentWorkspaceTest {
    @Test
    public void refreshCreateEditDeleteAndCleanEditor() throws Exception {
        var p = create();
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorPart editor = null;
        try {
            var root = Path.of(p.getLocationURI());
            Files.writeString(root.resolve("test-agent-mode.txt"), "old");
            refresh(root.toString());
            var file = p.getFile("test-agent-mode.txt");
            assertTrue(file.exists());
            editor = IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor");
            var text = (ITextEditor) editor;
            var document = text.getDocumentProvider().getDocument(text.getEditorInput());
            assertEquals("old", document.get());
            Files.writeString(root.resolve("test-agent-mode.txt"), "new content");
            refresh(root.toString());
            waitFor(() -> document.get().equals("new content"), 8, () -> {
            });
            assertFalse(editor.isDirty());
            Files.delete(root.resolve("test-agent-mode.txt"));
            refresh(root.toString());
            assertFalse(file.exists());
        } finally {
            if (editor != null) {
                page.closeEditor(editor, false);
            }
            p.delete(true, true, null);
        }
    }

    @Test
    public void dirtyEditorCancelKeepsBufferAndDisk() throws Exception {
        dirty(false);
    }

    @Test
    public void dirtyEditorSaveThenProtectAndRelease() throws Exception {
        dirty(true);
    }

    private void dirty(boolean save) throws Exception {
        var p = create();
        var workbench = PlatformUI.getWorkbench();
        var page = workbench.getActiveWorkbenchWindow().getActivePage();
        IEditorPart editor = null;
        ProjectWriteGuard guard = null;
        try {
            var file = p.getFile("Module.bsl");
            file.create(new ByteArrayInputStream("saved".getBytes(StandardCharsets.UTF_8)), true, null);
            editor = IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor");
            var text = (ITextEditor) editor;
            var document = text.getDocumentProvider().getDocument(text.getEditorInput());
            document.set("unsaved buffer");
            assertTrue(editor.isDirty());
            chooseDialog(save ? tr("dirtySave") : tr("cancel"));
            guard = ProjectWriteGuard.acquire(workbench, workbench.getActiveWorkbenchWindow().getShell(),
                    Path.of(p.getLocationURI()).toString(), () -> fail("Unexpected abort"));
            if (save) {
                assertNotNull(guard);
                assertFalse(editor.isDirty());
                assertEquals("unsaved buffer", Files.readString(Path.of(file.getLocationURI())));
                var target = editor.getAdapter(ITextOperationTarget.class);
                if (target instanceof ITextViewer viewer) {
                    assertFalse(viewer.getTextWidget().isEnabled());
                }
                guard.close();
                guard = null;
                if (target instanceof ITextViewer viewer) {
                    assertTrue(viewer.getTextWidget().isEnabled());
                }
            } else {
                assertNull(guard);
                assertEquals("saved", Files.readString(Path.of(file.getLocationURI())));
                assertEquals("unsaved buffer", document.get());
                assertTrue(editor.isDirty());
            }
        } finally {
            if (guard != null) {
                guard.close();
            }
            if (editor != null) {
                page.closeEditor(editor, false);
            }
            p.delete(true, true, null);
        }
    }

    @Test
    public void rejectsNonWorkspaceRoot() {
        assertThrows(IllegalStateException.class, () -> ProjectWriteGuard.project("C:/this-is-not-an-edt-project"));
    }

    @Test
    public void compareParserUsesOfficialHunks() throws Exception {
        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.zhumaniezov.codex.edt");
        var type = bundle.loadClass("io.github.zhumaniezov.codex.edt.ui.DiffReviewService");
        var root = type.getMethod("parse", java.util.Map.class).invoke(null,
                java.util.Map.of("Module.bsl", "--- a/Module.bsl\n+++ b/Module.bsl\n@@ -1 +1 @@\n-old\n+new\n"));
        var children = (Object[]) root.getClass().getMethod("getChildren").invoke(root);
        assertEquals(1, children.length);
    }

    @Test
    public void compareDistinguishesCreatedAndDeletedFileSides() throws Exception {
        var type = org.eclipse.core.runtime.Platform.getBundle("io.github.zhumaniezov.codex.edt")
                .loadClass("io.github.zhumaniezov.codex.edt.ui.DiffReviewService");
        for (var kind : java.util.List.of("add", "delete")) {
            var root = (org.eclipse.compare.structuremergeviewer.DiffNode) type
                    .getMethod("file", String.class, String.class, String.class)
                    .invoke(null, "test.txt", kind, "DATA\n");
            var node = (org.eclipse.compare.structuremergeviewer.DiffNode) root.getChildren()[0];
            try (var before = ((org.eclipse.compare.IStreamContentAccessor) node.getLeft()).getContents();
                    var after = ((org.eclipse.compare.IStreamContentAccessor) node.getRight()).getContents()) {
                assertEquals(kind.equals("delete") ? "DATA\n" : "",
                        new String(before.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(kind.equals("add") ? "DATA\n" : "",
                        new String(after.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    private IProject create() throws Exception {
        var p = ResourcesPlugin.getWorkspace().getRoot().getProject("CodexAgentTest" + System.nanoTime());
        p.create(null);
        p.open(null);
        return p;
    }

    private void refresh(String root) throws Exception {
        var done = ProjectRefreshService.refresh(root).toCompletableFuture();
        waitFor(done::isDone, 10, () -> {
        });
        done.get();
    }

    private void chooseDialog(String caption) {
        var display = Display.getCurrent();
        var clicked = new AtomicBoolean();
        Runnable search = new Runnable() {
            int attempts;

            public void run() {
                for (var shell : display.getShells()) {
                    if (click(shell, caption)) {
                        clicked.set(true);
                        break;
                    }
                }
                if (!clicked.get() && ++attempts < 100) {
                    display.timerExec(50, this);
                }
            }
        };
        display.timerExec(50, search);
    }

    private boolean click(Composite parent, String caption) {
        for (var c : parent.getChildren()) {
            if (c instanceof Button button && button.getText().replace("&", "").equals(caption)) {
                button.notifyListeners(SWT.Selection, new Event());
                return true;
            }
            if (c instanceof Composite nested && click(nested, caption)) {
                return true;
            }
        }
        return false;
    }
}
