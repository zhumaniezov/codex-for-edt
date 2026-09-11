package io.github.zhumaniezov.codex.edt.settings;

import java.util.Locale;
import java.util.ResourceBundle;
import org.eclipse.core.runtime.Platform;

public final class LocalizationService {
    private static final String BUNDLE = "io.github.zhumaniezov.codex.edt.messages";
    private LocalizationService() { }
    public static Locale resolve(String preference, String platformLocale) {
        String value = "auto".equals(preference) ? platformLocale : preference;
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("ru") ? Locale.forLanguageTag("ru") : Locale.ENGLISH;
    }
    public static Locale locale() {
        return resolve(EdtPreferencesService.language(), Platform.getNL());
    }
    public static String text(String key, Locale locale) {
        return ResourceBundle.getBundle(BUNDLE, locale, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)).getString(key);
    }
    public static String tr(String key) { return text(key, locale()); }
    public static String value(String key) { try { return tr(key); } catch (java.util.MissingResourceException error) { return key; } }
}
