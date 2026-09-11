package io.github.zhumaniezov.codex.edt.ui;
import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.jface.preference.*;
import org.eclipse.ui.*;
import io.github.zhumaniezov.codex.edt.settings.EdtPreferencesService;
public final class EdtPreferences extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
 public static final String ENTER_SENDS = EdtPreferencesService.ENTER, DIAGNOSTICS = EdtPreferencesService.DIAGNOSTICS;
 public EdtPreferences() { super(GRID); setPreferenceStore(EdtPreferencesService.store()); setDescription(tr("languageHint")); }
 public static boolean enterSends() { return EdtPreferencesService.get(ENTER_SENDS, true); }
 public static boolean diagnostics() { return EdtPreferencesService.get(DIAGNOSTICS, false); }
 @Override public void init(IWorkbench workbench) { }
 @Override protected void createFieldEditors() {
  addField(new ComboFieldEditor(EdtPreferencesService.LANGUAGE, tr("language"), new String[][] {{tr("automatic"),"auto"},{"Русский","ru"},{"English","en"}}, getFieldEditorParent()));
  addField(new BooleanFieldEditor(ENTER_SENDS, tr("text104"), getFieldEditorParent()));
  addField(new BooleanFieldEditor(EdtPreferencesService.AUTO_OPEN, tr("autoOpen"), getFieldEditorParent()));
 }
}
