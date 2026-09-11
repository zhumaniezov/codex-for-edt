package io.github.zhumaniezov.codex.edt.settings;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import io.github.zhumaniezov.codex.edt.client.CodexClient;

public final class McpService {
    private final CodexClient client;
    public McpService(CodexClient client) { this.client = client; }
    public record Server(String name, Boolean enabled, String transport, String status, String auth, Integer tools) { }
    public CompletionStage<List<Server>> list(CodexSettingsService.Configuration config) {
        return page(config.values(), "", new HashSet<>(), new ArrayList<>());
    }
    private CompletionStage<List<Server>> page(JsonObject config, String cursor, Set<String> seen, List<JsonObject> rows) {
        var params = object("limit", 100, "detail", "toolsAndAuthOnly");
        if (!cursor.isBlank()) { params.addProperty("cursor", cursor); }
        return client.manage(ManagementRequest.MCP_LIST, params).thenCompose(response -> {
            response.getAsJsonArray("data").forEach(value -> rows.add(value.getAsJsonObject()));
            String next = string(response, "nextCursor");
            if (next.isBlank()) { return CompletableFuture.completedFuture(map(config, rows)); }
            if (!seen.add(next)) { return CompletableFuture.failedFuture(new IllegalStateException("Repeated MCP cursor")); }
            return page(config, next, seen, rows);
        });
    }
    public static List<Server> map(JsonObject config, List<JsonObject> statuses) {
        var result = new LinkedHashMap<String, Server>();
        var servers = config.has("mcp_servers") && config.get("mcp_servers").isJsonObject() ? config.getAsJsonObject("mcp_servers") : new JsonObject();
        servers.entrySet().forEach(entry -> {
            var value = entry.getValue().getAsJsonObject();
            result.put(entry.getKey(), new Server(entry.getKey(), !value.has("enabled") || bool(value, "enabled"),
                value.has("url") ? "HTTP" : value.has("command") ? "STDIO" : "", "", "", null));
        });
        for (var status : statuses) {
            String name = string(status, "name"); var configured = result.get(name);
            result.put(name, new Server(name, configured == null ? null : configured.enabled(), configured == null ? "" : configured.transport(),
                string(status, "runtimeStatus"), string(status, "authStatus"), status.has("tools") && status.get("tools").isJsonObject() ? status.getAsJsonObject("tools").size() : null));
        }
        return List.copyOf(result.values());
    }
    public CompletionStage<JsonObject> reload() { return client.manage(ManagementRequest.MCP_RELOAD, null); }
    public CompletionStage<String> login(String name) {
        return client.manage(ManagementRequest.MCP_LOGIN, object("name", name)).thenApply(value -> string(value, "authorizationUrl"));
    }
}
