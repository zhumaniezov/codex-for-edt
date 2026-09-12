package io.github.zhumaniezov.codex.edt.client;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonObject;

public final class SessionData {
    private SessionData() { }
    public enum State {
        DISCONNECTED("text026"), CONNECTING("text000"), READY("text027"),
        WORKING("text028"), STOPPING("text029"), STOPPED("text030"),
        AUTH_REQUIRED("text031"), ERROR("text025");
        private final String label;
        State(String label) { this.label = label; }
        public String label() { return tr(label); }
        public boolean running() { return this == WORKING || this == STOPPING; }
    }
    public record Reasoning(String value, String description) { }
    public record Model(String id, String displayName, boolean isDefault, String defaultEffort, List<Reasoning> efforts) {
        public Model { efforts = List.copyOf(efforts); }
        public String compatibleEffort(String previous) {
            return efforts.stream().anyMatch(e -> e.value().equals(previous)) ? previous
                : efforts.stream().filter(e -> e.value().equals(defaultEffort)).map(Reasoning::value).findFirst()
                    .orElse(efforts.isEmpty() ? "" : efforts.get(0).value());
        }
    }
    public record Account(String type, String email, String plan) {
        public static final Account NONE = new Account("", "", "");
        public String label() { return type.isEmpty() ? tr("text031") : email.isBlank() ? type : email; }
    }
    public record Snapshot(State state, String version, List<Model> models, String model, String effort,
            String threadId, String cwd, Account account, JsonObject edtTools) {
        public Snapshot { models = List.copyOf(models);edtTools=edtTools.deepCopy(); }
        public Snapshot(State state,String version,List<Model> models,String model,String effort,String threadId,String cwd,Account account) {this(state,version,models,model,effort,threadId,cwd,account,object("status","unavailable"));}
        @Override public JsonObject edtTools() {return edtTools.deepCopy();}
        public static final Snapshot EMPTY = new Snapshot(State.DISCONNECTED, "", List.of(), "", "", "", "", Account.NONE);
    }
    public record ThreadSummary(String id, String title, long updatedAt, String cwd) { }
    public record ThreadPage(List<ThreadSummary> threads, String cursor) {
        public ThreadPage { threads = List.copyOf(threads); }
    }
    public record Message(String role, String text) { }
    public record HistoryPage(List<Message> messages, String cursor) {
        public HistoryPage { messages = List.copyOf(messages); }
    }

    public static List<Model> models(List<JsonObject> values) throws IOException {
        var result = new ArrayList<Model>();
        for (var value : values) {
            if (bool(value, "hidden") || string(value, "model").isBlank()) { continue; }
            var efforts = new ArrayList<Reasoning>();
            if (value.has("supportedReasoningEfforts")) {
                value.getAsJsonArray("supportedReasoningEfforts").forEach(item -> {
                    var level = item.getAsJsonObject();
                    efforts.add(new Reasoning(string(level, "reasoningEffort"), string(level, "description")));
                });
            }
            String display = string(value, "displayName");
            result.add(new Model(string(value, "model"), display.isBlank() ? string(value, "model") : display,
                bool(value, "isDefault"), string(value, "defaultReasoningEffort"), efforts));
        }
        if (result.isEmpty()) { throw new IOException(tr("text072")); }
        return List.copyOf(result);
    }
    public static Model defaultModel(List<Model> values) {
        return values.stream().filter(Model::isDefault).findFirst().orElse(values.get(0));
    }
    public static Account account(JsonObject response) {
        if (!response.has("account") || !response.get("account").isJsonObject()) { return Account.NONE; }
        var account = response.getAsJsonObject("account");
        return new Account(string(account, "type"), string(account, "email"), string(account, "planType"));
    }
    public static ThreadPage threads(JsonObject response) {
        var result = new ArrayList<ThreadSummary>();
        for (var value : response.getAsJsonArray("data")) {
            var thread = value.getAsJsonObject();
            if (thread.has("parentThreadId") && !thread.get("parentThreadId").isJsonNull()) { continue; }
            String title = string(thread, "name");
            if (title.isBlank()) { title = string(thread, "preview").replaceAll("\\s+", " ").strip(); }
            if (title.isBlank()) { title = tr("text032"); }
            result.add(new ThreadSummary(string(thread, "id"), title.substring(0, Math.min(100, title.length())),
                thread.has("updatedAt") ? thread.get("updatedAt").getAsLong() : 0, string(thread, "cwd")));
        }
        return new ThreadPage(result, string(response, "nextCursor"));
    }
    public static HistoryPage history(JsonObject response) {
        var turns = new ArrayList<JsonObject>();
        response.getAsJsonArray("data").forEach(value -> turns.add(value.getAsJsonObject()));
        java.util.Collections.reverse(turns);
        var messages = new ArrayList<Message>();
        for (var turn : turns) {
            if (!turn.has("items")) { continue; }
            for (var value : turn.getAsJsonArray("items")) {
                var item = value.getAsJsonObject();
                if ("agentMessage".equals(string(item, "type"))) { messages.add(new Message("Codex", string(item, "text"))); }
                if ("userMessage".equals(string(item, "type"))) {
                    var text = new StringBuilder();
                    for (var input : item.getAsJsonArray("content")) {
                        if ("text".equals(string(input.getAsJsonObject(), "type"))) { text.append(string(input.getAsJsonObject(), "text")); }
                    }
                    // В истории composer показываем запрос без повторного разворачивания переданного IDE-контекста.
                    String prompt = text.toString();
                    String marker = "\n\n[Запрос пользователя]\n";
                    if (prompt.startsWith("[Контекст 1C:EDT]") && prompt.contains(marker)) {
                        prompt = prompt.substring(prompt.indexOf(marker) + marker.length());
                    }
                    messages.add(new Message(tr("text024"), prompt));
                }
            }
        }
        return new HistoryPage(messages, string(response, "nextCursor"));
    }
}
