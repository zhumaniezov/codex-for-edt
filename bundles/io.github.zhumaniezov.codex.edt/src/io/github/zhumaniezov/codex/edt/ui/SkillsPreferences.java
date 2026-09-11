package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.settings.SkillsService;

public final class SkillsPreferences extends SettingsPage {
    private Table table;
    private Label description;
    @Override protected void createBody() {
        label(body, tr("skillsHint")); table = table(tr("name"), tr("status")); description = label(body, "");
        table.addListener(SWT.Selection, event -> {
            if (table.getSelectionCount() == 0) { return; }
            var skill = (SkillsService.Skill) table.getSelection()[0].getData();
            description.setText(skill.description() + (EdtPreferences.diagnostics() ? "\n" + skill.scope() + "\n" + skill.path() : "")); body.layout(true, true);
        });
        button(row(), tr("refresh"), this::refresh);
    }
    @Override protected void refresh() {
        run(() -> new SkillsService(access.client).list(access.cwd), skills -> {
            table.removeAll(); for (var skill : skills) {
                var item = new TableItem(table, SWT.NONE); item.setData(skill);
                item.setText(new String[] {skill.name(), tr(skill.enabled() ? "enabled" : "disabled")});
            }
        });
    }
}
