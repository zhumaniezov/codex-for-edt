package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.string;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.settings.CodexSettingsService;
import io.github.zhumaniezov.codex.edt.client.SessionData.Model;
import java.util.List;

public final class CodexPreferences extends SettingsPage {
    private Combo models, efforts;
    private Label values;
    private CodexSettingsService service;
    private CodexSettingsService.Configuration config;
    private List<Model> catalog = List.of();
    @Override protected void createBody() {
        service = new CodexSettingsService(access.client);
        label(body, tr("configHint")); label(body, tr("model")); models = new Combo(body, SWT.READ_ONLY);
        label(body, tr("reasoning")); efforts = new Combo(body, SWT.READ_ONLY);
        models.addListener(SWT.Selection, event -> levels(""));
        values = label(body, ""); label(body, tr("defaultsHint"));
        var actions = row(); button(actions, tr("refresh"), this::refresh);
        button(actions, tr("save"), () -> {
            if (config == null || models.getSelectionIndex() < 0) { return; }
            if (MessageDialog.openQuestion(body.getShell(), tr("sharedConfirm"), tr("sharedWarning"))) {
                Model model = catalog.get(models.getSelectionIndex()); int index = efforts.getSelectionIndex();
                run(() -> service.defaults(config, model.id(), index < 0 ? "" : model.efforts().get(index).value()), value -> { refresh(); });
            }
        });
    }
    @Override protected void refresh() {
        run(service::read, value -> {
            config = value; catalog = access.client.snapshot().models(); models.setItems(catalog.stream().map(Model::displayName).toArray(String[]::new));
            String model = string(value.values(), "model");
            for (int i = 0; i < catalog.size(); i++) { if (catalog.get(i).id().equals(model) || model.isBlank() && catalog.get(i).isDefault()) { models.select(i); break; } }
            levels(string(value.values(), "model_reasoning_effort"));
            values.setText(tr("policy") + ": " + string(value.values(), "approval_policy") + "\n" + tr("sandbox") + ": " + string(value.values(), "sandbox_mode"));
        });
    }
    private void levels(String selected) {
        efforts.removeAll(); if (models.getSelectionIndex() < 0) { return; }
        Model model = catalog.get(models.getSelectionIndex());
        efforts.setItems(model.efforts().stream().map(level -> ComposerComponent.label(level.value())).toArray(String[]::new));
        String desired = model.compatibleEffort(selected);
        for (int i = 0; i < model.efforts().size(); i++) { if (model.efforts().get(i).value().equals(desired)) { efforts.select(i); } }
    }
}
