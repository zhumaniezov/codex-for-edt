package io.github.zhumaniezov.codex.edt.settings;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import io.github.zhumaniezov.codex.edt.CodexPlugin;
import io.github.zhumaniezov.codex.edt.context.EditorContext;

public final class EdtPreferencesService {
    public static final String LANGUAGE = "language", ENTER = "enterSends", DIAGNOSTICS = "showDiagnostics",
        AUTO_OPEN = "autoOpen", CONTEXT = "editorContext";
    private EdtPreferencesService() { }
    public static ScopedPreferenceStore store() {
        var store = new ScopedPreferenceStore(InstanceScope.INSTANCE, CodexPlugin.ID);
        store.setDefault(LANGUAGE, "auto"); store.setDefault(ENTER, true);
        store.setDefault(DIAGNOSTICS, false); store.setDefault(AUTO_OPEN, false);
        store.setDefault(CONTEXT, true);
        return store;
    }
    public static String language() { return InstanceScope.INSTANCE.getNode(CodexPlugin.ID).get(LANGUAGE, "auto"); }
    public static boolean get(String key, boolean fallback) { return InstanceScope.INSTANCE.getNode(CodexPlugin.ID).getBoolean(key, fallback); }
    public static EditorContext context(EditorContext value) {
        if (!get(CONTEXT, true)) { return new EditorContext(value.projectName(), "", "", value.projectDirectory()); }
        return value;
    }
}
