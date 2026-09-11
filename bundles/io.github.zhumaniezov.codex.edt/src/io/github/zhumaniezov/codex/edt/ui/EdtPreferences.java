package io.github.zhumaniezov.codex.edt.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import io.github.zhumaniezov.codex.edt.CodexPlugin;

public final class EdtPreferences extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
    public static final String ENTER_SENDS = "enterSends";
    public static final String DIAGNOSTICS = "showDiagnostics";
    public EdtPreferences() {
        super(GRID);
        var store = new ScopedPreferenceStore(InstanceScope.INSTANCE, CodexPlugin.ID);
        store.setDefault(ENTER_SENDS, true); store.setDefault(DIAGNOSTICS, false);
        setPreferenceStore(store);
        setDescription("Настройки только этого клиента EDT. Общая конфигурация Codex не изменяется.");
    }
    public static boolean enterSends() { return InstanceScope.INSTANCE.getNode(CodexPlugin.ID).getBoolean(ENTER_SENDS, true); }
    public static boolean diagnostics() { return InstanceScope.INSTANCE.getNode(CodexPlugin.ID).getBoolean(DIAGNOSTICS, false); }
    @Override public void init(IWorkbench workbench) { }
    @Override protected void createFieldEditors() {
        addField(new BooleanFieldEditor(ENTER_SENDS, "Enter отправляет сообщение (Shift+Enter — новая строка)", getFieldEditorParent()));
        addField(new BooleanFieldEditor(DIAGNOSTICS, "Показывать диагностические сведения", getFieldEditorParent()));
    }
}
