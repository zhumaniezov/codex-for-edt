package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.string;
import com.google.gson.*;

public final class MetadataPresentation {
    private MetadataPresentation() {
    }
    public static String diagnostic(JsonObject value) {
        if(!"ready".equals(string(value,"status")))return tr("nativePlatformUnavailable");
        return "EDT " + string(value,"edtVersion") + " · " + tr("nativePlatformReady") + "\n"
            + tr("nativeTools") + ": " + value.get("toolCount") + " · PUBLIC: " + value.get("publicCapabilityCount")
            + " · EXPERIMENTAL: " + value.get("experimentalCapabilityCount") + " · " + tr("nativeInternalDisabled") + ": " + value.getAsJsonArray("disabledInternalCapabilities").size();
    }

    public static String plan(MetadataPlan plan) {
        var text = new StringBuilder();
        for (var entry : plan.json().getAsJsonArray("operations")) {
            var op = entry.getAsJsonObject();
            text.append(tr("semantic" + string(op, "operation"))).append(": ");
            if(op.has("kind")) text.append(string(op,"kind")).append('.');
            if(op.has("path") && op.get("path").isJsonPrimitive()) text.append(string(op,"path"));
            if (op.has("catalog")) {
                text.append(string(op, "catalog")).append(" / ");
            }
            if (op.has("tabularSection")) {
                text.append(string(op, "tabularSection")).append(" / ");
            }
            text.append(string(op, "name")).append('\n');
            if(op.has("path") && op.get("path").isJsonArray()) for(var step:op.getAsJsonArray("path")) text.append("  / ").append(string(step.getAsJsonObject(),"collection")).append(" / ").append(string(step.getAsJsonObject(),"name")).append('\n');
            if(op.has("collection"))text.append("  ").append(string(op,"collection")).append(" / ").append(string(op,"childName")).append('\n');
            if(op.has("child"))node(text,op.getAsJsonObject("child"),"    ");
            if(op.has("children"))children(text,op,"  ");
            if(op.has("reference"))text.append("  → ").append(string(op.getAsJsonObject("reference"),"kind")).append('.').append(string(op.getAsJsonObject("reference"),"name")).append('\n');
            if(op.has("form"))form(text,op.getAsJsonObject("form"),"  ");
            if(op.has("edits"))for(var edit:op.getAsJsonArray("edits")) {var change=edit.getAsJsonObject();text.append("  ").append(tr("semanticTextRange")).append(' ').append(change.get("offset")).append(" + ").append(change.get("length")).append('\n').append(string(change,"text")).append('\n');}
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

    private static void node(StringBuilder out,JsonObject node,String indent) {
        out.append(indent).append(string(node,"name"));if(node.has("type"))out.append(": ").append(type(node.getAsJsonObject("type")));out.append('\n');
        if(node.has("properties"))node.getAsJsonObject("properties").entrySet().forEach(e->out.append(indent).append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n'));
        children(out,node,indent+"  ");if(node.has("form"))form(out,node.getAsJsonObject("form"),indent+"  ");
    }
    private static void children(StringBuilder out,JsonObject node,String indent) {
        if(node.has("children"))node.getAsJsonObject("children").entrySet().forEach(e->{out.append(indent).append(e.getKey()).append(':').append('\n');for(var child:e.getValue().getAsJsonArray())node(out,child.getAsJsonObject(),indent+"  ");});
    }
    private static void form(StringBuilder out,JsonObject form,String indent) {
        out.append(indent).append(tr("semanticForm")).append(' ').append(string(form,"template")).append('\n');
        for(String group:new String[]{"attributes","commands","items"})if(form.has(group))for(var entry:form.getAsJsonArray(group)) {
            var item=entry.getAsJsonObject();node(out,item,indent+"  ");
            for(String field:new String[]{"handler","command","dataPath","parent"})if(item.has(field))out.append(indent).append("    ").append(field).append(" → ").append(string(item,field)).append('\n');
        }
    }

    private static String type(JsonObject type) {
        String kind = string(type, "kind");
        var result = new StringBuilder(tr("semanticType" + kind));
        if(kind.equals("Composite")) {for(var value:type.getAsJsonArray("types"))result.append(" · ").append(type(value.getAsJsonObject()));}
        for (String field : new String[] { "length", "precision", "scale", "fractions", "catalog", "name" }) {
            if (type.has(field)) {
                result.append(" · ").append(tr("semantic" + field)).append(": ").append(type.get(field).getAsString());
            }
        }
        return result.toString();
    }

    public static String result(JsonObject event) {
        if (event.has("state")) {
            String tool=string(event,"tool");
            String label=switch(tool){case "edt_bsl_read","edt_bsl_context"->tr("nativeReadBsl");case "edt_bsl_edit","edt_bsl_format"->tr("nativeEditBsl");case "edt_validate_project"->tr("semanticvalidateProject");case "edt_open_resource"->tr("nativeOpenEditor");default->tr("semantic" + string(event,"state"));};
            return label + " · " + string(event, "project");
        }
        var out = new StringBuilder(tr("semanticResult")).append(" · ").append(string(event, "project")).append('\n');
        var result = event.getAsJsonObject("result");
        if(result.has("path"))out.append(string(result,"path")).append('\n');
        for (var entry : result.has("results")?result.getAsJsonArray("results"):new JsonArray()) {
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
        if(result.has("problems"))out.append(tr("semanticDiagnostics")).append(": ").append(result.getAsJsonArray("problems").size());
        return out.toString();
    }
}
