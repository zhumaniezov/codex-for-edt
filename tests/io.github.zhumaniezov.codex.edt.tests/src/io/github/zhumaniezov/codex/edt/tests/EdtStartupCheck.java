package io.github.zhumaniezov.codex.edt.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.junit.runner.JUnitCore;

/** Явно включаемая проверка внутри полного EDT в одноразовой рабочей области. */
public final class EdtStartupCheck implements IStartup {
    @Override
    public void earlyStartup() {
        String resultPath = System.getProperty("codex.edt.smoke.result");
        if (resultPath == null) {
            return;
        }
        var workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().asyncExec(() -> {
            try {
                var product = Platform.getProduct();
                if (product == null || !"com._1c.g5.v8.dt.product.application.rcp".equals(product.getId())) {
                    throw new IllegalStateException("Expected the real EDT product");
                }
                var result = Boolean.getBoolean("codex.edt.live")
                    ? JUnitCore.runClasses(CodexLiveTest.class)
                    : JUnitCore.runClasses(CodexViewTest.class, AppServerClientTest.class, StreamingViewTest.class, SessionFeaturesTest.class, EditorBufferTest.class, MarkdownTest.class, IdeViewTest.class);
                result.getFailures().forEach(failure -> System.err.println(failure.getTrace()));
                String report = (result.wasSuccessful() ? "PASS" : "FAIL")
                    + " product=" + product.getId() + " tests=" + result.getRunCount()
                    + " failures=" + result.getFailureCount();
                Files.writeString(Path.of(resultPath), report);
                System.out.println("CODEX_EDT_SMOKE " + report);
            } catch (Exception failure) {
                failure.printStackTrace();
            } finally {
                workbench.close();
            }
        });
    }
}
