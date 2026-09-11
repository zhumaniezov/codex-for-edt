package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.*;

final class ThreadListComponent {
    private final Table table;
    private final Button more;
    private String cursor = "";
    ThreadListComponent(Composite parent, Consumer<String> open, Consumer<String> load) {
        var section = new Composite(parent, SWT.NONE); section.setLayout(new GridLayout(1, false));
        section.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        new Label(section, SWT.NONE).setText(tr("text020"));
        table = new Table(section, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setData("codex.role", "threads");
        var data = new GridData(SWT.FILL, SWT.FILL, true, false); data.heightHint = 90; data.widthHint = 0; table.setLayoutData(data);
        new TableColumn(table, SWT.LEFT); new TableColumn(table, SWT.RIGHT);
        table.addListener(SWT.Resize, event -> {
            table.getColumn(0).setWidth(Math.max(60, table.getClientArea().width - 90)); table.getColumn(1).setWidth(90);
        });
        table.addListener(SWT.Selection, event -> {
            if (table.getSelectionCount() > 0) { open.accept(((ThreadSummary) table.getSelection()[0].getData()).id()); }
        });
        more = new Button(section, SWT.PUSH); more.setText(tr("text021")); more.setData("codex.role", "moreThreads");
        more.addListener(SWT.Selection, event -> load.accept(cursor)); more.setEnabled(false);
        more.setLayoutData(new GridData());
    }
    void page(ThreadPage page, boolean append) {
        if (!append) { table.removeAll(); }
        for (var thread : page.threads()) {
            boolean exists = java.util.Arrays.stream(table.getItems()).anyMatch(item -> ((ThreadSummary) item.getData()).id().equals(thread.id()));
            if (exists) { continue; }
            var item = new TableItem(table, SWT.NONE); item.setData(thread);
            item.setText(new String[] { thread.title(), DateTimeFormatter.ofPattern("dd.MM")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(thread.updatedAt())) });
        }
        cursor = page.cursor(); more.setEnabled(!cursor.isBlank()); more.setVisible(!cursor.isBlank());
        ((GridData) more.getLayoutData()).exclude = cursor.isBlank(); more.getParent().layout(true);
    }
    void enabled(boolean value) { table.setEnabled(value); more.setEnabled(value && !cursor.isBlank()); }
    void current(String id) {
        for (var item : table.getItems()) { if (((ThreadSummary) item.getData()).id().equals(id)) { table.setSelection(item); return; } }
        table.deselectAll();
    }
}
