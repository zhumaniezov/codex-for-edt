package io.github.zhumaniezov.codex.edt.semantic;

import com.google.gson.*;
import java.util.Set;

/** Собственный контракт инструментов EDT; это не DTO протокола Codex. */
public final class MetadataPlan {
    private final JsonObject value;

    public MetadataPlan(JsonObject value) {
        this.value = value.deepCopy();
        requireKeys(value, Set.of("operations"));
        if (!value.has("operations") || !value.get("operations").isJsonArray()
                || value.getAsJsonArray("operations").isEmpty() || value.getAsJsonArray("operations").size() > 32) {
            throw new IllegalArgumentException("operations: 1..32");
        }
        for (var entry : value.getAsJsonArray("operations")) {
            var op = entry.getAsJsonObject();
            if(Set.of("bslEdit","bslFormat").contains(text(op,"operation"))) {
                if(value.getAsJsonArray("operations").size()!=1) throw new EdtToolException("INVALID_PROPERTY","Document edit must be a separate plan");
                EdtBslService.validateEdit(op);continue;
            }
            if(text(op,"operation").equals("validateProject")) {
                if(value.getAsJsonArray("operations").size()!=1) throw new EdtToolException("INVALID_PROPERTY","Validation must be a separate plan");
                requireKeys(op,Set.of("operation"));continue;
            }
            if (MetadataOperations.ACTIONS.contains(text(op, "operation"))) {
                MetadataOperations.validate(op);
                continue;
            }
            requireKeys(op, Set.of("operation", "name", "objectKind", "catalog", "tabularSection", "synonym",
                    "properties", "attributes", "tabularSections", "type"));
            String action = text(op, "operation");
            if (!Set.of("createCatalog", "createCommonModule", "addAttribute", "addTabularSection", "setProperties")
                    .contains(action)) {
                throw new IllegalArgumentException("operation: " + action);
            }
            name(text(op, "name"));
            if (op.has("catalog")) {
                name(text(op, "catalog"));
            }
            if (op.has("tabularSection")) {
                name(text(op, "tabularSection"));
            }
            if (op.has("synonym") && text(op, "synonym").length() > 1024) {
                throw new IllegalArgumentException("synonym: 1024");
            }
            if (action.equals("addAttribute")) {
                validateType(op.getAsJsonObject("type"));
            }
            if (action.equals("addAttribute") || action.equals("addTabularSection")) {
                name(text(op, "catalog"));
            }
            if (action.equals("setProperties") && !Set.of("Catalog", "CommonModule").contains(text(op, "objectKind"))) {
                throw new IllegalArgumentException("objectKind");
            }
            validateProperties(op,
                    action.equals("createCommonModule") || text(op, "objectKind").equals("CommonModule"));
            attributes(op);
            if (op.has("tabularSections")) {
                if (op.getAsJsonArray("tabularSections").size() > 32) {
                    throw new IllegalArgumentException("tabularSections: 32");
                }
                for (var section : op.getAsJsonArray("tabularSections")) {
                    var tabular = section.getAsJsonObject();
                    requireKeys(tabular, Set.of("name", "synonym", "attributes"));
                    name(text(tabular, "name"));
                    attributes(tabular);
                }
            }
        }
    }

    private static void attributes(JsonObject parent) {
        if (!parent.has("attributes")) {
            return;
        }
        if (parent.getAsJsonArray("attributes").size() > 64) {
            throw new IllegalArgumentException("attributes: 64");
        }
        for (var value : parent.getAsJsonArray("attributes")) {
            var attribute = value.getAsJsonObject();
            requireKeys(attribute, Set.of("name", "synonym", "type"));
            name(text(attribute, "name"));
            validateType(attribute.getAsJsonObject("type"));
        }
    }

    private static void validateProperties(JsonObject op, boolean module) {
        if (!op.has("properties")) {
            return;
        }
        Set<String> booleans = module ? Set.of("clientManagedApplication", "clientOrdinaryApplication", "server",
                "externalConnection", "serverCall", "global", "privileged")
                : Set.of("hierarchical", "autonumbering", "checkUnique");
        for (var entry : op.getAsJsonObject("properties").entrySet()) {
            if (booleans.contains(entry.getKey())) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException(entry.getKey() + ": boolean");
                }
            } else if (!module && Set.of("codeLength", "descriptionLength").contains(entry.getKey())) {
                range(op.getAsJsonObject("properties"), entry.getKey(), 0,
                        entry.getKey().equals("codeLength") ? 9 : 150);
            } else {
                throw new IllegalArgumentException("Unsupported property: " + entry.getKey());
            }
        }
    }

    public static void validateType(JsonObject type) {
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
        requireKeys(type, Set.of("kind", "length", "precision", "scale", "fractions", "catalog", "name", "types"));
        switch (text(type, "kind")) {
        case "String" -> range(type, "length", 0, 1024);
        case "Number" -> {
            int precision = range(type, "precision", 1, 38);
            range(type, "scale", 0, precision);
        }
        case "Boolean" -> {
        }
        case "Date" -> {
            if (type.has("fractions") && !Set.of("Date", "Time", "DateTime").contains(text(type, "fractions"))) {
                throw new IllegalArgumentException("fractions");
            }
        }
        case "CatalogRef" -> name(text(type, "catalog"));
        case "DocumentRef", "EnumRef", "ChartOfCharacteristicTypesRef", "ChartOfAccountsRef",
                "ChartOfCalculationTypesRef", "ExchangePlanRef", "BusinessProcessRef", "TaskRef" -> name(text(type, "name"));
        case "Composite" -> {
            if (!type.has("types") || !type.get("types").isJsonArray() || type.getAsJsonArray("types").isEmpty()
                    || type.getAsJsonArray("types").size() > 16) throw new EdtToolException("INVALID_TYPE", "composite types: 1..16");
            var primitives = new java.util.HashSet<String>();
            for (var item : type.getAsJsonArray("types")) {
                var member = item.getAsJsonObject(); String kind = text(member, "kind");
                if (kind.equals("Composite") || (!kind.endsWith("Ref") && !primitives.add(kind)))
                    throw new EdtToolException("INVALID_TYPE", "Repeated/nested composite member");
                validateType(member);
            }
        }
        default -> throw new IllegalArgumentException("type.kind");
        }
    }

    private static int range(JsonObject object, String key, int min, int max) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException(key);
        }
        int value;
        try {
            value = object.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(key);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + ": " + min + ".." + max);
        }
        return value;
    }

    public static String name(String value) {
        if (!value.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,79}")) {
            throw new IllegalArgumentException("name: " + value);
        }
        return value;
    }

    public static String text(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private static void requireKeys(JsonObject object, Set<String> keys) {
        if (!keys.containsAll(object.keySet())) {
            throw new IllegalArgumentException("Unsupported fields: " + object.keySet());
        }
    }

    public JsonObject json() {
        return value.deepCopy();
    }
}
