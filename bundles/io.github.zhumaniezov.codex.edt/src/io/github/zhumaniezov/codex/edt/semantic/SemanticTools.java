package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import com.google.gson.*;
import java.util.List;

public final class SemanticTools {
    private SemanticTools() {
    }

    public static final List<String> READ = List.of("edt_get_project_context", "edt_get_configuration_info",
            "edt_list_metadata_objects", "edt_find_metadata_object", "edt_get_metadata_object");

    public static JsonObject list() {
        var tools = new JsonArray();
        for (String name : READ) {
            tools.add(object("name", name, "description",
                    "Read the current EDT configuration through its native object model. Editor not required. kind/name are exact case-insensitive metadata names; omit to list. offset paginates lists.",
                    "inputSchema",
                    object("type", "object", "properties",
                            object("kind", object("type", "string"), "name", object("type", "string"), "offset",
                                    object("type", "integer", "minimum", 0)),
                            "additionalProperties", false),
                    "annotations", object("readOnlyHint", true, "openWorldHint", false)));
        }
        var type = object("type", "object", "properties",
                object("kind",
                        object("type", "string", "enum", List.of("String", "Number", "Boolean", "Date", "CatalogRef")),
                        "length", object("type", "integer"), "precision", object("type", "integer"), "scale",
                        object("type", "integer"), "fractions",
                        object("type", "string", "enum", List.of("Date", "Time", "DateTime")), "catalog",
                        object("type", "string")),
                "required", List.of("kind"), "additionalProperties", false);
        var attribute = object("type", "object", "properties",
                object("name", object("type", "string"), "synonym", object("type", "string"), "type", type), "required",
                List.of("name", "type"), "additionalProperties", false);
        var attributes = object("type", "array", "items", attribute, "maxItems", 64);
        var section = object("type", "object", "properties",
                object("name", object("type", "string"), "synonym", object("type", "string"), "attributes", attributes),
                "required", List.of("name"), "additionalProperties", false);
        var operation = object("type", "object", "properties",
                object("operation",
                        object("type", "string", "enum",
                                List.of("createCatalog", "createCommonModule", "addAttribute", "addTabularSection",
                                        "setProperties")),
                        "name", object("type", "string"), "synonym", object("type", "string"), "catalog",
                        object("type", "string"), "tabularSection", object("type", "string"), "objectKind",
                        object("type", "string", "enum", List.of("Catalog", "CommonModule")), "properties",
                        object("type", "object"), "type", type, "attributes", attributes, "tabularSections",
                        object("type", "array", "items", section, "maxItems", 32)),
                "required", List.of("operation", "name"), "additionalProperties", false);
        tools.add(object("name", "edt_apply_metadata_plan", "description",
                "Atomically create/update EDT metadata using public native APIs. Never generate metadata XML yourself. Group related changes in one plan; the IDE asks approval when required. createCatalog supports attributes/tabularSections. createCommonModule properties: clientManagedApplication, clientOrdinaryApplication, server, externalConnection, serverCall, global, privileged. addAttribute requires catalog and optional tabularSection. setProperties requires objectKind. String requires length; Number precision/scale. Duplicates return alreadyExists without overwrite. Read-only rejects writes. Existing BSL file tools can edit Module.bsl after native creation succeeds.",
                "inputSchema",
                object("type", "object", "properties",
                        object("operations",
                                object("type", "array", "items", operation, "minItems", 1, "maxItems", 32)),
                        "required", List.of("operations"), "additionalProperties", false),
                "annotations", object("readOnlyHint", false, "destructiveHint", false, "idempotentHint", false,
                        "openWorldHint", false)));
        for (var value : tools) {
            var schema = value.getAsJsonObject().getAsJsonObject("inputSchema");
            schema.getAsJsonObject("properties").add("turnKey", object("type", "string", "description",
                    "Current IDE turnKey provided in this user turn; never reuse an earlier key."));
            if (!schema.has("required")) {
                schema.add("required", new JsonArray());
            }
            schema.getAsJsonArray("required").add("turnKey");
        }
        return object("tools", tools);
    }
}
