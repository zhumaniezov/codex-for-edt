package io.github.zhumaniezov.codex.edt.ui;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.Account;

final class AccountComponent {
    private final Button button;
    private Account account = Account.NONE;
    AccountComponent(Composite parent, Runnable logout) {
        button = new Button(parent, SWT.PUSH); button.setText("Аккаунт");
        button.addListener(SWT.Selection, event -> {
            if (account.type().isBlank()) {
                MessageDialog.openInformation(button.getShell(), "Вход в Codex",
                    "Выполните codex login в терминале. Вход выполняется самим Codex в браузере. Затем нажмите «Переподключить»."); return;
            }
            var menu = new Menu(button);
            var info = new MenuItem(menu, SWT.PUSH); info.setText(account.label() + (account.plan().isBlank() ? "" : " · " + account.plan())); info.setEnabled(false);
            var exit = new MenuItem(menu, SWT.PUSH); exit.setText("Выйти из Codex");
            exit.addListener(SWT.Selection, selected -> {
                if (MessageDialog.openConfirm(button.getShell(), "Выход из Codex",
                    "Авторизация общая с Codex CLI и другими клиентами. Выйти из этой учётной записи Codex?")) { logout.run(); }
            });
            menu.addListener(SWT.Hide, hidden -> button.getDisplay().asyncExec(() -> { if (!menu.isDisposed()) { menu.dispose(); } }));
            menu.setLocation(button.toDisplay(0, button.getSize().y)); menu.setVisible(true);
        });
    }
    void account(Account account) { this.account = account; button.setToolTipText(account.label()); }
    void enabled(boolean value) { button.setEnabled(value); }
}
