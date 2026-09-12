package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.*;

final class StatusComponent {
    private final Label status;
    private final Label diagnostic;
    StatusComponent(Composite parent, Composite statusParent, ThemePalette palette) {
        status = new Label(statusParent, SWT.NONE);palette.apply(status,"panel","muted"); status.setData("codex.role", "status");
        status.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false)); status.setText(io.github.zhumaniezov.codex.edt.Messages.CONNECTING());
        diagnostic = new Label(parent, SWT.WRAP);palette.apply(diagnostic,"panel","muted"); diagnostic.setData("codex.role", "diagnostic");
        diagnostic.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); showDiagnostic();
    }
    void text(String text) { status.setText(text); status.getParent().layout(true); }
    void details(Snapshot value, String project) {
        diagnostic.setText("Codex " + value.version() + tr("text101") + value.model() + " · " + value.effort()
            + tr("text102") + project + "\n" + value.cwd() + "\nThread: " + value.threadId()
            + "\n" + io.github.zhumaniezov.codex.edt.semantic.MetadataPresentation.diagnostic(value.edtTools()));
        status.setToolTipText(diagnostic.getText()); showDiagnostic();
    }
    void showDiagnostic() {
        boolean visible = EdtPreferences.diagnostics(); diagnostic.setVisible(visible);
        ((GridData) diagnostic.getLayoutData()).exclude = !visible; diagnostic.getParent().layout(true);
    }
}
