package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.ui.presentation.ApprovalPresenter;

final class ApprovalStatusComponent {
    ApprovalStatusComponent(Composite parent, ThemePalette palette) {
        var chip = new Button(parent, SWT.FLAT);
        chip.setText(tr("approvalChip"));
        chip.setToolTipText(tr("approvalHint"));
        chip.setData("codex.role", "approvalPolicy");
        palette.apply(chip, "footer", "muted");
        var menu = new Menu(chip);
        var active = new MenuItem(menu, SWT.RADIO);
        active.setText(tr("approvalReadOnly"));
        active.setSelection(true);
        var automatic = new MenuItem(menu, SWT.PUSH);
        automatic.setText(tr("approvalAutoUnavailable"));
        automatic.setEnabled(ApprovalPresenter.policy().canAutoApprove());
        chip.addListener(SWT.Selection, event -> menu.setVisible(true));
        chip.addDisposeListener(event -> menu.dispose());
    }
}
