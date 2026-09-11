package io.github.zhumaniezov.codex.edt.settings;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import com.google.gson.JsonObject;
import io.github.zhumaniezov.codex.edt.client.*;

public final class AccountService {
    private final CodexClient client;
    public AccountService(CodexClient client) { this.client = client; }
    public record Login(String id, String url) { }
    public record Usage(String name, String window, int usedPercent, Long minutes, Long resetsAt) { }
    public CompletionStage<SessionData.Account> read() {
        return client.manage(ManagementRequest.ACCOUNT_READ, object("refreshToken", false)).thenApply(SessionData::account);
    }
    public CompletionStage<List<Usage>> limits() { return client.manage(ManagementRequest.LIMITS_READ, null).thenApply(AccountService::mapLimits); }
    public CompletionStage<Login> login() {
        return client.manage(ManagementRequest.LOGIN_START, object("type", "chatgpt"))
            .thenApply(value -> new Login(string(value, "loginId"), string(value, "authUrl")));
    }
    public CompletionStage<JsonObject> cancel(String id) { return client.manage(ManagementRequest.LOGIN_CANCEL, object("loginId", id)); }
    public CompletionStage<Void> logout() { return client.logout(); }
    public static List<Usage> mapLimits(JsonObject value) {
        var result = new ArrayList<Usage>();
        var buckets = value.has("rateLimitsByLimitId") && value.get("rateLimitsByLimitId").isJsonObject()
            ? value.getAsJsonObject("rateLimitsByLimitId") : object("codex", value.get("rateLimits"));
        for (var entry : buckets.entrySet()) {
            if (!entry.getValue().isJsonObject()) { continue; }
            var bucket = entry.getValue().getAsJsonObject(); String name = string(bucket, "limitName");
            for (String window : List.of("primary", "secondary")) {
                if (!bucket.has(window) || !bucket.get(window).isJsonObject()) { continue; }
                var data = bucket.getAsJsonObject(window);
                if (!data.has("usedPercent") || data.get("usedPercent").isJsonNull()) { continue; }
                result.add(new Usage(name.isBlank() ? entry.getKey() : name, window, Math.max(0, Math.min(100, data.get("usedPercent").getAsInt())),
                    nullableLong(data, "windowDurationMins"), nullableLong(data, "resetsAt")));
            }
        }
        return List.copyOf(result);
    }
    private static Long nullableLong(JsonObject data, String key) { return data.has(key) && !data.get(key).isJsonNull() ? data.get(key).getAsLong() : null; }
}
