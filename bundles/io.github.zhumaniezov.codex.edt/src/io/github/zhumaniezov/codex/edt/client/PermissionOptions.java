package io.github.zhumaniezov.codex.edt.client;

import com.google.gson.*;
import java.util.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;

public record PermissionOptions(Set<PermissionMode> allowed) {
    public static final PermissionOptions CLOSED = new PermissionOptions(Set.of());

    public PermissionOptions {
        allowed = Set.copyOf(allowed);
    }

    public static PermissionOptions read(JsonObject response, List<JsonObject> profiles, String model) {
        var r = response.has("requirements") && response.get("requirements").isJsonObject()
                ? response.getAsJsonObject("requirements")
                : new JsonObject();
        var result = EnumSet.noneOf(PermissionMode.class);
        for (var mode : PermissionMode.values()) {
            if (!allows(r, "allowedSandboxModes", mode.sandbox)
                    || !allows(r, "allowedApprovalPolicies", mode.approval)) {
                continue;
            }
            if (profiles.stream().noneMatch(p -> mode.profile.equals(string(p, "id")) && bool(p, "allowed"))) {
                continue;
            }
            if (r.has("autoReview") && r.get("autoReview").isJsonObject()) {
                var review = r.getAsJsonObject("autoReview");
                if (review.has("requiredOnModels") && review.get("requiredOnModels").isJsonArray()
                        && review.getAsJsonArray("requiredOnModels").asList().contains(new JsonPrimitive(model))
                        && mode != PermissionMode.AUTO) {
                    continue;
                }
            }
            result.add(mode);
        }
        return new PermissionOptions(result);
    }

    private static boolean allows(JsonObject r, String key, String value) {
        return !r.has(key) || r.get(key).isJsonNull()
                || r.get(key).isJsonArray() && r.getAsJsonArray(key).asList().contains(new JsonPrimitive(value));
    }
}
