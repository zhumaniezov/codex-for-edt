package io.github.zhumaniezov.codex.edt.ui;
import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.dialogs.PreferencesUtil;
import io.github.zhumaniezov.codex.edt.client.SessionData.Account;
final class AccountComponent {
 private final Button button;
 AccountComponent(Composite parent) {
  button = new Button(parent, SWT.PUSH); button.setData("codex.role", "account"); IconResources.button(button,"account",tr("account"));
  button.addListener(SWT.Selection, event -> {
   var dialog = PreferencesUtil.createPreferenceDialogOn(parent.getShell(), "io.github.zhumaniezov.codex.edt.preferences.account", null, null);
   if (dialog != null) { dialog.open(); }
  });
 }
 void account(Account account) { button.setToolTipText(tr("account") + " · " + account.label()); }
 void enabled(boolean enabled) { button.setEnabled(enabled); }
}
