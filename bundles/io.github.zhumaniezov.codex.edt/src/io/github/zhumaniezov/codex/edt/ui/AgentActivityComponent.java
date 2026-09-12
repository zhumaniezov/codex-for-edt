package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import com.google.gson.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.client.*;

/** Показывает протокольные действия и approvals, не выполняя их. */
final class AgentActivityComponent {
    private final Composite root, approvalArea;
    private final Table table;
    private final Button review;
    private final ThemePalette palette;
    private final BiConsumer<String, String> decide;
    private final Consumer<String> openFile;
    private final Map<String, Composite> requests = new LinkedHashMap<>();
    private final LinkedHashMap<String, AgentActivity.Snapshot> turns = new LinkedHashMap<>();
    private final List<JsonObject> rows = new ArrayList<>();
    private final Map<String, StyledText> outputWindows = new HashMap<>();

    AgentActivityComponent(Composite parent, ThemePalette palette, BiConsumer<String, String> decide,
            Consumer<String> openFile) {
        this.palette = palette;
        this.decide = decide;
        this.openFile = openFile;
        root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setData("codex.role", "agentActivity");
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        palette.apply(root, "panel", "text");
        table = new Table(root, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setData("codex.role", "agentActions");
        var data = new GridData(SWT.FILL, SWT.FILL, true, false);
        data.heightHint = 80;
        data.widthHint = 0;
        table.setLayoutData(data);
        palette.apply(table, "input", "text");
        table.addListener(SWT.DefaultSelection, e -> details());
        review = new Button(root, SWT.PUSH);
        var reviewData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        reviewData.widthHint = 0;
        review.setLayoutData(reviewData);
        review.setText(tr("agentReview"));
        review.addListener(SWT.Selection, e -> review());
        approvalArea = new Composite(root, SWT.NONE);
        approvalArea.setLayout(new GridLayout(1, false));
        approvalArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        palette.apply(approvalArea, "panel", "text");
        visible();
    }

    void clear() {
        turns.clear();
        rows.clear();
        table.removeAll();
        requests.values().forEach(Composite::dispose);
        requests.clear();
        visible();
    }

    void activity(AgentActivity.Snapshot value) {
        root.setData("codex.activity", value);
        turns.put(value.turn(), value);
        while (turns.size() > 12) {
            turns.remove(turns.keySet().iterator().next());
        }
        int selected = table.getSelectionIndex();
        rows.clear();
        table.removeAll();
        for (var turn : turns.values()) {
            for (var item : turn.items()) {
                rows.add(item);
                var row = new TableItem(table, SWT.NONE);
                String description = string(item, "type").equals("commandExecution") ? string(item, "command")
                        : fileNames(item);
                row.setText(tr("action" + string(item, "status")) + " · " + description.replaceAll("\\s+", " "));
                row.setData("itemId", string(item, "id"));
                var output = outputWindows.get(turn.turn() + ":" + string(item, "id"));
                if (output != null && !output.isDisposed()) {
                    output.setText(commandText(item));
                    output.setTopIndex(Math.max(0, output.getLineCount() - 15));
                }
            }
        }
        if (selected >= 0 && selected < table.getItemCount()) {
            table.select(selected);
        }
        review.setEnabled(turns.values().stream()
                .anyMatch(t -> !t.diff().isBlank() || t.items().stream().anyMatch(i -> i.has("changes"))));
        if (value.complete() && !value.diff().isBlank()) {
            var display = root.getDisplay();
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return DiffReviewService.summary(value.diff());
                } catch (Exception error) {
                    return null;
                }
            }).thenAccept(summary -> {
                if (summary != null && !display.isDisposed()) {
                    try {
                        display.asyncExec(() -> {
                            if (!review.isDisposed() && turns.get(value.turn()) == value) {
                                review.setText(tr("agentReview") + " · " + summary.files() + " · +" + summary.added()
                                        + " −" + summary.deleted());
                                root.layout(true);
                            }
                        });
                    } catch (org.eclipse.swt.SWTException ignored) {
                    }
                }
            });
        }
        visible();
    }

    static String fileNames(JsonObject item) {
        if (!item.has("changes")) {
            return "";
        }
        var names = new ArrayList<String>();
        item.getAsJsonArray("changes").forEach(c -> {
            var change = c.getAsJsonObject();
            String kind = string(change.getAsJsonObject("kind"), "type");
            names.add(tr("change" + kind) + " " + string(change, "path"));
        });
        return String.join("; ", names);
    }

    void approval(AgentApproval request) {
        if (requests.containsKey(request.key())) {
            return;
        }
        var panel = new Composite(approvalArea, SWT.BORDER);
        panel.setLayout(new GridLayout(2, true));
        var layout = new GridData(SWT.FILL, SWT.TOP, true, false);
        layout.widthHint = 0;
        panel.setLayoutData(layout);
        panel.setData("codex.approval", request.key());
        palette.apply(panel, "input", "text");
        requests.put(request.key(), panel);
        var p = request.params();
        String text = tr("approvalTitle") + "\n" + string(p, "reason");
        if (request.method().equals("item/commandExecution/requestApproval")) {
            text += "\n" + string(p, "command") + "\n" + tr("approvalCwd") + " " + string(p, "cwd");
            text += "\n" + tr("approvalEscape");
            if (p.has("networkApprovalContext") && p.get("networkApprovalContext").isJsonObject()) {
                var n = p.getAsJsonObject("networkApprovalContext");
                text += "\n" + tr("approvalNetwork") + " " + string(n, "protocol") + "://" + string(n, "host");
            }
        } else if(request.method().equals(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.METHOD)) {
            text=tr("nativeMcpApproval")+"\n"+string(p,"message")+"\n"+tr("nativeMcpPlanFollows");
        } else if (request.method().equals("item/permissions/requestApproval")) {
            text += "\n" + tr("approvalPermissions") + "\n" + p.get("permissions");
        } else {
            var turn = turns.get(request.turn());
            if (turn != null && turn.thread().equals(request.thread())) {
                for (var row : turn.items()) {
                    if (request.item().equals(string(row, "id"))) {
                        text += "\n" + fileNames(row);
                    }
                }
            }
            if (!string(p, "grantRoot").isBlank()) {
                text += "\n" + tr("approvalExtraRoot") + " " + string(p, "grantRoot");
            }
        }
        var description = new Text(panel, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        description.setText(text);
        var d = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
        d.heightHint = 90;
        d.widthHint = 0;
        description.setLayoutData(d);
        palette.apply(description, "input", "text");
        if (request.method().equals("item/fileChange/requestApproval")) {
            var diff = new Button(panel, SWT.PUSH);
            var diffData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
            diffData.widthHint = 0;
            diff.setLayoutData(diffData);
            diff.setText(tr("agentReview"));
            diff.addListener(SWT.Selection, e -> review(request));
        }
        for (String decision : request.decisions()) {
            var button = new Button(panel, SWT.PUSH | SWT.WRAP);
            button.setText(tr("decision" + decision));
            button.setData("codex.decision", decision);
            button.setData("codex.nativeMcpApproval",request.method().equals(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.METHOD));
            var buttonData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            buttonData.widthHint = 0;
            buttonData.heightHint = 42;
            button.setLayoutData(buttonData);
            button.addListener(SWT.Selection, e -> {
                panel.setEnabled(false);
                decide.accept(request.key(), decision);
            });
        }
        visible();
    }

    void resolved(String key) {
        var p = requests.remove(key);
        if (p != null && !p.isDisposed()) {
            p.dispose();
        }
        visible();
    }

    boolean hasApprovals() {
        return !requests.isEmpty();
    }

    private void visible() {
        boolean first = true;
        for (var panel : requests.values()) {
            panel.setVisible(first);
            ((GridData) panel.getLayoutData()).exclude = !first;
            first = false;
        }
        table.setVisible(requests.isEmpty());
        ((GridData) table.getLayoutData()).exclude = !requests.isEmpty();
        review.setVisible(requests.isEmpty());
        ((GridData) review.getLayoutData()).exclude = !requests.isEmpty();
        boolean show = !rows.isEmpty() || !requests.isEmpty();
        root.setVisible(show);
        ((GridData) root.getLayoutData()).exclude = !show;
        root.getParent().layout(true, true);
    }

    private void details() {
        int index = table.getSelectionIndex();
        if (index < 0) {
            return;
        }
        var item = rows.get(index);
        if (item.has("changes")) {
            review();
            return;
        }
        var text = show(tr("agentCommand"), commandText(item), false);
        for (var turn : turns.values()) {
            if (turn.items().contains(item)) {
                String key = turn.turn() + ":" + string(item, "id");
                outputWindows.put(key, text);
                text.addDisposeListener(e -> outputWindows.remove(key, text));
            }
        }
    }

    private String commandText(JsonObject item) {
        return string(item, "command") + "\n" + string(item, "cwd") + "\n" + tr("agentExit") + " "
                + string(item, "exitCode") + "\n\n" + tr("agentOutputLimit") + "\n" + string(item, "aggregatedOutput");
    }

    private void review() {
        review(null);
    }

    private void review(AgentApproval approval) {
        var files = new LinkedHashMap<String, String>();
        var kinds = new HashMap<String, String>();
        var ordered = new ArrayList<>(turns.values());
        Collections.reverse(ordered);
        for (var turn : ordered) {
            if (approval != null
                    && (!approval.turn().equals(turn.turn()) || !approval.thread().equals(turn.thread()))) {
                continue;
            }
            if (approval == null && !turn.diff().isBlank()) {
                files.put(tr("agentTurnDiff") + " " + turn.turn(), turn.diff());
            }
            for (var item : turn.items()) {
                if (!item.has("changes") || approval != null && !approval.item().equals(string(item, "id"))) {
                    continue;
                }
                for (var change : item.getAsJsonArray("changes")) {
                    var c = change.getAsJsonObject();
                    String path = string(c, "path");
                    if (!files.containsKey(path)) {
                        files.put(path, string(c, "diff"));
                        kinds.put(path, string(c.getAsJsonObject("kind"), "type"));
                    }
                }
            }
        }
        if (files.isEmpty()) {
            return;
        }
        var shell = new Shell(root.getShell(), SWT.SHELL_TRIM | SWT.RESIZE);
        shell.setText(tr("agentReview"));
        shell.setLayout(new GridLayout(1, false));
        var selection = new Combo(shell, SWT.READ_ONLY);
        selection.setItems(files.keySet().toArray(String[]::new));
        selection.select(0);
        selection.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var text = diffText(shell);
        text.setText(files.get(selection.getText()));
        selection.addListener(SWT.Selection, e -> text.setText(files.get(selection.getText())));
        var open = new Button(shell, SWT.PUSH);
        open.setText(tr("agentOpenFile"));
        open.addListener(SWT.Selection, e -> {
            if (!selection.getText().startsWith(tr("agentTurnDiff"))) {
                openFile.accept(selection.getText());
            }
        });
        var compare = new Button(shell, SWT.PUSH);
        compare.setText(tr("diffCompare"));
        compare.addListener(SWT.Selection,
                e -> DiffReviewService.open(Map.of(selection.getText(), files.get(selection.getText())),
                        kinds.getOrDefault(selection.getText(), "")));
        shell.setSize(850, 600);
        shell.open();
    }

    private StyledText diffText(Composite parent) {
        var text = new StyledText(parent, SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        text.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
        text.addLineStyleListener(e -> {
            if (e.lineText.startsWith("+") || e.lineText.startsWith("-") || e.lineText.startsWith("@@")) {
                var style = new org.eclipse.swt.custom.StyleRange(e.lineOffset, e.lineText.length(), null, null,
                        SWT.BOLD);
                style.underline = e.lineText.startsWith("+");
                style.strikeout = e.lineText.startsWith("-");
                e.styles = new org.eclipse.swt.custom.StyleRange[] { style };
            }
        });
        return text;
    }

    private StyledText show(String title, String value, boolean diff) {
        var shell = new Shell(root.getShell(), SWT.SHELL_TRIM | SWT.RESIZE);
        shell.setText(title);
        shell.setLayout(new GridLayout());
        var text = diffText(shell);
        text.setText(value);
        shell.setSize(760, 500);
        shell.open();
        return text;
    }
}
