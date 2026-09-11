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
    private static String activeThread;
    private static String activeTurn;
    private static final java.util.Map<String, JsonObject> saved = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, java.util.List<JsonObject>> history = new java.util.HashMap<>();
    private static JsonObject model(String name, boolean selected) {
        return obj("model", name, "id", name, "displayName", selected ? "Default Model" : "Fast Model",
            "isDefault", selected, "hidden", false, "defaultReasoningEffort", selected ? "medium" : "low",
            "supportedReasoningEfforts", selected
                ? List.of(obj("reasoningEffort", "medium", "description", "Среднее"), obj("reasoningEffort", "high", "description", "Высокое"))
                : List.of(obj("reasoningEffort", "low", "description", "Лёгкое")));
    }


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
                        reply(id, obj("requiresOpenaiAuth", true, "account", mode.equals("auth") ? null : obj("type", "chatgpt", "email", "tester@example.invalid", "planType", "plus")));
                    }
                    case "model/list" -> reply(id, params.has("cursor")
                        ? obj("data", List.of(model("default-model", true)))
                        : obj("data", List.of(model("fallback-model", false)), "nextCursor", "page-2"));
                    case "thread/list" -> {
                        check(params.get("sortKey").getAsString().equals("updated_at"));
                        var values = new java.util.ArrayList<>(saved.values()); java.util.Collections.reverse(values);
                        reply(id, obj("data", values, "nextCursor", null));
                    }
                    case "thread/read" -> reply(id, obj("thread", saved.get(params.get("threadId").getAsString())));
                    case "thread/turns/list" -> {
                        check(params.get("itemsView").getAsString().equals("full"));
                        var values = new java.util.ArrayList<>(history.getOrDefault(params.get("threadId").getAsString(), List.of()));
                        java.util.Collections.reverse(values); reply(id, obj("data", values, "nextCursor", null));
                    }
                    case "thread/unsubscribe" -> reply(id, obj("status", "unsubscribed"));
                    case "thread/name/set" -> {
                        saved.get(params.get("threadId").getAsString()).add("name", params.get("name")); reply(id, obj());
                    }
                    case "thread/resume" -> {
                        check(!params.has("ephemeral")); check(params.get("excludeTurns").getAsBoolean());
                        check(params.get("sandbox").getAsString().equals("read-only"));
                        check(params.get("approvalPolicy").getAsString().equals("never"));
                        cwd = saved.get(params.get("threadId").getAsString()).get("cwd").getAsString();
                        reply(id, obj("thread", saved.get(params.get("threadId").getAsString()), "cwd", cwd,
                            "model", params.get("model"), "approvalPolicy", "never",
                            "sandbox", obj("type", "readOnly", "networkAccess", false)));
                    }
                    case "turn/interrupt" -> {
                        check(params.get("threadId").getAsString().equals(activeThread));
                        check(params.get("turnId").getAsString().equals(activeTurn));
                        reply(id, obj());
                        event("turn/completed", obj("threadId", activeThread, "turn", obj("id", activeTurn, "status", "interrupted")));
                    }
                    case "account/logout" -> { mode = "auth"; reply(id, obj()); }
                    case "config/read" -> reply(id, obj("config", obj("mcp_servers", obj("external", obj("enabled", true)))));
                    case "thread/start" -> {
                        check(params.get("sandbox").getAsString().equals("read-only"));
                        check(params.get("approvalPolicy").getAsString().equals("never"));
                        check(params.getAsJsonObject("config").getAsJsonObject("mcp_servers")
                            .getAsJsonObject("external").get("enabled").getAsBoolean() == false);
                        check(params.getAsJsonObject("config").getAsJsonObject("features").get("plugins").getAsBoolean() == false);
                        cwd = params.get("cwd").getAsString();
                        check(!params.get("ephemeral").getAsBoolean());
                        threads++;
                        saved.put("thread-" + threads, obj("id", "thread-" + threads, "name", null, "preview", "", "updatedAt", 1800000000L + threads,
                            "cwd", cwd, "parentThreadId", null));
                        reply(id, obj("thread", obj("id", "thread-" + threads), "cwd", cwd, "model", params.get("model"),
                            "approvalPolicy", "never", "sandbox", obj("type", mode.equals("unsafe") ? "workspaceWrite" : "readOnly",
                                "networkAccess", false)));
                    }
                    case "turn/start" -> {
                        check(params.get("cwd").getAsString().equals(cwd));
                        check(params.getAsJsonObject("sandboxPolicy").get("type").getAsString().equals("readOnly"));
                        check(!params.getAsJsonObject("sandboxPolicy").get("networkAccess").getAsBoolean());
                        String thread = params.get("threadId").getAsString();
                        String turn = "turn-" + ++turns;
                        activeThread = thread; activeTurn = turn;
                        if (params.get("model").getAsString().equals("fallback-model")) { check(params.get("effort").getAsString().equals("low")); }
                        else { check(List.of("medium", "high").contains(params.get("effort").getAsString())); }
                        history.computeIfAbsent(thread, key -> new java.util.ArrayList<>()).add(obj("id", turn, "status", "completed", "items",
                            List.of(obj("type", "userMessage", "content", params.get("input")),
                                obj("type", "agentMessage", "text", "Ответ из истории " + thread))));
                        reply(id, obj("turn", obj("id", turn, "status", "inProgress")));
                        event("turn/started", obj("threadId", thread, "turn", obj("id", turn)));
                        if (mode.equals("auth-update")) {
                            mode = "auth"; event("account/updated", obj("authMode", null, "planType", null)); continue;
                        }
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
                        if (mode.equals("interrupt") && turns == 1) { continue; }
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

