package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import java.nio.file.*;
import org.junit.Test;
import io.github.zhumaniezov.codex.edt.ui.render.*;

public class FileLinkTest {
    @Test public void resolvesOnlyExistingProjectFilesAndLineNumbers() throws Exception {
        var parent = Files.createTempDirectory("codex-links-"); var project = Files.createDirectory(parent.resolve("project"));
        var file = Files.writeString(project.resolve("Module.bsl"), "first\nsecond"); var outside = Files.writeString(parent.resolve("outside.bsl"), "outside");
        try {
            assertEquals(new FileLinkTarget(file.toRealPath(), 2), FileLinkTarget.resolve("Module.bsl#L2", project));
            assertEquals(file.toRealPath(), FileLinkTarget.resolve(file + ":1", project).path());
            assertThrows(java.io.IOException.class, () -> FileLinkTarget.resolve("../outside.bsl", project));
            assertThrows(java.io.IOException.class, () -> FileLinkTarget.resolve(outside.toString(), project));
            assertThrows(java.io.IOException.class, () -> FileLinkTarget.resolve("missing.bsl", project));
            assertThrows(java.io.IOException.class, () -> FileLinkTarget.resolve("https://example.invalid/Module.bsl", project));
        } finally { Files.delete(file); Files.delete(outside); Files.delete(project); Files.delete(parent); }
    }
    @Test public void markdownCanKeepFileLinksWithoutAllowingCommandsOrHtmlExecution() {
        assertFalse(FileLinkTarget.candidate("command:run")); assertFalse(FileLinkTarget.candidate("javascript:alert(1)"));
        assertFalse(FileLinkTarget.candidate("file://server/share/code.bsl"));
        var rendered = MarkdownDocument.parse("[file](Module.bsl#L3) **bold** `inline`\n\n```bsl\nСообщить();\n```", link -> MarkdownDocument.safeLink(link) || FileLinkTarget.candidate(link));
        assertTrue(rendered.spans().stream().anyMatch(span -> span.style().link().equals("Module.bsl#L3")));
        assertTrue(rendered.spans().stream().anyMatch(span -> span.style().code() && rendered.text().substring(span.start(), span.start()+span.length()).contains("Сообщить")));
    }
    @org.junit.Test public void fileLinkOpensWorkspaceEditorAtRequestedLine() throws Exception {
        var project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject("CodexFileLinkTest");
        project.create(null); project.open(null);
        var page = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var shell = new org.eclipse.swt.widgets.Shell(page.getWorkbenchWindow().getShell());
        AutoCloseable service = null;
        try {
            var file = project.getFile("Module.txt"); file.create(new java.io.ByteArrayInputStream("first\nsecond\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), true, null);
            var type = org.eclipse.core.runtime.Platform.getBundle("io.github.zhumaniezov.codex.edt").loadClass("io.github.zhumaniezov.codex.edt.ui.FileLinkService");
            var constructor = type.getDeclaredConstructor(org.eclipse.swt.widgets.Control.class, java.util.function.Supplier.class, java.util.function.Supplier.class); constructor.setAccessible(true);
            service = (AutoCloseable) constructor.newInstance(shell, (java.util.function.Supplier<String>) () -> project.getLocation().toOSString(), (java.util.function.Supplier<org.eclipse.ui.IWorkbenchPage>) () -> page);
            var open = type.getDeclaredMethod("open", String.class); open.setAccessible(true); open.invoke(service, "Module.txt#L2");
            ViewScenario.waitFor(() -> page.getActiveEditor() != null && file.equals(page.getActiveEditor().getEditorInput().getAdapter(org.eclipse.core.resources.IFile.class)), 10, () -> { });
            var editor = page.getActiveEditor().getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
            org.junit.Assert.assertNotNull(editor);
            org.junit.Assert.assertEquals(1, ((org.eclipse.jface.text.ITextSelection) editor.getSelectionProvider().getSelection()).getStartLine());
            page.closeEditor(page.getActiveEditor(), false);
        } finally { if (service != null) { service.close(); } shell.dispose(); project.delete(true, true, null); }
    }
}
