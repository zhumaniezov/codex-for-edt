package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.*;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import io.github.zhumaniezov.codex.edt.CodexPlugin;
import io.github.zhumaniezov.codex.edt.ui.render.*;

final class FileLinkService implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> { var thread = new Thread(task, "codex-file-links"); thread.setDaemon(true); return thread; });
    private final Control owner;
    private final Supplier<String> cwd;
    private final Supplier<IWorkbenchPage> page;
    private volatile boolean closed;
    FileLinkService(Control owner, Supplier<String> cwd, Supplier<IWorkbenchPage> page) {
        this.owner = owner; this.cwd = cwd; this.page = page; owner.addDisposeListener(event -> close());
    }
    static void openHttp(String link) {
        if (!MarkdownDocument.safeLink(link)) { return; }
        try { PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(java.net.URI.create(link).toURL()); }
        catch (Exception error) { CodexPlugin.log(tr("text041"), error); }
    }
    void open(String link) {
        if (MarkdownDocument.safeLink(link)) { openHttp(link); return; }
        if (closed || owner.isDisposed()) { return; }
        String directory = cwd.get(); var display = owner.getDisplay();
        if (directory.isBlank()) { return; }
        executor.execute(() -> {
            try {
                var target = FileLinkTarget.resolve(link, Path.of(directory));
                if (closed || display.isDisposed()) { return; }
                display.asyncExec(() -> {
                    if (closed || owner.isDisposed()) { return; }
                    var current = page.get(); if (current == null) { return; }
                    var files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(target.path().toUri());
                    try {
                        for (var file : files) {
                            if (!file.exists()) { continue; }
                            var editor = IDE.openEditor(current, file, true);
                            var text = editor instanceof ITextEditor value ? value : editor.getAdapter(ITextEditor.class);
                            if (text != null) {
                                var document = text.getDocumentProvider().getDocument(text.getEditorInput());
                                if (document != null && target.line() <= document.getNumberOfLines()) { text.selectAndReveal(document.getLineOffset(target.line() - 1), 0); }
                            }
                            return;
                        }
                        org.eclipse.jface.dialogs.MessageDialog.openInformation(owner.getShell(), tr("openLink"), tr("linkBlocked"));
                    } catch (Exception error) { CodexPlugin.log(tr("linkBlocked"), error); }
                });
            } catch (Exception error) {
                CodexPlugin.log(tr("linkBlocked"), error);
                if (!closed && !display.isDisposed()) { display.asyncExec(() -> {
                    if (!closed && !owner.isDisposed()) { org.eclipse.jface.dialogs.MessageDialog.openInformation(owner.getShell(), tr("openLink"), tr("linkBlocked")); }
                }); }
            }
        });
    }
    @Override public void close() { closed = true; executor.shutdownNow(); }
}
