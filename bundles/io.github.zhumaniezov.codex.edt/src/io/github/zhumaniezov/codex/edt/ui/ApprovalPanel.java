package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.ui.presentation.ApprovalPresenter;

/**
 * Компонент будущего запроса; текущая политика не предоставляет ни одного
 * решения backend.
 */
final class ApprovalPanel {
    private final Composite root;
    private final Label description;

    ApprovalPanel(Composite parent, ThemePalette palette) {
        root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        palette.apply(root, "footer", "text");
        description = new Label(root, SWT.WRAP);
        description.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        palette.apply(description, "footer", "text");
        var buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new RowLayout());
        palette.apply(buttons, "footer", "text");
        for (String key : new String[] { "approvalAllow", "approvalAuto", "approvalReject" }) {
            var button = new Button(buttons, SWT.PUSH);
            button.setText(tr(key));
            button.setEnabled(false);
        }
        show(ApprovalPresenter.policy());
    }

    void show(ApprovalPresenter model) {
        description.setText(model.description() + "\n" + tr("approvalHint"));
        root.setVisible(model.hasRequest());
        ((GridData) root.getLayoutData()).exclude = !model.hasRequest();
        root.getParent().layout(true);
    }
}
