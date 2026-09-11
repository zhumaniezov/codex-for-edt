package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Label;
import java.time.Instant;
import io.github.zhumaniezov.codex.edt.settings.AccountService;

public final class AccountPreferences extends SettingsPage {
    private AccountService service;
    private Label account, usage;
    private String loginId = "";
    @Override protected void createBody() {
        service = new AccountService(access.client); label(body, tr("accountRefreshHint"));
        account = label(body, ""); usage = label(body, "");
        var actions = row(); button(actions, tr("refreshAuth"), this::refresh);
        button(actions, tr("login"), () -> run(service::login, login -> {
            loginId = login.id(); FileLinkService.openHttp(login.url()); status.setText(tr("loginWaiting"));
        }));
        button(actions, tr("cancelLogin"), () -> { if (!loginId.isBlank()) { run(() -> service.cancel(loginId), value -> { loginId = ""; refresh(); }); } });
        button(actions, tr("logout"), () -> {
            if (MessageDialog.openQuestion(body.getShell(), tr("sharedConfirm"), tr("text048"))) {
                run(() -> service.logout().thenCompose(value -> access.client.connect().handle((connection, error) -> null)), value -> refresh());
            }
        });
    }
    @Override protected void refresh() {
        run(service::read, value -> {
            account.setText(value.label() + (value.plan().isBlank() ? "" : "\n" + tr("plan") + ": " + value.plan()));
            usage.setText("");
            if (!value.type().isBlank()) { run(service::limits, limits -> {
                var text = new StringBuilder();
                for (var limit : limits) {
                    text.append(limit.name()).append(" · ").append(tr("usage")).append(": ").append(limit.usedPercent()).append("%");
                    if (limit.minutes() != null) { text.append(" / ").append(limit.minutes()).append(' ').append(tr("minutes")); }
                    if (limit.resetsAt() != null) { text.append(" · ").append(tr("reset")).append(": ").append(Instant.ofEpochSecond(limit.resetsAt()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()); }
                    text.append('\n');
                }
                usage.setText(text.isEmpty() ? tr("noData") : text.toString());
            }); }
        });
    }
}
