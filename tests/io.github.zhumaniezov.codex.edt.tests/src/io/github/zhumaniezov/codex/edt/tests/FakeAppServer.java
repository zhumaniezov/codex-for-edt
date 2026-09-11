package io.github.zhumaniezov.codex.edt.tests;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class FakeAppServer {
    private static final Gson JSON = new Gson();
    private static String mode;
    private static boolean initialized;
    private static String cwd;
    private static int threads;
    private static int turns;
    private static JsonElement first;

    private static JsonObject obj(Object... pairs) {
        JsonObject value = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) { value.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1])); }
        return value;
    }
    private static void write(JsonObject value) throws Exception {
        byte[] bytes = (value + "\r\n").getBytes(StandardCharsets.UTF_8);
        int split = bytes.length / 2;
        System.out.write(bytes, 0, split);
        System.out.flush();
        System.out.write(bytes, split, bytes.length - split);
        System.out.flush();
    }
    private static void reply(JsonElement id, JsonObject value) throws Exception { write(obj("id", id, "result", value)); }
    private static void event(String method, JsonObject params) throws Exception { write(obj("method", method, "params", params)); }
    private static void check(boolean valid) { if (!valid) { throw new IllegalStateException("Неверный запрос клиента"); } }

    public static void main(String[] args) throws Exception {
        mode = args[0];
        if (mode.equals("linger")) { Thread.sleep(60000); return; }
        if (mode.equals("exit")) { return; }
        System.err.println("Диагностика тестового сервера");
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                if (!request.has("method")) { continue; }
                String method = request.get("method").getAsString();
                JsonElement id = request.get("id");
                JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
                if (method.equals("initialize")) {
                    check(params.getAsJsonObject("clientInfo").get("name").getAsString().equals("codex_edt"));
                    check(!params.has("capabilities") || !params.getAsJsonObject("capabilities").has("experimentalApi"));
                    reply(id, obj("userAgent", "test"));
                    continue;
                }
                if (method.equals("initialized")) { initialized = true; continue; }
                check(initialized);
                switch (method) {
                    case "account/read" -> {
                        check(!params.get("refreshToken").getAsBoolean());
                        reply(id, obj("requiresOpenaiAuth", true, "account", mode.equals("auth") ? null : obj("type", "chatgpt")));
                    }
                    case "model/list" -> reply(id, params.has("cursor")
                        ? obj("data", List.of(obj("model", "default-model", "isDefault", true, "hidden", false)))
                        : obj("data", List.of(obj("model", "fallback-model", "isDefault", false, "hidden", false)), "nextCursor", "page-2"));
                    case "config/read" -> reply(id, obj("config", obj("mcp_servers", obj("external", obj("enabled", true)))));
                    case "thread/start" -> {
                        check(params.get("sandbox").getAsString().equals("read-only"));
                        check(params.get("approvalPolicy").getAsString().equals("never"));
                        check(params.getAsJsonObject("config").getAsJsonObject("mcp_servers")
                            .getAsJsonObject("external").get("enabled").getAsBoolean() == false);
                        check(params.getAsJsonObject("config").getAsJsonObject("features").get("plugins").getAsBoolean() == false);
                        cwd = params.get("cwd").getAsString();
                        threads++;
                        reply(id, obj("thread", obj("id", "thread-" + threads), "cwd", cwd, "model", "default-model",
                            "approvalPolicy", "never", "sandbox", obj("type", mode.equals("unsafe") ? "workspaceWrite" : "readOnly",
                                "networkAccess", false)));
                    }
                    case "turn/start" -> {
                        check(params.get("cwd").getAsString().equals(cwd));
                        check(params.getAsJsonObject("sandboxPolicy").get("type").getAsString().equals("readOnly"));
                        check(!params.getAsJsonObject("sandboxPolicy").get("networkAccess").getAsBoolean());
                        String thread = params.get("threadId").getAsString();
                        String turn = "turn-" + ++turns;
                        reply(id, obj("turn", obj("id", turn, "status", "inProgress")));
                        event("turn/started", obj("threadId", thread, "turn", obj("id", turn)));
                        if (mode.equals("deny")) {
                            write(obj("id", 1, "method", "item/commandExecution/requestApproval", "params", obj()));
                            continue;
                        }
                        if (mode.equals("fail") || mode.equals("retry")) {
                            event("error", obj("threadId", thread, "turnId", turn,
                                "willRetry", mode.equals("retry"), "error", obj("message", "Тестовая ошибка")));
                            if (mode.equals("fail")) { continue; }
                        }
                        event("item/agentMessage/delta", obj("threadId", "foreign-thread", "turnId", turn, "itemId", "a", "delta", "Чужой ответ"));
                        String a = "Сообщить выводит ";
                        String b = "«Привет». " + thread;
                        event("item/agentMessage/delta", obj("threadId", thread, "turnId", turn, "itemId", "a", "delta", a));
                        Thread.sleep(120);
                        event("item/agentMessage/delta", obj("threadId", thread, "turnId", turn, "itemId", "a", "delta", b));
                        Thread.sleep(120);
                        event("item/completed", obj("threadId", thread, "turnId", turn, "item",
                            obj("type", "agentMessage", "id", "a", "text", a + b)));
                        event("turn/completed", obj("threadId", thread, "turn", obj("id", turn, "status", "completed", "error", null)));
                    }
                    case "echo/first" -> first = id;
                    case "echo/second" -> {
                        reply(id, obj("value", "second"));
                        event("test/notification", obj("text", "Привет"));
                        reply(first, obj("value", "first"));
                    }
                    case "broken" -> { System.out.write("{bad}\n".getBytes(StandardCharsets.UTF_8)); System.out.flush(); }
                    case "error" -> write(obj("id", id, "error", obj("code", -1, "message", "Тестовая ошибка")));
                    case "timeout" -> { }
                    case "child" -> {
                        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java.exe", "-cp",
                            System.getProperty("java.class.path"), FakeAppServer.class.getName(), "linger").start();
                        reply(id, obj("pid", child.pid()));
                    }
                    default -> throw new IllegalArgumentException("Неизвестный метод");
                }
            }
        }
        if (mode.equals("hang")) { Thread.sleep(60000); }
    }
}

