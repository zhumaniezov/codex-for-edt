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
    private TableItem hover;

    ThreadListComponent(Composite parent, ThemePalette palette, Consumer<String> open, Consumer<String> load) {
        var section = new Composite(parent, SWT.NONE);
        section.setLayout(new GridLayout(1, false));
        section.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        palette.apply(section, "panel", "text");
        var heading = new Label(section, SWT.NONE);
        heading.setText(tr("text020"));
        palette.apply(heading, "panel", "muted");
        table = new Table(section, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setData("codex.role", "threads");
        palette.apply(table, "panel", "text");
        table.addListener(SWT.MeasureItem, event -> {
            event.height = 28;
        });
        table.addListener(SWT.EraseItem, event -> {
            event.detail &= ~(SWT.FOREGROUND | SWT.BACKGROUND | SWT.SELECTED | SWT.HOT | SWT.FOCUSED);
        });
        table.addListener(SWT.PaintItem, event -> {
            var item = (TableItem) event.item;
            boolean selected = java.util.Arrays.asList(table.getSelection()).contains(item);
            var bounds = item.getBounds(event.index);
            var gc = event.gc;
            gc.setBackground(palette.color(selected ? "selected" : item == hover ? "hover" : "panel"));
            gc.fillRectangle(bounds);
            gc.setForeground(palette.color(event.index == 0 ? "text" : "muted"));
            String title = item.getText(event.index);
            int width = Math.max(0, bounds.width - 16);
            while (title.length() > 1 && gc.textExtent(title).x > width) {
                title = title.substring(0, title.length() - 2) + "…";
            }
            int x = event.index == 0 ? bounds.x + 8 : bounds.x + Math.max(4, bounds.width - gc.textExtent(title).x - 8);
            gc.drawText(title, x, bounds.y + (bounds.height - gc.textExtent(title).y) / 2, true);
            if (selected && event.index == 0) {
                gc.setBackground(palette.color("text"));
                gc.fillRectangle(bounds.x, bounds.y + 5, 2, Math.max(0, bounds.height - 10));
            }
        });
        table.addListener(SWT.MouseMove, event -> {
            var item = table.getItem(new org.eclipse.swt.graphics.Point(event.x, event.y));
            if (item != hover) {
                hover = item;
                table.setToolTipText(item == null ? null : item.getText(0));
                table.redraw();
            }
        });
        table.addListener(SWT.MouseExit, event -> {
            hover = null;
            table.redraw();
        });
        var data = new GridData(SWT.FILL, SWT.FILL, true, false);
        data.heightHint = 28;
        data.widthHint = 0;
        table.setLayoutData(data);
        new TableColumn(table, SWT.LEFT);
        new TableColumn(table, SWT.RIGHT);
        table.addListener(SWT.Resize, event -> {
            table.getColumn(0).setWidth(Math.max(30, table.getClientArea().width - 62));
            table.getColumn(1).setWidth(62);
        });
        table.addListener(SWT.Selection, event -> {
            if (table.getSelectionCount() > 0) {
                open.accept(((ThreadSummary) table.getSelection()[0].getData()).id());
            }
        });
        more = new Button(section, SWT.FLAT);
        more.setText(tr("text021"));
        more.setData("codex.role", "moreThreads");
        more.addListener(SWT.Selection, event -> load.accept(cursor));
        more.setEnabled(false);
        more.setVisible(false);
        palette.apply(more, "panel", "muted");
        more.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        ((GridData) more.getLayoutData()).exclude = true;
    }

    void page(ThreadPage page, boolean append) {
        if (!append) {
            table.removeAll();
        }
        for (var thread : page.threads()) {
            boolean exists = java.util.Arrays.stream(table.getItems())
                    .anyMatch(item -> ((ThreadSummary) item.getData()).id().equals(thread.id()));
            if (exists) {
                continue;
            }
            var item = new TableItem(table, SWT.NONE);
            item.setData(thread);
            item.setText(
                    new String[] { thread.title(), io.github.zhumaniezov.codex.edt.ui.presentation.ChatListPresentation
                            .time(thread.updatedAt(), Instant.now().getEpochSecond()) });
        }
        ((GridData) table.getLayoutData()).heightHint = Math.max(1, Math.min(4, table.getItemCount())) * 28;
        cursor = page.cursor();
        more.setEnabled(!cursor.isBlank());
        more.setVisible(!cursor.isBlank());
        ((GridData) more.getLayoutData()).exclude = cursor.isBlank();
        more.getParent().getParent().layout(true, true);
    }

    void enabled(boolean value) {
        table.setEnabled(value);
        more.setEnabled(value && !cursor.isBlank());
    }

    void current(String id) {
        for (var item : table.getItems()) {
            if (((ThreadSummary) item.getData()).id().equals(id)) {
                table.setSelection(item);
                table.redraw();
                return;
            }
        }
        table.deselectAll();
        table.redraw();
    }
}
