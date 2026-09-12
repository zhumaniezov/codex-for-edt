package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.semantic.*;

final class SemanticActivityComponent {
    private final Composite root, requests;
    private final Text result;
    private final ThemePalette palette;

    SemanticActivityComponent(Composite parent, ThemePalette palette) {
        this.palette = palette;
        root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        palette.apply(root, "panel", "text");
        requests = new Composite(root, SWT.NONE);
        requests.setLayout(new GridLayout(1, false));
        requests.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        palette.apply(requests, "panel", "text");
        result = new Text(root, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        var size = new GridData(SWT.FILL, SWT.FILL, true, false);
        size.heightHint = 60;
        size.widthHint = 0;
        result.setLayoutData(size);
        palette.apply(result, "panel", "muted");
        result.setData("codex.role", "semanticResult");
        var problems = new Button(root, SWT.PUSH);
        problems.setText(tr("semanticProblems"));
        problems.addListener(SWT.Selection, e -> {
            try {
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
                        .showView(org.eclipse.ui.IPageLayout.ID_PROBLEM_VIEW);
            } catch (org.eclipse.ui.PartInitException error) {
                io.github.zhumaniezov.codex.edt.CodexPlugin.log(tr("semanticProblems"), error);
            }
        });
        visible();
    }

    void approval(SemanticApproval approval) {
        if (approval.decision().isDone()) {
            return;
        }
        var block = new Composite(requests, SWT.BORDER);
        block.setLayout(new GridLayout(1, false));
        block.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        palette.apply(block, "input", "text");
        var title = new Label(block, SWT.WRAP);
        title.setText(tr("semanticApproval") + " · " + approval.project());
        title.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        palette.apply(title, "input", "text");
        var plan = new Text(block, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        plan.setText(MetadataPresentation.plan(approval.plan()));
        var size = new GridData(SWT.FILL, SWT.FILL, true, false);
        size.heightHint = 120;
        size.widthHint = 0;
        plan.setLayoutData(size);
        palette.apply(plan, "input", "text");
        var actions = new Composite(block, SWT.NONE);
        actions.setLayout(new GridLayout(2, false));
        palette.apply(actions, "input", "text");
        for (boolean accept : new boolean[] { true, false }) {
            var button = new Button(actions, SWT.PUSH);
            button.setText(tr(accept ? "semanticAccept" : "semanticDecline"));
            button.setData("codex.role", accept ? "semanticAccept" : "semanticDecline");
            button.addListener(SWT.Selection, e -> approval.answer(accept));
        }
        block.addDisposeListener(e -> approval.answer(false));
        var display = root.getDisplay();
        approval.decision().whenComplete((v, error) -> {
            if (!display.isDisposed()) {
                try {
                    display.asyncExec(() -> {
                        if (!block.isDisposed()) {
                            block.dispose();
                            visible();
                        }
                    });
                } catch (org.eclipse.swt.SWTException ignored) {
                }
            }
        });
        visible();
    }

    void result(com.google.gson.JsonObject value) {
        root.setData("codex.semantic", value.deepCopy());
        String text = result.getText() + "\n" + MetadataPresentation.result(value);
        result.setText(text.substring(Math.max(0, text.length() - 16000)));
        result.setSelection(result.getCharCount());
        visible();
    }

    void clear() {
        for (var control : requests.getChildren()) {
            control.dispose();
        }
        result.setText("");
        visible();
    }

    private void visible() {
        boolean show = requests.getChildren().length > 0 || !result.getText().isBlank();
        ((GridData) root.getLayoutData()).exclude = !show;
        root.setVisible(show);
        ((GridData) result.getLayoutData()).exclude = result.getText().isBlank();
        result.setVisible(!result.getText().isBlank());
        root.getParent().layout(true, true);
    }
}
