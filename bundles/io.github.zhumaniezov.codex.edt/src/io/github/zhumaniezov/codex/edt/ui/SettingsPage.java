package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.util.concurrent.CompletionStage;
import java.util.function.*;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import io.github.zhumaniezov.codex.edt.CodexPlugin;
import io.github.zhumaniezov.codex.edt.protocol.CodexProtocol;

abstract class SettingsPage extends PreferencePage implements IWorkbenchPreferencePage {
    protected Composite body;
    protected Label status;
    protected SettingsAccess access;
    private org.eclipse.swt.widgets.Display display;
    private boolean busy;
    SettingsPage() { noDefaultAndApplyButton(); }
    @Override public void init(IWorkbench workbench) { }
    @Override protected Control createContents(Composite parent) {
        display = parent.getDisplay(); body = new Composite(parent, SWT.NONE); body.setLayout(new GridLayout(1, false));
        access = SettingsAccess.get(parent.getShell());
        status = label(body, tr("loading")); createBody();
        display.asyncExec(() -> { if (!body.isDisposed()) { refresh(); } }); return body;
    }
    protected abstract void createBody();
    protected abstract void refresh();
    protected Label label(Composite parent, String text) {
        var label = new Label(parent, SWT.WRAP); label.setText(text);
        var data = new GridData(SWT.FILL, SWT.CENTER, true, false); data.widthHint = 420; label.setLayoutData(data); return label;
    }
    protected Composite row() {
        var row = new Composite(body, SWT.NONE); row.setLayout(new RowLayout(SWT.HORIZONTAL));
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false)); return row;
    }
    protected Button button(Composite parent, String text, Runnable action) {
        var button = new Button(parent, SWT.PUSH); button.setText(text);
        button.addListener(SWT.Selection, event -> { if (!busy) { action.run(); } }); return button;
    }
    protected <T> void run(Supplier<CompletionStage<T>> operation, Consumer<T> success) {
        if (body.isDisposed()) { return; }
        busy = true; status.setText(tr("loading"));
        access.ready.thenCompose(ignored -> operation.get()).whenComplete((value, error) -> {
            if (display.isDisposed()) { return; }
            try { display.asyncExec(() -> {
                if (body.isDisposed()) { return; }
                busy = false;
                if (error == null) { status.setText(""); success.accept(value); }
                else {
                    Throwable failure = error;
                    while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) { failure = failure.getCause(); }
                    CodexPlugin.log("Settings", failure);
                    status.setText(tr("error") + ": " + CodexProtocol.redact(String.valueOf(failure.getMessage())));
                }
                body.layout(true, true);
            }); } catch (org.eclipse.swt.SWTException failure) { if (!display.isDisposed()) { throw failure; } }
        });
    }
    protected Table table(String... headings) {
        var table = new Table(body, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true); table.setLinesVisible(false);
        var data = new GridData(SWT.FILL, SWT.FILL, true, true); data.heightHint = 220; data.widthHint = 420; table.setLayoutData(data);
        for (String heading : headings) { var column = new TableColumn(table, SWT.LEFT); column.setText(heading); column.setWidth(130); }
        return table;
    }
}
