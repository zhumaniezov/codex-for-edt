package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.PlatformUI;

/** Два настоящих запуска EDT с одной сохранённой рабочей областью. */
public final class RestoreScenario {
    private static final String ID = "io.github.zhumaniezov.codex.edt.views.Codex";

    public static void run(String phase) throws Exception {
        var workbench = PlatformUI.getWorkbench();
        var page = workbench.getActiveWorkbenchWindow().getActivePage();
        assertNotNull(page);
        String encodedProject = System.getProperty("codex.edt.restore.project64");
        String projectName = encodedProject == null ? null : new String(java.util.Base64.getDecoder().decode(encodedProject), java.nio.charset.StandardCharsets.UTF_8);
        if ("seed".equals(phase) && projectName != null) {
            var workspace = org.eclipse.core.resources.ResourcesPlugin.getWorkspace();
            var project = workspace.getRoot().getProject(projectName);
            var description = workspace.loadProjectDescription(workspace.getRoot().getLocation().append(projectName).append(".project"));
            project.create(description, null); project.open(null);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            ViewScenario.waitFor(() -> System.nanoTime() > deadline, 20, () -> { });
        }
        var manager = workbench.getActivitySupport().getActivityManager();
        var identifier = manager.getIdentifier("io.github.zhumaniezov.codex.edt/" + ID);
        System.out.println("RESTORE phase=" + phase + " descriptor=" + workbench.getViewRegistry().find(ID)
            + " activities=" + identifier.getActivityIds() + " enabled=" + identifier.isEnabled()
            + " bundleState=" + Platform.getBundle("io.github.zhumaniezov.codex.edt").getState());
        for (var ref : page.getViewReferences()) {
            if (ref.getId().toLowerCase(java.util.Locale.ROOT).contains("codex")) {
                var existing = ref.getView(false);
                System.out.println("RESTORE reference=" + ref.getId() + " part=" + (existing == null ? null : existing.getClass().getName()));
            }
        }
        if ("seed".equals(phase)) {
            var preferences = io.github.zhumaniezov.codex.edt.settings.EdtPreferencesService.store();
            preferences.setValue(io.github.zhumaniezov.codex.edt.settings.EdtPreferencesService.LANGUAGE, "en"); preferences.save();
            page.closeAllEditors(false);
            page.showView(ID);
        }
        if (!"seed".equals(phase)) { assertEquals("en", io.github.zhumaniezov.codex.edt.settings.EdtPreferencesService.store().getString(io.github.zhumaniezov.codex.edt.settings.EdtPreferencesService.LANGUAGE)); }
        var reference = page.findViewReference(ID);
        assertNotNull("Сохранённая панель должна присутствовать", reference);
        var view = reference.getView(true);
        assertNotNull(view);
        assertEquals("io.github.zhumaniezov.codex.edt.ui.CodexView", view.getClass().getName());
        assertNotNull(ViewScenario.find(view.getSite().getShell(), "prompt"));
        if (projectName == null) {
            assertNull(page.getActiveEditor());
            assertEquals(0, org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProjects().length);
        } else if (!"seed".equals(phase)) {
            var provider = new io.github.zhumaniezov.codex.edt.context.EclipseContextProvider();
            ViewScenario.waitFor(() -> projectName.equals(provider.capture(page).projectName()), 60, () -> { });
            var context = provider.capture(page);
            assertEquals(projectName, context.projectName());
            System.out.println("RESTORE BSL editor=" + page.getActiveEditor().getClass().getName() + " module=" + context.modulePath());
        }
        // Панель намеренно остаётся открытой: workbench.close() сохраняет её штатно.
    }
}
