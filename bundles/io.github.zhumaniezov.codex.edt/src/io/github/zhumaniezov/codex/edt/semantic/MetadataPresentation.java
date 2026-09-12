package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.string;
import com.google.gson.*;

public final class MetadataPresentation {
    private MetadataPresentation() {
    }

    public static String plan(MetadataPlan plan) {
        var text = new StringBuilder();
        for (var entry : plan.json().getAsJsonArray("operations")) {
            var op = entry.getAsJsonObject();
            text.append(tr("semantic" + string(op, "operation"))).append(": ");
            if (op.has("catalog")) {
                text.append(string(op, "catalog")).append(" / ");
            }
            if (op.has("tabularSection")) {
                text.append(string(op, "tabularSection")).append(" / ");
            }
            text.append(string(op, "name")).append('\n');
            if (op.has("synonym")) {
                text.append("  ").append(tr("semanticSynonym")).append(": ").append(string(op, "synonym")).append('\n');
            }
            if (op.has("properties")) {
                op.getAsJsonObject("properties").entrySet().forEach(
                        p -> text.append("  ").append(p.getKey()).append(" = ").append(p.getValue()).append('\n'));
            }
            if (op.has("type")) {
                text.append("  ").append(type(op.getAsJsonObject("type"))).append('\n');
            }
            attributes(text, op, "  ");
            if (op.has("tabularSections")) {
                for (var s : op.getAsJsonArray("tabularSections")) {
                    var section = s.getAsJsonObject();
                    text.append("  ").append(tr("semanticSection")).append(": ").append(string(section, "name"))
                            .append('\n');
                    attributes(text, section, "    ");
                }
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static void attributes(StringBuilder out, JsonObject op, String indent) {
        if (!op.has("attributes")) {
            return;
        }
        for (var a : op.getAsJsonArray("attributes")) {
            var attribute = a.getAsJsonObject();
            out.append(indent).append(string(attribute, "name")).append(": ")
                    .append(type(attribute.getAsJsonObject("type"))).append('\n');
        }
    }

    private static String type(JsonObject type) {
        String kind = string(type, "kind");
        var result = new StringBuilder(tr("semanticType" + kind));
        for (String field : new String[] { "length", "precision", "scale", "fractions", "catalog" }) {
            if (type.has(field)) {
                result.append(" · ").append(tr("semantic" + field)).append(": ").append(type.get(field).getAsString());
            }
        }
        return result.toString();
    }

    public static String result(JsonObject event) {
        if (event.has("state")) {
            return tr("semantic" + string(event, "state")) + " · " + string(event, "project");
        }
        var out = new StringBuilder(tr("semanticResult")).append(" · ").append(string(event, "project")).append('\n');
        var result = event.getAsJsonObject("result");
        for (var entry : result.getAsJsonArray("results")) {
            var value = entry.getAsJsonObject();
            out.append(tr("semantic" + string(value, "status"))).append(": ");
            if (value.has("object")) {
                var object = value.getAsJsonObject("object");
                out.append(string(object, "kind")).append('.').append(string(object, "name"));
            }
            out.append('\n');
        }
        if (result.has("diagnostics")) {
            out.append(tr("semanticDiagnostics")).append(": ").append(result.getAsJsonArray("diagnostics").size());
        }
        return out.toString();
    }
}
