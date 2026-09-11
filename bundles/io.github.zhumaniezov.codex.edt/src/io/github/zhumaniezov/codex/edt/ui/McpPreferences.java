package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import com.google.gson.*;
import io.github.zhumaniezov.codex.edt.settings.*;

public final class McpPreferences extends SettingsPage {
    private Table table;
    private McpService service;
    private CodexSettingsService settings;
    private CodexSettingsService.Configuration config;
    @Override protected void createBody() {
        service = new McpService(access.client); settings = new CodexSettingsService(access.client);
        label(body, tr("mcpHint")); table = table(tr("name"), tr("enabled"), tr("status"), tr("type"), tr("auth"), tr("tools"));
        var actions = row(); button(actions, tr("refresh"), this::refresh);
        button(actions, tr("add"), () -> edit(true)); button(actions, tr("edit"), () -> edit(false));
        button(actions, tr("enable"), () -> {
            var server = selected(); if (server == null || server.enabled() == null) { return; }
            var value = configuration(server.name()); if (value == null) { MessageDialog.openInformation(body.getShell(), tr("readOnly"), tr("mcpUserOnly")); return; }
            value.addProperty("enabled", !server.enabled()); write(server.name(), value);
        });
        button(actions, tr("remove"), () -> {
            var server = selected(); if (server == null || configuration(server.name()) == null) { return; }
            if (MessageDialog.openQuestion(body.getShell(), tr("sharedConfirm"), tr("confirmDelete"))) {
                run(() -> settings.server(config, server.name(), JsonNull.INSTANCE), value -> refresh());
            }
        });
        button(actions, tr("reload"), () -> run(service::reload, value -> refresh()));
        button(actions, tr("authMcp"), () -> { var selected = selected(); if (selected != null) { run(() -> service.login(selected.name()), FileLinkService::openHttp); } });
    }
    private McpService.Server selected() { return table.getSelectionCount() == 0 ? null : (McpService.Server) table.getSelection()[0].getData(); }
    private JsonObject configuration(String name) {
        if (config == null || !config.userValues().has("mcp_servers")) { return null; }
        var servers = config.userValues().getAsJsonObject("mcp_servers");
        return servers.has(name) && servers.get(name).isJsonObject() ? servers.getAsJsonObject(name).deepCopy() : null;
    }
    private void edit(boolean add) {
        if (config == null) { return; }
        var selected = selected(); if (!add && selected == null) { return; }
        var value = add ? new JsonObject() : configuration(selected.name()); if (value == null) { MessageDialog.openInformation(body.getShell(), tr("readOnly"), tr("mcpUserOnly")); return; }
        var dialog = new McpEditDialog(body.getShell(), add ? "" : selected.name(), value);
        if (dialog.open() == Window.OK) {
            if (add && config.values().has("mcp_servers") && config.values().getAsJsonObject("mcp_servers").has(dialog.serverName)) {
                MessageDialog.openInformation(body.getShell(), tr("error"), tr("mcpDuplicate")); return;
            }
            write(dialog.serverName, dialog.value);
        }
    }
    private void write(String name, JsonElement value) {
        if (MessageDialog.openQuestion(body.getShell(), tr("sharedConfirm"), tr("sharedWarning"))) {
            run(() -> settings.server(config, name, value), ignored -> refresh());
        }
    }
    @Override protected void refresh() {
        run(() -> settings.read().thenCompose(value -> { config = value; return service.list(value); }), servers -> {
            table.removeAll(); for (var server : servers) {
                var item = new TableItem(table, SWT.NONE); item.setData(server);
                item.setText(new String[] {server.name(), server.enabled() == null ? tr("unknown") : tr(server.enabled() ? "enabled" : "disabled"),
                    server.status().isBlank() ? tr("mcpConfigured") : LocalizationService.value(server.status().equals("disabled") ? "disabledState" : server.status()),
                    server.transport(), server.auth().isBlank() ? tr("unknown") : LocalizationService.value(server.auth()), server.tools() == null ? tr("unknown") : server.tools().toString()});
            }
        });
    }
}
