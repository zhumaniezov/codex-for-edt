package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import com.google.gson.JsonObject;

public final class EdtToolException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String code;
    public EdtToolException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
    public JsonObject result() { return object("status", "error", "code", code, "message", getMessage()); }
}
