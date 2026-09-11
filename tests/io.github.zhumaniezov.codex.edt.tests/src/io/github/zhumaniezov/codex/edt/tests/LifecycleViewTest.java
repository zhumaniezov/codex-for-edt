package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;

@RunWith(Parameterized.class)
public class LifecycleViewTest {
    @Parameterized.Parameters(name = "{0}") public static Object[] cases() {
        return new Object[] { "no-window", "no-page", "no-editor", "no-project", "missing", "exit", "auth",
            "account-read-error", "model-list-error", "thread-list-error", "factory-error", "dispose-pending" };
    }
    @Parameterized.Parameter public String mode;

    @Test public void controlsExistIndependentlyOfContextAndConnection() throws Exception {
        var calls = new AtomicInteger(); var released = new AtomicBoolean();
        var session = new CodexSessionService(mode.equals("missing")
            ? List.of(Path.of(System.getProperty("java.io.tmpdir"), "missing-codex.exe").toString())
            : TestServer.command(mode.equals("dispose-pending") ? "slow-init" : mode), "test", text -> { });
        var registration = FrameworkUtil.getBundle(getClass()).getBundleContext().registerService(CodexClientFactory.class, () -> {
            assertNull("Фабрика не должна работать на UI-потоке", Display.getCurrent());
            if (mode.equals("factory-error") && calls.getAndIncrement() == 0) { throw new IllegalStateException("Тестовая ошибка создания клиента"); }
            calls.incrementAndGet(); return session;
        }, null);
        var shell = new Shell(Display.getDefault()); IViewPart view = null;
        try {
            var element = java.util.Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.views"))
                .filter(value -> "io.github.zhumaniezov.codex.edt.views.Codex".equals(value.getAttribute("id"))).findFirst().orElseThrow();
            view = (IViewPart) element.createExecutableExtension("class");
            assertEquals(0, calls.get());
            var page = mode.equals("no-page") || mode.equals("no-window") ? null : (IWorkbenchPage) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { IWorkbenchPage.class }, (proxy, method, args) -> null);
            var site = (IViewSite) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IViewSite.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getShell" -> shell;
                    case "getPage" -> page;
                    case "getWorkbenchWindow" -> mode.equals("no-window") ? null : PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    case "getId" -> "io.github.zhumaniezov.codex.edt.views.Codex";
                    default -> null;
                });
            view.init(site); var parent = new Composite(shell, 0); view.createPartControl(parent);
            assertNotNull(find(parent, "prompt")); assertNotNull(find(parent, "response"));
            assertEquals("Подключение...", ((Label) find(parent, "status")).getText());
            assertEquals(0, calls.get());
            var retry = (Button) find(parent, "reconnect");
            var status = (Label) find(parent, "status");
            if (mode.equals("dispose-pending")) {
                waitFor(session::processAlive, 10, () -> { });
                parent.dispose(); view.dispose(); view = null;
                session.termination().whenComplete((value, failure) -> released.set(true));
                waitFor(released::get, 12, () -> { }); assertFalse(session.processAlive());
                return;
            }
            boolean error = mode.equals("missing") || mode.equals("exit") || mode.endsWith("error");
            waitFor(() -> retry.isEnabled() && (!error || status.getText().startsWith("Ошибка")), 25, () -> { });
            assertFalse(parent.isDisposed()); assertTrue(retry.isEnabled());
            if (mode.equals("auth")) { assertEquals("Требуется вход", status.getText()); }
            if (mode.startsWith("no-")) {
                ((Text) find(parent, "prompt")).setText("Запрос без проекта");
                find(parent, "send").notifyListeners(org.eclipse.swt.SWT.Selection, new Event());
                waitFor(() -> "Ошибка".equals(status.getText()) && retry.isEnabled(), 10, () -> { });
                assertFalse(parent.isDisposed());
            }
            if (mode.equals("factory-error")) {
                retry.notifyListeners(org.eclipse.swt.SWT.Selection, new Event());
                waitFor(() -> "Подключён".equals(status.getText()) && retry.isEnabled(), 20, () -> { });
            }
            // Dispose родительского control тоже закрывает ресурсы, даже до View.dispose().
            parent.dispose(); view.dispose(); view = null;
            session.termination().whenComplete((value, failure) -> released.set(true));
            waitFor(released::get, 12, () -> { }); assertFalse(session.processAlive());
        } finally { if (view != null) { view.dispose(); } shell.dispose(); registration.unregister(); session.close(); }
    }
}
