package io.github.zhumaniezov.codex.edt.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.*;

final class StatusComponent {
    private final Label status;
    private final Label diagnostic;
    StatusComponent(Composite parent) {
        status = new Label(parent, SWT.WRAP); status.setData("codex.role", "status");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); status.setText("Подключение...");
        diagnostic = new Label(parent, SWT.WRAP); diagnostic.setData("codex.role", "diagnostic");
        diagnostic.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); showDiagnostic();
    }
    void text(String text) { status.setText(text); status.getParent().layout(true); }
    void details(Snapshot value, String project) {
        diagnostic.setText("Codex " + value.version() + "\nМодель: " + value.model() + " · " + value.effort()
            + "\nПроект: " + project + "\n" + value.cwd() + "\nThread: " + value.threadId());
        status.setToolTipText(diagnostic.getText()); showDiagnostic();
    }
    void showDiagnostic() {
        boolean visible = EdtPreferences.diagnostics(); diagnostic.setVisible(visible);
        ((GridData) diagnostic.getLayoutData()).exclude = !visible; diagnostic.getParent().layout(true);
    }
}
