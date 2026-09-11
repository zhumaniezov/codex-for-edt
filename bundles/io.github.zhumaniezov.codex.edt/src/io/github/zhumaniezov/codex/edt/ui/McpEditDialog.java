package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import com.google.gson.*;

final class McpEditDialog extends TitleAreaDialog {
    private final String originalName;
    private final JsonObject original;
    private Text name, endpoint, args;
    private Combo type;
    String serverName;
    JsonObject value;
    McpEditDialog(Shell shell, String name, JsonObject config) { super(shell); originalName = name; original = config.deepCopy(); }
    @Override protected Control createDialogArea(Composite parent) {
        var area = (Composite) super.createDialogArea(parent); setTitle(tr("mcp")); setMessage(tr("mcpEditHint"));
        var form = new Composite(area, SWT.NONE); form.setLayout(new GridLayout(2, false)); form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        name = field(form, tr("name")); name.setText(originalName); name.setEnabled(originalName.isBlank());
        new Label(form, SWT.NONE).setText(tr("type")); type = new Combo(form, SWT.READ_ONLY); type.setItems("STDIO", "HTTP"); type.select(original.has("url") ? 1 : 0);
        endpoint = field(form, tr("command")); endpoint.setText(string(original, original.has("url") ? "url" : "command"));
        args = field(form, tr("args")); args.setText(original.has("args") ? original.get("args").toString() : "[]");
        return area;
    }
    private Text field(Composite parent, String label) {
        new Label(parent, SWT.NONE).setText(label); var text = new Text(parent, SWT.BORDER);
        var data = new GridData(SWT.FILL, SWT.CENTER, true, false); data.widthHint = 300; text.setLayoutData(data); return text;
    }
    @Override protected void okPressed() {
        try {
            serverName = name.getText().strip(); String address = endpoint.getText().strip();
            if (!serverName.matches("[A-Za-z0-9_-]+") || address.isBlank()) { throw new IllegalArgumentException(); }
            value = original.deepCopy();
            if (type.getSelectionIndex() == 1) {
                if (!io.github.zhumaniezov.codex.edt.ui.render.MarkdownDocument.safeLink(address)) { throw new IllegalArgumentException(); }
                value.remove("command"); value.remove("args"); value.addProperty("url", address);
            } else {
                var arguments = JsonParser.parseString(args.getText()).getAsJsonArray();
                for (var argument : arguments) { if (!argument.isJsonPrimitive() || !argument.getAsJsonPrimitive().isString()) { throw new IllegalArgumentException(); } }
                value.remove("url"); value.addProperty("command", address); value.add("args", arguments);
            }
            if (!value.has("enabled")) { value.addProperty("enabled", true); }
            super.okPressed();
        } catch (RuntimeException error) { setErrorMessage(tr("invalidMcp")); }
    }
}
