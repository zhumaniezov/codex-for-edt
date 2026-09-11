package io.github.zhumaniezov.codex.edt.ui.presentation;

import java.time.*;
import java.time.format.DateTimeFormatter;
import io.github.zhumaniezov.codex.edt.settings.LocalizationService;

public final class ChatListPresentation {
    private ChatListPresentation() {
    }

    public static String time(long timestamp, long now) {
        long minutes = Math.max(0, (now - timestamp) / 60);
        if (minutes < 1) {
            return LocalizationService.tr("timeNow");
        }
        if (minutes < 60) {
            return java.text.MessageFormat.format(LocalizationService.tr("timeMinutes"), minutes);
        }
        if (minutes < 1440) {
            return java.text.MessageFormat.format(LocalizationService.tr("timeHours"), minutes / 60);
        }
        if (minutes < 10080) {
            return java.text.MessageFormat.format(LocalizationService.tr("timeDays"), minutes / 1440);
        }
        return DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(timestamp));
    }
}
