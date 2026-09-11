package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.ui.presentation.AttachmentSelection;

final class AttachmentChipBar {
    private final Composite root;
    private final ThemePalette palette;
    final AttachmentSelection selection = new AttachmentSelection();

    AttachmentChipBar(Composite parent, ThemePalette palette) {
        this.palette = palette;
        root = new Composite(parent, SWT.NONE);
        var layout = new RowLayout();
        layout.wrap = true;
        layout.spacing = 5;
        layout.marginLeft = 0;
        layout.marginTop = 0;
        layout.marginBottom = 0;
        root.setLayout(layout);
        var data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = 0;
        root.setLayoutData(data);
        root.setData("codex.role", "attachments");
        palette.apply(root, "input", "text");
        refresh();
    }

    void refresh() {
        for (var child : root.getChildren()) {
            child.dispose();
        }
        for (var item : selection.items()) {
            var chip = new Composite(root, SWT.NONE);
            var layout = new GridLayout(2, false);
            layout.marginWidth = 6;
            layout.marginHeight = 1;
            layout.horizontalSpacing = 3;
            chip.setLayout(layout);
            palette.apply(chip, "footer", "text");
            var label = new Label(chip, SWT.NONE);
            String name = item.file().getFileName().toString();
            label.setText(name.length() > 26 ? name.substring(0, 23) + "…" : name);
            label.setToolTipText(item.relative() + "\n" + tr("attachmentSaved"));
            palette.apply(label, "footer", "text");
            var remove = new IconButton(chip, palette, "close", tr("attachmentRemove"), false);
            remove.setLayoutData(new GridData(22, 22));
            remove.addListener(SWT.Selection, e -> {
                selection.remove(item);
                refresh();
            });
        }
        boolean visible = !selection.items().isEmpty();
        root.setVisible(visible);
        ((GridData) root.getLayoutData()).exclude = !visible;
        root.getParent().layout(true, true);
    }

    void clear() {
        selection.clear();
        refresh();
    }

    void enabled(boolean enabled) {
        root.setEnabled(enabled);
    }
}
