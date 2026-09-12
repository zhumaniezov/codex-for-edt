package io.github.zhumaniezov.codex.edt.tests;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class AgentFakeServer {
    private static final Gson JSON = new Gson();
    private static String cwd, active, scenario;
    private static JsonObject sandbox, config;
    private static int turns;

    private static JsonObject obj(Object... pairs) {
        var o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            o.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        }
        return o;
    }

    private static void write(JsonObject o) {
        System.out.println(o);
        System.out.flush();
    }

    private static void reply(JsonElement id, JsonObject result) {
        write(obj("id", id, "result", result));
    }

    private static void event(String method, JsonObject p) {
        write(obj("method", method, "params", p));
    }

    private static JsonObject params() {
        return obj("threadId", "t", "turnId", active, "itemId", "item");
    }

    public static void run() throws Exception {
        try (var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                var r = JsonParser.parseString(line).getAsJsonObject();
                var id = r.get("id");
                if (!r.has("method")) {
                    if (!new JsonPrimitive("approval").equals(id)) {
                        throw new IllegalStateException("Wrong response id");
                    }
                    var result = r.getAsJsonObject("result");
                    event("serverRequest/resolved", obj("threadId", "t", "requestId", id));
                    var done = params();
                    done.add("item",
                            obj("id", "item", "type", scenario.contains("command") ? "commandExecution" : "fileChange",
                                    "status", "completed", "changes", List.of(obj("path", cwd + "/test.txt", "kind",
                                            obj("type", "update"), "diff", "@@ -1 +1 @@\n-old\n+new\n"))));
                    event("item/completed", done);
                    var delta = params();
                    delta.addProperty("delta", result.toString());
                    event("item/agentMessage/delta", delta);
                    event("turn/completed", obj("threadId", "t", "turn", obj("id", active, "status", "completed")));
                    continue;
                }
                String method = r.get("method").getAsString();
                var p = r.has("params") ? r.getAsJsonObject("params") : obj();
                switch (method) {
                case "initialize" -> reply(id, obj());
                case "initialized" -> {
                }
                case "account/read" -> reply(id, obj("requiresOpenaiAuth", false));
                case "model/list" -> reply(id,
                        obj("data",
                                List.of(obj("model", "test", "displayName", "Test", "isDefault", true,
                                        "defaultReasoningEffort", "medium", "supportedReasoningEfforts",
                                        List.of(obj("reasoningEffort", "medium"))))));
                case "config/read" -> reply(id, obj("config", obj()));
                case "configRequirements/read" -> reply(id, obj("requirements", null));
                case "permissionProfile/list" -> reply(id, obj("data", List.of(obj("id", ":read-only", "allowed", true),
                        obj("id", ":workspace", "allowed", true), obj("id", ":danger-full-access", "allowed", true))));
                case "thread/start", "thread/resume" -> {
                    cwd = p.get("cwd").getAsString();
                    config = p.getAsJsonObject("config");
                    String mode = p.get("sandbox").getAsString();
                    sandbox = obj("type",
                            mode.equals("read-only") ? "readOnly"
                                    : mode.equals("workspace-write") ? "workspaceWrite" : "dangerFullAccess",
                            "networkAccess", false);
                    if (mode.equals("workspace-write")) {
                        sandbox.add("writableRoots",
                                config.getAsJsonObject("sandbox_workspace_write").get("writable_roots"));
                        sandbox.addProperty("excludeTmpdirEnvVar", true);
                        sandbox.addProperty("excludeSlashTmp", true);
                    }
                    reply(id,
                            obj("thread", obj("id", "t"), "cwd", cwd, "model", "test", "sandbox", sandbox,
                                    "approvalPolicy", p.get("approvalPolicy"), "approvalsReviewer",
                                    p.get("approvalsReviewer")));
                }
                case "thread/unsubscribe", "thread/name/set" -> reply(id, obj());
                case "turn/interrupt" -> {
                    reply(id, obj());
                    event("turn/completed", obj("threadId", "t", "turn", obj("id", active, "status", "interrupted")));
                }
                case "turn/start" -> {
                    active = "turn-" + (++turns);
                    scenario = p.getAsJsonArray("input").get(0).getAsJsonObject().get("text").getAsString();
                    reply(id, obj("turn", obj("id", active, "status", "inProgress")));
                    if (turns > 1) {
                        event("item/commandExecution/outputDelta",
                                obj("threadId", "t", "turnId", "turn-1", "itemId", "item", "delta", "LATE"));
                    }
                    if (!p.getAsJsonObject("sandboxPolicy").get("type").getAsString().equals("readOnly")) {
                        var started = params();
                        started.add("item",
                                obj("id", "item", "type",
                                        scenario.contains("command") ? "commandExecution" : "fileChange", "status",
                                        "inProgress", "command", "echo test", "cwd", cwd, "changes", List.of(obj("path",
                                                cwd + "/test.txt", "kind", obj("type", "delete"), "diff", "-old"))));
                        event("item/started", started);
                        var output = params();
                        output.addProperty("delta", "STREAM");
                        event("item/commandExecution/outputDelta", output);
                        var diff = params();
                        diff.addProperty("diff", "--- a/test.txt\n+++ b/test.txt\n@@ -1 +1 @@\n-old\n+new\n");
                        event("turn/diff/updated", diff);
                        var a = params();
                        a.addProperty("cwd", cwd);
                        a.addProperty("command", "echo test");
                        a.addProperty("startedAtMs", 1);
                        a.add("permissions", obj("network", obj("enabled", true)));
                        if (scenario.contains("wrong-item")) {
                            a.addProperty("itemId", "foreign-item");
                        }
                        if (scenario.contains("wrong-thread")) {
                            a.addProperty("threadId", "foreign-thread");
                        }
                        write(obj("id", "approval", "method",
                                scenario.contains("permissions") ? "item/permissions/requestApproval"
                                        : scenario.contains("command") ? "item/commandExecution/requestApproval"
                                                : "item/fileChange/requestApproval",
                                "params", a));
                        if (scenario.contains("crash")) {
                            return;
                        }
                        if (scenario.contains("fatal")) {
                            var error = params();
                            error.addProperty("willRetry", false);
                            error.add("error", obj("message", "fatal test error"));
                            event("error", error);
                        }
                    } else {
                        var text = params();
                        text.addProperty("delta", "READ_ONLY");
                        event("item/agentMessage/delta", text);
                        event("turn/completed", obj("threadId", "t", "turn", obj("id", active, "status", "completed")));
                    }
                }
                default -> reply(id, obj());
                }
            }
        }
    }
}
