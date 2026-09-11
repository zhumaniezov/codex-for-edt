package io.github.zhumaniezov.codex.edt.settings;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import com.google.gson.JsonObject;
import io.github.zhumaniezov.codex.edt.client.CodexClient;

public final class SkillsService {
    private final CodexClient client;
    public SkillsService(CodexClient client) { this.client = client; }
    public record Skill(String name, boolean enabled, String description, String path, String scope) { }
    public CompletionStage<List<Skill>> list() {
        return list(client.snapshot().cwd());
    }
    public CompletionStage<List<Skill>> list(String cwd) {
        return client.manage(ManagementRequest.SKILLS_LIST, object("cwds", cwd.isBlank() ? List.of() : List.of(cwd), "forceReload", false)).thenApply(SkillsService::map);
    }
    public static List<Skill> map(JsonObject response) {
        var result = new ArrayList<Skill>();
        for (var entry : response.getAsJsonArray("data")) {
            for (var element : entry.getAsJsonObject().getAsJsonArray("skills")) {
                var skill = element.getAsJsonObject();
                result.add(new Skill(string(skill, "name"), bool(skill, "enabled"), string(skill, "description"), string(skill, "path"), string(skill, "scope")));
            }
        }
        return List.copyOf(result);
    }
}
