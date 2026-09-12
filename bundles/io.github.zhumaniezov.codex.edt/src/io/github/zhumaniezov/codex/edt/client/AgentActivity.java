package io.github.zhumaniezov.codex.edt.client;

import com.google.gson.*;
import java.util.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;

public final class AgentActivity {
    public record Snapshot(String thread, String turn, List<JsonObject> items, String diff, boolean complete) {
        public Snapshot {
            items = items.stream().map(JsonObject::deepCopy).toList();
        }
    }

    private final String thread, turn;
    private final LinkedHashMap<String, JsonObject> items = new LinkedHashMap<>();
    private String diff = "";
    private boolean complete;

    public AgentActivity(String thread, String turn) {
        this.thread = thread;
        this.turn = turn;
    }

    public void event(String method, JsonObject params) {
        if (complete || !thread.equals(string(params, "threadId")) || !turn.equals(string(params, "turnId"))) {
            return;
        }
        if (method.equals("turn/diff/updated")) {
            diff = string(params, "diff");
            return;
        }
        if (method.equals("item/started") || method.equals("item/completed")) {
            var item = params.getAsJsonObject("item");
            if (item != null && List.of("commandExecution", "fileChange").contains(string(item, "type"))) {
                String id = string(item, "id");
                if (!id.isBlank() && (items.containsKey(id) || items.size() < 300)) {
                    var copy = item.deepCopy();
                    if (string(copy, "aggregatedOutput").isEmpty() && items.containsKey(id)
                            && items.get(id).has("aggregatedOutput")) {
                        copy.add("aggregatedOutput", items.get(id).get("aggregatedOutput"));
                    }
                    String output = string(copy, "aggregatedOutput");
                    if (output.length() > 128 * 1024) {
                        copy.addProperty("aggregatedOutput", output.substring(output.length() - 128 * 1024));
                    }
                    items.put(id, copy);
                }
            }
        } else if (method.equals("item/commandExecution/outputDelta")) {
            var item = items.get(string(params, "itemId"));
            if (item != null && "inProgress".equals(string(item, "status"))) {
                String text = string(item, "aggregatedOutput") + string(params, "delta");
                item.addProperty("aggregatedOutput", text.substring(Math.max(0, text.length() - 128 * 1024)));
            }
        } else if (method.equals("item/fileChange/patchUpdated")) {
            var item = items.get(string(params, "itemId"));
            if (item != null && params.has("changes")) {
                item.add("changes", params.get("changes").deepCopy());
            }
        }
    }

    public void finish() {
        complete = true;
    }

    public Snapshot snapshot() {
        return new Snapshot(thread, turn, List.copyOf(items.values()), diff, complete);
    }
}
