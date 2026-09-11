package io.github.zhumaniezov.codex.edt.ui;
import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.jface.preference.*;
import org.eclipse.ui.*;
import io.github.zhumaniezov.codex.edt.settings.EdtPreferencesService;
public final class AdvancedPreferences extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
 public AdvancedPreferences() { super(GRID); setPreferenceStore(EdtPreferencesService.store()); setDescription(tr("contextHint")); }
 @Override public void init(IWorkbench workbench) { }
 @Override protected void createFieldEditors() {
  for (String[] item : new String[][] {{EdtPreferencesService.DIAGNOSTICS,"diagnostics"},{EdtPreferencesService.CONTEXT,"editorContext"}}) {
   addField(new BooleanFieldEditor(item[0], tr(item[1]), getFieldEditorParent()));
  }
 }
}
