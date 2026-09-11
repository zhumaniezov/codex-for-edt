package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.*;
import org.junit.Test;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.settings.*;

public class SettingsUiTest {
    @Test public void viewIconAndDpiArtworkAreAvailable() throws Exception {
        var bundle = Platform.getBundle("io.github.zhumaniezov.codex.edt");
        var descriptor = PlatformUI.getWorkbench().getViewRegistry().find("io.github.zhumaniezov.codex.edt.views.Codex").getImageDescriptor();
        assertEquals(16, descriptor.getImageData(100).width); assertEquals(32, descriptor.getImageData(200).width);
        var type = bundle.loadClass("io.github.zhumaniezov.codex.edt.ui.IconResources");
        for (String name : java.util.List.of("codex", "newChat", "refresh", "settings", "account", "send", "stop", "history")) {
            for (boolean dark : new boolean[] {false, true}) {
                var image = (ImageDescriptor) type.getMethod("descriptor", String.class, boolean.class).invoke(null, name, dark);
                for (int zoom : new int[] {100,150,200,300,400}) {
                    var data = image.getImageData(zoom); assertNotNull(data); assertEquals(16 * zoom / 100, data.width); assertNotNull(data.alphaData);
                }
            }
        }
    }
    @Test public void themeModelDoesNotDependOnFixedEdtThemeNames() throws Exception {
        var type = Platform.getBundle("io.github.zhumaniezov.codex.edt").loadClass("io.github.zhumaniezov.codex.edt.ui.ThemeService");
        var method = type.getMethod("luminance", int.class, int.class, int.class);
        assertTrue((int) method.invoke(null, 20,30,40) < 128); assertTrue((int) method.invoke(null, 230,220,210) >= 128);
    }
    @Test public void lateThemeEventsAfterDisposeCannotUpdateControls() throws Exception {
        var shell = new Shell(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var type = Platform.getBundle("io.github.zhumaniezov.codex.edt").loadClass("io.github.zhumaniezov.codex.edt.ui.ThemeService");
        var service = (AutoCloseable) type.getConstructor(Control.class, Runnable.class).newInstance(shell, (Runnable) calls::incrementAndGet);
        var refresh = type.getDeclaredMethod("refresh"); refresh.setAccessible(true);
        refresh.invoke(service); shell.dispose(); refresh.invoke(service); service.close();
        while (Display.getCurrent().readAndDispatch()) { }
        assertEquals(0, calls.get());
    }
    @Test public void russianToolbarAndSettingsPagesUseIsolatedSettingsSession() throws Exception { ui("ru"); }
    @Test public void englishToolbarAndSettingsPagesUseIsolatedSettingsSession() throws Exception { ui("en"); }
    private void ui(String language) throws Exception {
        var store = EdtPreferencesService.store(); String previous = store.getString(EdtPreferencesService.LANGUAGE);
        store.setValue(EdtPreferencesService.LANGUAGE, language); store.save();
        var command = TestServer.command("normal");
        var session = new CodexSessionService(command, "test", line -> { });
        var created = new java.util.concurrent.CopyOnWriteArrayList<CodexSessionService>(); created.add(session);
        var registration = FrameworkUtil.getBundle(getClass()).getBundleContext().registerService(CodexClientFactory.class, () -> { if (created.size() == 1 && !session.processAlive()) { return session; } var settings = new CodexSessionService(command, "test", line -> { }); created.add(settings); return settings; }, null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(); IViewPart view = null;
        try {
            view = page.showView("io.github.zhumaniezov.codex.edt.views.Codex"); var shell = view.getSite().getShell();
            var model = (Combo) find(shell, "model"); waitFor(model::isEnabled, 15, () -> { });
            assertEquals(language.equals("ru") ? "Спросите Codex…" : "Ask Codex…", ((Label) find(shell, "placeholder")).getText());
            assertEquals(language.equals("ru") ? "Отправить" : "Send", find(shell, "send").getToolTipText());
            for (String role : java.util.List.of("newThread", "refreshThreads", "settings", "account", "send")) { assertNotNull(find(shell, role).getData("codex.icon")); }
            find(shell, "refreshThreads").notifyListeners(SWT.Selection, new Event()); assertTrue(session.processAlive());
            for (var element : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.preferencePages")) {
                if (!element.getAttribute("id").startsWith("io.github.zhumaniezov.codex.edt.preferences")) { continue; }
                var preference = (PreferencePage) element.createExecutableExtension("class");
                var holder = new Shell(shell); holder.setLayout(new org.eclipse.swt.layout.FillLayout());
                try {
                    ((IWorkbenchPreferencePage) preference).init(PlatformUI.getWorkbench()); preference.createControl(holder);
                    holder.setSize(700,600); holder.open(); holder.layout(true,true);
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(400);
                    waitFor(() -> System.nanoTime() >= deadline, 4, () -> { });
                    assertFalse(preference.getControl().isDisposed()); assertTrue(session.processAlive());
                } finally { preference.dispose(); holder.dispose();
                    if (created.size() > 1) { var settings = created.get(created.size() - 1); waitFor(() -> !settings.processAlive(), 10, () -> { }); } }
            }
            var body = find(shell, "response").getParent();
            var preview = new Shell(shell); preview.setLayout(new org.eclipse.swt.layout.FillLayout());
            var original = body.getParent();
            try {
                body.setParent(preview); preview.setSize(390,750); preview.open(); preview.layout(true,true);
                var response = (org.eclipse.swt.custom.StyledText) find(preview, "response");
                assertTrue(response.getSize().y > 100);
                var image = new org.eclipse.swt.graphics.Image(shell.getDisplay(), body.getSize().x, body.getSize().y);
                var gc = new org.eclipse.swt.graphics.GC(body);
                try { gc.copyArea(image,0,0); var loader = new org.eclipse.swt.graphics.ImageLoader(); loader.data = new org.eclipse.swt.graphics.ImageData[] {image.getImageData()};
                    loader.save(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codex-stage5-"+language+".png").toString(), SWT.IMAGE_PNG);
                } finally { gc.dispose(); image.dispose(); }
            } finally { body.setParent(original); preview.dispose(); original.layout(true,true); }
        } finally {
            if (view != null) { page.hideView(view); } created.forEach(CodexSessionService::close); registration.unregister();
            store.setValue(EdtPreferencesService.LANGUAGE, previous); store.save();
        }
    }
}
