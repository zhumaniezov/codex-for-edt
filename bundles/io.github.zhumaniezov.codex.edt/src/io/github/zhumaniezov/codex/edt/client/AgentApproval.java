package io.github.zhumaniezov.codex.edt.client;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.List;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;

/**
 * Не содержит исполняемого кода; ответ строится только из исходного запроса
 * сервера.
 */
public record AgentApproval(String key, JsonElement id, String method, String thread, String turn, String item,
        JsonObject params, Path root, PermissionMode mode) {
    public AgentApproval {
        id = id.deepCopy();
        params = params.deepCopy();
    }

    public List<String> decisions() {
        if (method.equals("item/permissions/requestApproval")) {
            return List.of("accept", "acceptForSession", "decline");
        }
        if (params.has("availableDecisions") && params.get("availableDecisions").isJsonArray()) {
            return params.getAsJsonArray("availableDecisions").asList().stream().filter(JsonElement::isJsonPrimitive)
                    .map(JsonElement::getAsString)
                    .filter(List.of("accept", "acceptForSession", "decline", "cancel")::contains).toList();
        }
        return List.of("accept", "acceptForSession", "decline", "cancel");
    }

    public JsonObject response(String decision) {
        if (!decisions().contains(decision)) {
            throw new IllegalArgumentException(
                    io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr("approvalDecisionUnsupported"));
        }
        if (!method.equals("item/permissions/requestApproval")) {
            return object("decision", decision);
        }
        var granted = new JsonObject();
        if (decision.startsWith("accept") && mode.writes()) {
            var requested = params.getAsJsonObject("permissions");
            if (requested != null) {
                // Только точная копия показанного запроса; никаких дополнительных прав от
                // клиента.
                if (requested.has("network")) {
                    granted.add("network", requested.get("network").deepCopy());
                }
                if (requested.has("fileSystem")) {
                    granted.add("fileSystem", requested.get("fileSystem").deepCopy());
                }
            }
        }
        return object("permissions", granted, "scope", decision.equals("acceptForSession") ? "session" : "turn");
    }
}
