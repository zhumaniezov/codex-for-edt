package io.github.zhumaniezov.codex.edt.ui;

import org.eclipse.ui.*;
import io.github.zhumaniezov.codex.edt.CodexPlugin;
import io.github.zhumaniezov.codex.edt.settings.EdtPreferencesService;

public final class AutoOpenCodex implements IStartup {
    @Override public void earlyStartup() {
        if (!EdtPreferencesService.get(EdtPreferencesService.AUTO_OPEN, false)) { return; }
        var workbench = PlatformUI.getWorkbench(); var display = workbench.getDisplay();
        display.asyncExec(() -> {
            if (display.isDisposed() || workbench.isClosing()) { return; }
            var window = workbench.getActiveWorkbenchWindow(); if (window == null || window.getActivePage() == null) { return; }
            var page = window.getActivePage();
            if (page.findViewReference(CodexView.ID) != null) { return; }
            try { page.showView(CodexView.ID, null, IWorkbenchPage.VIEW_VISIBLE); }
            catch (PartInitException error) { CodexPlugin.log("Codex auto-open", error); }
        });
    }
}
