package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.util.List;
import java.util.function.BiConsumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.*;

final class ComposerComponent {
    final Text prompt;
    final IconButton send;
    private final IconButton add;
    final AttachmentChipBar attachments;
    private final Combo models;
    private final Combo efforts;
    private final Label context;
    private List<Model> catalog = List.of();
    private List<Reasoning> levels = List.of();
    private boolean ready;
    private boolean busy;
    private boolean running;

    ComposerComponent(Composite parent, ThemePalette palette, Runnable submit, Runnable stop,
            BiConsumer<String, String> selection, Runnable attach) {
        var frame = new Composite(parent, SWT.DOUBLE_BUFFERED);
        frame.setData("codex.role", "composer");
        var frameLayout = new GridLayout(1, false);
        frameLayout.marginWidth = 12;
        frameLayout.marginHeight = 12;
        frameLayout.verticalSpacing = 8;
        frame.setLayout(frameLayout);
        var frameData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        frameData.widthHint = 0;
        frame.setLayoutData(frameData);
        palette.apply(frame, "input", "text");
        frame.addPaintListener(event -> {
            var area = frame.getClientArea();
            var gc = event.gc;
            gc.setAntialias(SWT.ON);
            gc.setBackground(palette.color("panel"));
            gc.fillRectangle(area);
            gc.setBackground(palette.color("input"));
            gc.fillRoundRectangle(0, 0, area.width - 1, area.height - 1, 20, 20);
            gc.setForeground(palette.color(Boolean.TRUE.equals(frame.getData("focused")) ? "text" : "border"));
            gc.drawRoundRectangle(0, 0, area.width - 1, area.height - 1, 20, 20);
        });
        var input = new Composite(frame, SWT.NONE);
        input.setLayout(new FormLayout());
        palette.apply(input, "input", "text");
        var layout = new GridData(SWT.FILL, SWT.FILL, true, false);
        layout.heightHint = 78;
        layout.widthHint = 0;
        input.setLayoutData(layout);
        prompt = new Text(input, SWT.MULTI | SWT.WRAP);
        prompt.addListener(SWT.MouseVerticalWheel, event -> {
            prompt.setTopIndex(Math.max(0, prompt.getTopIndex() - event.count * 3));
            event.doit = false;
        });
        prompt.setMessage(tr("text006"));
        prompt.setTextLimit(32768);
        prompt.setData("codex.role", "prompt");
        palette.apply(prompt, "input", "text");
        var inputData = new FormData();
        inputData.left = new FormAttachment(0);
        inputData.right = new FormAttachment(100);
        inputData.top = new FormAttachment(0);
        inputData.bottom = new FormAttachment(100);
        prompt.setLayoutData(inputData);
        var placeholder = new Label(input, SWT.NONE);
        placeholder.setText(tr("composerPlaceholder"));
        placeholder.setData("codex.role", "placeholder");
        palette.apply(placeholder, "input", "muted");
        var hintData = new FormData();
        hintData.left = new FormAttachment(0, 1);
        hintData.top = new FormAttachment(0, 2);
        placeholder.setLayoutData(hintData);
        placeholder.moveAbove(prompt);
        prompt.addModifyListener(event -> {
            if (!placeholder.isDisposed()) {
                placeholder.setVisible(prompt.getText().isEmpty() && !prompt.isFocusControl());
            }
        });
        prompt.addListener(SWT.FocusIn, event -> {
            if (!placeholder.isDisposed()) {
                placeholder.setVisible(false);
            }
            frame.setData("focused", true);
            frame.redraw();
        });
        prompt.addListener(SWT.FocusOut, event -> {
            if (!placeholder.isDisposed()) {
                placeholder.setVisible(prompt.getText().isEmpty());
            }
            frame.setData("focused", false);
            frame.redraw();
        });
        placeholder.addListener(SWT.MouseDown, event -> prompt.setFocus());
        prompt.addListener(SWT.KeyDown, event -> {
            if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) && EdtPreferences.enterSends()
                    && (event.stateMask & (SWT.SHIFT | SWT.CTRL | SWT.ALT | SWT.COMMAND)) == 0) {
                event.doit = false;
                if (ready && !busy && !prompt.getText().isBlank()) {
                    submit.run();
                }
            }
        });
        attachments = new AttachmentChipBar(frame, palette);
        context = new Label(frame, SWT.WRAP);
        context.setData("codex.role", "contextChip");
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        palette.apply(context, "input", "muted");
        context("");
        var footer = new Composite(frame, SWT.NONE);
        var fl = new GridLayout(1, false);
        fl.marginWidth = 4;
        fl.marginHeight = 4;
        fl.verticalSpacing = 6;
        footer.setLayout(fl);
        footer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        palette.apply(footer, "footer", "text");
        var badges = new Composite(footer, SWT.NONE);
        var bl = new RowLayout(SWT.HORIZONTAL);
        bl.center = true;
        bl.spacing = 6;
        bl.marginLeft = 0;
        bl.marginTop = 0;
        bl.marginBottom = 0;
        badges.setLayout(bl);
        badges.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        palette.apply(badges, "footer", "text");
        add = new IconButton(badges, palette, "plus", tr("attach"), false);
        add.setData("codex.role", "attach");
        add.addListener(SWT.Selection, event -> attach.run());
        var mode = new Label(badges, SWT.NONE);
        mode.setText(tr("readOnly"));
        mode.setToolTipText(tr("approvalHint"));
        palette.apply(mode, "footer", "muted");
        new ApprovalStatusComponent(badges, palette);
        var options = new Composite(footer, SWT.NONE);
        var ol = new GridLayout(3, false);
        ol.marginWidth = 0;
        ol.marginHeight = 0;
        ol.horizontalSpacing = 6;
        options.setLayout(ol);
        options.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        palette.apply(options, "footer", "text");
        models = new Combo(options, SWT.READ_ONLY);
        models.setData("codex.role", "model");
        models.setToolTipText(tr("text008"));
        palette.apply(models, "footer", "text");
        var modelData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        modelData.widthHint = 90;
        models.setLayoutData(modelData);
        efforts = new Combo(options, SWT.READ_ONLY);
        efforts.setData("codex.role", "reasoning");
        efforts.setToolTipText(tr("text009"));
        palette.apply(efforts, "footer", "text");
        var effortData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        effortData.widthHint = 65;
        efforts.setLayoutData(effortData);
        send = new IconButton(options, palette, "send", tr("text010"), true);
        send.setData("codex.role", "send");
        send.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        send.addListener(SWT.Selection, event -> {
            if (running) {
                stop.run();
            } else {
                submit.run();
            }
        });
        prompt.addModifyListener(event -> update());
        models.addListener(SWT.Selection, event -> {
            if (models.getSelectionIndex() < 0) {
                return;
            }
            Model model = catalog.get(models.getSelectionIndex());
            String previous = efforts.getSelectionIndex() < 0 ? "" : levels.get(efforts.getSelectionIndex()).value();
            selection.accept(model.id(), model.compatibleEffort(previous));
        });
        efforts.addListener(SWT.Selection, event -> {
            if (models.getSelectionIndex() >= 0 && efforts.getSelectionIndex() >= 0) {
                selection.accept(catalog.get(models.getSelectionIndex()).id(),
                        levels.get(efforts.getSelectionIndex()).value());
            }
        });
        frame.setTabList(new Control[] { input, footer });
        input.setTabList(new Control[] { prompt });
        footer.setTabList(new Control[] { badges, options });
        options.setTabList(new Control[] { models, efforts, send });
        update();
    }

    void catalog(Snapshot snapshot) {
        catalog = snapshot.models();
        models.setItems(catalog.stream().map(Model::displayName).toArray(String[]::new));
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).id().equals(snapshot.model())) {
                models.select(i);
                models.setToolTipText(tr("text008") + ": " + catalog.get(i).displayName());
                levels = catalog.get(i).efforts();
                efforts.setItems(levels.stream().map(level -> label(level.value())).toArray(String[]::new));
                for (int j = 0; j < levels.size(); j++) {
                    if (levels.get(j).value().equals(snapshot.effort())) {
                        efforts.select(j);
                        efforts.setToolTipText(tr("text009") + ": " + label(levels.get(j).value()));
                    }
                }
                break;
            }
        }
    }

    static String label(String effort) {
        return switch (effort) {
        case "none" -> tr("text012");
        case "minimal" -> tr("text013");
        case "low" -> tr("text014");
        case "medium" -> tr("text015");
        case "high" -> tr("text016");
        case "xhigh" -> tr("text017");
        case "max" -> tr("text018");
        case "ultra" -> tr("text019");
        default -> effort;
        };
    }

    void context(String value) {
        context.setText(value);
        context.setVisible(!value.isBlank());
        ((GridData) context.getLayoutData()).exclude = value.isBlank();
        context.getParent().layout(true);
    }

    void state(boolean ready, boolean busy, boolean running) {
        this.ready = ready;
        this.busy = busy;
        this.running = running;
        update();
    }

    private void update() {
        send.icon(running ? "stop" : "send");
        send.setData("codex.running", running);
        send.setToolTipText(running ? tr("text011") : tr("text010"));
        var state = new io.github.zhumaniezov.codex.edt.ui.presentation.ComposerState(ready, busy, running,
                !prompt.getText().isBlank());
        send.setEnabled(state.canSend() || state.canStop());
        add.setEnabled(!busy && !running);
        attachments.enabled(!busy && !running);
        models.setEnabled(ready && !busy && !catalog.isEmpty());
        efforts.setEnabled(ready && !busy && !levels.isEmpty());
    }
}
