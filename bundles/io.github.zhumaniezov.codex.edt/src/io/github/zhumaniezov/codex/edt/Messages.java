package io.github.zhumaniezov.codex.edt;

import java.util.ResourceBundle;

/** Общие сообщения жизненного цикла панели. */
public final class Messages {
    private static final ResourceBundle TEXT = ResourceBundle.getBundle("io.github.zhumaniezov.codex.edt.messages");
    public static final String CONNECTING = TEXT.getString("connecting");
    public static final String CONNECTION_ERROR = TEXT.getString("connectionError");
    public static final String RECONNECT = TEXT.getString("reconnect");
    public static final String CLOSED = TEXT.getString("closed");
    public static final String UNAVAILABLE = TEXT.getString("unavailable");
    private Messages() { }
}
