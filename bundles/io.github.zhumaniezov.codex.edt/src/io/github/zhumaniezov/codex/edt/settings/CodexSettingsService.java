package io.github.zhumaniezov.codex.edt.settings;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.util.List;
import java.util.concurrent.CompletionStage;
import com.google.gson.*;
import io.github.zhumaniezov.codex.edt.client.CodexClient;

public final class CodexSettingsService {
    private final CodexClient client;
    public CodexSettingsService(CodexClient client) { this.client = client; }
    public record Configuration(JsonObject values, String version, JsonObject userValues) {
        public Configuration { values = values.deepCopy(); userValues = userValues.deepCopy(); }
        @Override public JsonObject userValues() { return userValues.deepCopy(); }
        @Override public JsonObject values() { return values.deepCopy(); }
    }
    public CompletionStage<Configuration> read() {
        return client.manage(ManagementRequest.CONFIG_READ, object("includeLayers", true)).thenApply(CodexSettingsService::map);
    }
    public static Configuration map(JsonObject response) {
        String version = ""; JsonObject user = new JsonObject();
        if (response.has("layers") && response.get("layers").isJsonArray()) {
            for (var element : response.getAsJsonArray("layers")) {
                var layer = element.getAsJsonObject(); var name = layer.getAsJsonObject("name");
                if ("user".equals(string(name, "type")) && string(name, "profile").isBlank()) { version = string(layer, "version"); user = layer.getAsJsonObject("config"); }
            }
        }
        return new Configuration(response.getAsJsonObject("config"), version, user);
    }
    private static void version(Configuration config) {
        if (config.version().isBlank()) { throw new IllegalStateException(LocalizationService.tr("configVersionMissing")); }
    }
    public CompletionStage<JsonObject> defaults(Configuration config, String model, String effort) {
        version(config);
        return client.manage(ManagementRequest.CONFIG_BATCH, object("expectedVersion", config.version(), "edits", List.of(
            object("keyPath", "model", "mergeStrategy", "replace", "value", model),
            object("keyPath", "model_reasoning_effort", "mergeStrategy", "replace", "value", effort.isBlank() ? null : effort))));
    }
    public CompletionStage<JsonObject> server(Configuration config, String name, JsonElement value) {
        version(config);
        if (!name.matches("[A-Za-z0-9_-]+")) { throw new IllegalArgumentException(LocalizationService.tr("invalidMcp")); }
        return client.manage(ManagementRequest.CONFIG_WRITE, object("expectedVersion", config.version(),
            "keyPath", "mcp_servers." + name, "mergeStrategy", "replace", "value", value));
    }
}
