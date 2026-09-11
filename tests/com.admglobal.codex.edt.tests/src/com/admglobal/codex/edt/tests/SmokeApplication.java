package com.admglobal.codex.edt.tests;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;
import org.junit.runner.JUnitCore;

/** Локальная проверка без PDE/Maven; в устанавливаемую feature не входит. */
public final class SmokeApplication implements IApplication {
    private int result = 1;

    @Override
    public Object start(IApplicationContext context) {
        Display display = PlatformUI.createDisplay();
        try {
            PlatformUI.createAndRunWorkbench(display, new WorkbenchAdvisor() {
                @Override
                public String getInitialWindowPerspectiveId() {
                    return "org.eclipse.ui.resourcePerspective";
                }

                @Override
                public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
                    return new WorkbenchWindowAdvisor(configurer) {
                        @Override
                        public void preWindowOpen() {
                            configurer.setInitialSize(new Point(1000, 700));
                            configurer.setTitle("Codex EDT - isolated smoke test");
                        }
                    };
                }

                @Override
                public void postStartup() {
                    display.asyncExec(() -> {
                        try {
                            var run = JUnitCore.runClasses(CodexViewTest.class);
                            run.getFailures().forEach(failure -> System.err.println(failure.getTrace()));
                            result = run.wasSuccessful() ? 0 : 1;
                            System.out.println("CODEX_SMOKE tests=" + run.getRunCount()
                                + " failures=" + run.getFailureCount());
                        } finally {
                            PlatformUI.getWorkbench().close();
                        }
                    });
                }
            });
        } finally {
            display.dispose();
        }
        return result;
    }

    @Override
    public void stop() {
        if (PlatformUI.isWorkbenchRunning()) {
            var workbench = PlatformUI.getWorkbench();
            workbench.getDisplay().asyncExec(workbench::close);
        }
    }
}
