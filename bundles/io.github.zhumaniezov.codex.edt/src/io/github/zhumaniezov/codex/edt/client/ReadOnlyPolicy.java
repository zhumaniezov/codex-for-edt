package io.github.zhumaniezov.codex.edt.client;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonObject;

public final class ReadOnlyPolicy {
    private static final List<String> DISABLED_FEATURES = List.of("apps", "plugins", "hooks", "codex_hooks",
        "plugin_hooks", "browser_use", "computer_use", "image_generation", "multi_agent_v2", "code_mode");

    private ReadOnlyPolicy() { }

    public static List<String> command(Path executable) {
        var command = new ArrayList<>(List.of(executable.toString(), "app-server", "--listen", "stdio://"));
        for (String feature : DISABLED_FEATURES) {
            command.addAll(List.of("-c", "features." + feature + "=false"));
        }
        command.addAll(List.of("-c", "agents.enabled=false", "-c", "notify=[]"));
        return List.copyOf(command);
    }

    public static JsonObject config(JsonObject effectiveConfig) {
        JsonObject features = new JsonObject();
        DISABLED_FEATURES.forEach(name -> features.addProperty(name, false));
        JsonObject servers = new JsonObject();
        if (effectiveConfig.has("mcp_servers") && effectiveConfig.get("mcp_servers").isJsonObject()) {
            // Копируются только имена, без адресов, env и других значений настроек.
            effectiveConfig.getAsJsonObject("mcp_servers").keySet()
                .forEach(name -> servers.add(name, object("enabled", false)));
        }
        return object("features", features, "agents", object("enabled", false),
            "mcp_servers", servers, "notify", List.of(), "web_search", "disabled");
    }

    public static JsonObject thread(String directory, String model, JsonObject config) {
        return object("cwd", directory, "model", model, "sandbox", "read-only", "approvalPolicy", "never",
            "approvalsReviewer", "user", "ephemeral", true, "config", config,
            "developerInstructions", "Работай только в режиме чтения. Объясняй код. Не изменяй файлы, "
                + "не запускай операции записи и не запрашивай расширение разрешений.");
    }

    public static JsonObject turn(String threadId, String directory, String model, String prompt) {
        return object("threadId", threadId, "cwd", directory, "model", model, "approvalPolicy", "never",
            "approvalsReviewer", "user", "sandboxPolicy", object("type", "readOnly", "networkAccess", false),
            "input", List.of(object("type", "text", "text", prompt)));
    }

    public static Path directory(String directory) throws IOException {
        if (directory.isBlank()) { throw new IOException("Откройте BSL-модуль проекта EDT с физическим каталогом."); }
        Path path = Path.of(directory);
        if (!path.isAbsolute() || !Files.isDirectory(path)) {
            throw new IOException("Физический каталог проекта EDT не существует.");
        }
        return path.toRealPath();
    }

    public static void verify(JsonObject result, Path directory, String model) throws IOException {
        JsonObject sandbox = result.has("sandbox") && result.get("sandbox").isJsonObject()
            ? result.getAsJsonObject("sandbox") : new JsonObject();
        if (!"readOnly".equals(string(sandbox, "type")) || bool(sandbox, "networkAccess")
                || !"never".equals(string(result, "approvalPolicy"))
                || !model.equals(string(result, "model"))
                || !directory.equals(directory(string(result, "cwd")))) {
            throw new IOException("Codex не подтвердил ожидаемые cwd, модель и режим чтения. Запрос остановлен.");
        }
    }

    public static String prompt(ChatRequest request) {
        var context = request.context();
        StringBuilder result = new StringBuilder("[Контекст 1C:EDT]\nПроект: ").append(context.projectName());
        if (!context.modulePath().isBlank()) {
            result.append("\nТекущий объект/файл: ").append(context.modulePath());
        }
        if (!context.selectedText().isBlank()) {
            String selected = context.selectedText();
            result.append("\n\nВыделенный код:\n").append(selected.substring(0, Math.min(selected.length(), 16000)));
            if (selected.length() > 16000) { result.append("\n[Выделение сокращено до 16000 символов]"); }
        }
        return result.append("\n\n[Запрос пользователя]\n").append(request.message()).toString();
    }
}

