package io.github.zhumaniezov.codex.edt;
import io.github.zhumaniezov.codex.edt.settings.LocalizationService;
/** Совместимые имена сообщений жизненного цикла. */
public final class Messages {
 private Messages() { }
 public static String CONNECTING() { return LocalizationService.tr("text000"); }
 public static String CONNECTION_ERROR() { return LocalizationService.tr("text001"); }
 public static String RECONNECT() { return LocalizationService.tr("text002"); }
 public static String CLOSED() { return LocalizationService.tr("text003"); }
 public static String UNAVAILABLE() { return LocalizationService.tr("text004"); }
}
