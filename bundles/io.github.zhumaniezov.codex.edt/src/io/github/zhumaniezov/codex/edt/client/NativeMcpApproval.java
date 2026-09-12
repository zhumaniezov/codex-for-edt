package io.github.zhumaniezov.codex.edt.client;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import com.google.gson.JsonObject;

/** Только пустая approval-форма собственного сервера; ввод данных и URL не поддерживаются. */
public final class NativeMcpApproval {
    public static final String METHOD="mcpServer/elicitation/request";
    private NativeMcpApproval() { }
    public static boolean supported(JsonObject params,String server,String thread,String turn) {
        if(params==null || server==null || server.isBlank() || thread==null || thread.isBlank() || turn==null || turn.isBlank())return false;
        if(!server.equals(string(params,"serverName")) || !thread.equals(string(params,"threadId")) || !turn.equals(string(params,"turnId")))return false;
        if(!java.util.Set.of("form","openai/form","openaiForm").contains(string(params,"mode")))return false;
        if(!params.has("requestedSchema") || !params.get("requestedSchema").isJsonObject())return false;
        var schema=params.getAsJsonObject("requestedSchema");
        if(!"object".equals(string(schema,"type")) || !schema.has("properties") || !schema.get("properties").isJsonObject() || !schema.getAsJsonObject("properties").isEmpty())return false;
        if(schema.has("required") && (!schema.get("required").isJsonArray() || !schema.getAsJsonArray("required").isEmpty()))return false;
        if(!params.has("_meta") || !params.get("_meta").isJsonObject())return false;
        return "mcp_tool_call".equals(string(params.getAsJsonObject("_meta"),"codex_approval_kind"));
    }
}
