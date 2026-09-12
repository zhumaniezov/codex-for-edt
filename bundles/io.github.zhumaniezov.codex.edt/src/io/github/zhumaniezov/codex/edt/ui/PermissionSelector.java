package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.jface.dialogs.MessageDialog;
import io.github.zhumaniezov.codex.edt.client.*;

final class PermissionSelector {
    final Button button;
    private PermissionMode mode = PermissionMode.READ_ONLY;
    private PermissionOptions options = PermissionOptions.CLOSED;
    Consumer<PermissionMode> selection = value -> {
    };

    PermissionSelector(Composite parent, ThemePalette palette) {
        button = new Button(parent, SWT.PUSH);
        button.setData("codex.role", "permissions");
        button.setText(mode.label());
        button.setToolTipText(mode.description());
        palette.apply(button, "footer", "text");
        button.addListener(SWT.Selection, e -> {
            var menu = new Menu(button);
            for (var value : PermissionMode.values()) {
                var item = new MenuItem(menu, SWT.RADIO);
                item.setText(value.label());
                item.setSelection(value == mode);
                item.setEnabled(options.allowed().contains(value));
                item.setToolTipText(options.allowed().contains(value) ? value.description() : tr("modeForbidden"));
                item.addListener(SWT.Selection, event -> {
                    if (!item.getSelection()) {
                        return;
                    }
                    if (value == PermissionMode.FULL
                            && !MessageDialog.openQuestion(button.getShell(), value.label(), tr("fullWarning"))) {
                        return;
                    }
                    selection.accept(value);
                });
            }
            menu.addListener(SWT.Hide, event -> button.getDisplay().asyncExec(() -> {
                if (!menu.isDisposed()) {
                    menu.dispose();
                }
            }));
            menu.setLocation(button.toDisplay(0, button.getSize().y));
            menu.setVisible(true);
        });
    }

    void update(PermissionMode mode, PermissionOptions options) {
        this.mode = mode;
        this.options = options;
        button.setText(mode.label());
        button.setToolTipText(mode.description());
        button.getParent().layout(true);
    }
}
