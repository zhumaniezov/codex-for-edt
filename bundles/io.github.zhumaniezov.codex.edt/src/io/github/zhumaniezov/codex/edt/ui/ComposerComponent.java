package io.github.zhumaniezov.codex.edt.ui;

import java.util.List;
import java.util.function.BiConsumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.*;

final class ComposerComponent {
    final Text prompt;
    final Button send;
    private final Combo models;
    private final Combo efforts;
    private final Label context;
    private List<Model> catalog = List.of();
    private List<Reasoning> levels = List.of();
    private boolean ready;
    private boolean busy;
    private boolean running;
    ComposerComponent(Composite parent, Runnable submit, Runnable stop, BiConsumer<String, String> selection) {
        var frame = new Composite(parent, SWT.BORDER);
        frame.setLayout(new GridLayout(1, false)); frame.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        var input = new Composite(frame, SWT.NONE); input.setLayout(new FormLayout());
        var layout = new GridData(SWT.FILL, SWT.FILL, true, false); layout.heightHint = 72; layout.widthHint = 240; input.setLayoutData(layout);
        prompt = new Text(input, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        prompt.setMessage("Спросите Codex"); prompt.setTextLimit(32768); prompt.setData("codex.role", "prompt");
        var inputData = new FormData(); inputData.left = new FormAttachment(0); inputData.right = new FormAttachment(100);
        inputData.top = new FormAttachment(0); inputData.bottom = new FormAttachment(100); prompt.setLayoutData(inputData);
        // Многострочный Windows Text не рисует setMessage: подсказка остаётся native SWT.
        var placeholder = new Label(input, SWT.NONE); placeholder.setText("Спросите Codex"); placeholder.setData("codex.role", "placeholder");
        placeholder.setBackground(prompt.getBackground());
        placeholder.setForeground(prompt.getForeground());
        var hintData = new FormData(); hintData.left = new FormAttachment(0, 5); hintData.top = new FormAttachment(0, 4);
        placeholder.setLayoutData(hintData); placeholder.moveAbove(prompt);
        prompt.addModifyListener(event -> { if (!placeholder.isDisposed()) { placeholder.setVisible(prompt.getText().isEmpty() && !prompt.isFocusControl()); } });
        prompt.addListener(SWT.FocusIn, event -> { if (!placeholder.isDisposed()) { placeholder.setVisible(false); } });
        prompt.addListener(SWT.FocusOut, event -> { if (!placeholder.isDisposed()) { placeholder.setVisible(prompt.getText().isEmpty()); } });
        placeholder.addListener(SWT.MouseDown, event -> prompt.setFocus());
        prompt.addListener(SWT.KeyDown, event -> {
            if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) && EdtPreferences.enterSends()
                    && (event.stateMask & (SWT.SHIFT | SWT.CTRL | SWT.ALT | SWT.COMMAND)) == 0) {
                event.doit = false;
                if (ready && !busy && !prompt.getText().isBlank()) { submit.run(); }
            }
        });
        context = new Label(frame, SWT.WRAP); context.setText("Только чтение");
        context.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var options = new Composite(frame, SWT.NONE); options.setLayout(new GridLayout(3, false));
        var optionData = new GridData(SWT.FILL, SWT.CENTER, true, false); optionData.widthHint = 0; options.setLayoutData(optionData);
        models = new Combo(options, SWT.READ_ONLY); models.setData("codex.role", "model");
        models.setToolTipText("Модель");
        var modelData = new GridData(SWT.FILL, SWT.CENTER, true, false); modelData.widthHint = 110; models.setLayoutData(modelData);
        efforts = new Combo(options, SWT.READ_ONLY); efforts.setData("codex.role", "reasoning");
        efforts.setToolTipText("Рассуждение");
        var effortData = new GridData(SWT.FILL, SWT.CENTER, true, false); effortData.widthHint = 100; efforts.setLayoutData(effortData);
        send = new Button(options, SWT.PUSH); send.setData("codex.role", "send"); send.setText("↑"); send.setToolTipText("Отправить");
        send.addListener(SWT.Selection, event -> { if (running) { stop.run(); } else { submit.run(); } });
        prompt.addModifyListener(event -> update());
        models.addListener(SWT.Selection, event -> {
            Model model = catalog.get(models.getSelectionIndex());
            String previous = efforts.getSelectionIndex() < 0 ? "" : levels.get(efforts.getSelectionIndex()).value();
            selection.accept(model.id(), model.compatibleEffort(previous));
        });
        efforts.addListener(SWT.Selection, event -> {
            if (models.getSelectionIndex() >= 0 && efforts.getSelectionIndex() >= 0) {
                selection.accept(catalog.get(models.getSelectionIndex()).id(), levels.get(efforts.getSelectionIndex()).value());
            }
        });
        update();
    }
    void catalog(Snapshot snapshot) {
        catalog = snapshot.models();
        models.setItems(catalog.stream().map(Model::displayName).toArray(String[]::new));
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).id().equals(snapshot.model())) {
                models.select(i); levels = catalog.get(i).efforts();
                efforts.setItems(levels.stream().map(level -> label(level.value())).toArray(String[]::new));
                for (int j = 0; j < levels.size(); j++) { if (levels.get(j).value().equals(snapshot.effort())) { efforts.select(j); } }
                break;
            }
        }
    }
    static String label(String effort) {
        return switch (effort) {
            case "none" -> "Без рассуждения"; case "minimal" -> "Минимальное"; case "low" -> "Лёгкое";
            case "medium" -> "Среднее"; case "high" -> "Высокое"; case "xhigh" -> "Очень высокое";
            case "max" -> "Максимальное"; case "ultra" -> "Ультра"; default -> effort;
        };
    }
    void context(String value) { context.setText("Только чтение" + (value.isBlank() ? "" : " · " + value)); context.getParent().layout(true); }
    void state(boolean ready, boolean busy, boolean running) { this.ready = ready; this.busy = busy; this.running = running; update(); }
    private void update() {
        send.setText(running ? "■" : "↑"); send.setToolTipText(running ? "Остановить" : "Отправить");
        send.setEnabled(running || ready && !busy && !prompt.getText().isBlank());
        models.setEnabled(ready && !busy && !catalog.isEmpty()); efforts.setEnabled(ready && !busy && !levels.isEmpty());
    }
}
