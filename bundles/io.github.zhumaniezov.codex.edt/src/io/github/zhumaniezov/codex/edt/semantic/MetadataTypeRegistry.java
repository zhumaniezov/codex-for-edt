package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import java.util.*;
import com.google.gson.*;
import org.eclipse.emf.ecore.*;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

public final class MetadataTypeRegistry {
    private static final Set<String> NON_OWNING = Set.of("defaultRoles", "standaloneConfigurationRestrictionRoles",
            "content", "additionalFullTextSearchDictionaries");
    private static final Set<String> PROPERTY_NAMES = Set.of("comment", "languageCode", "hierarchical", "codeLength",
            "descriptionLength", "autonumbering", "checkUnique", "clientManagedApplication", "clientOrdinaryApplication",
            "server", "externalConnection", "serverCall", "global", "privileged", "numberLength", "numberType",
            "numberPeriodicity", "periodicity", "writeMode", "registerType", "rootURL", "template", "httpMethod",
            "handler", "useStandardCommands", "includeHelpInContents", "enableTotalsSplitting", "dataHistory",
            "mainFilter", "master", "denyIncompleteValues", "index", "useInTotals", "useInSubordinateNodes",
            "hierarchyType", "codeType", "numberAllowedLength", "codeAllowedLength", "quickChoice",
            "fillChecking", "choiceMode", "choiceFoldersAndItems", "sessionMaxAge", "reuseSessions");
    private static final Set<String> CHILD_NAMES = Set.of("attributes", "tabularSections", "dimensions", "resources",
            "forms", "commands", "templates", "enumValues", "values", "urlTemplates", "methods", "operations",
            "parameters", "recalculations", "accountingFlags", "extDimensionAccountingFlags", "subsystems");
    private final Map<String, MetadataTypeDescriptor> types;

    public MetadataTypeRegistry() {
        var found = new LinkedHashMap<String, MetadataTypeDescriptor>();
        for (var relation : MdClassPackage.Literals.CONFIGURATION.getEAllReferences()) {
            EClass type = relation.getEReferenceType();
            if (!relation.isMany() || relation.isDerived() || type.isAbstract() || NON_OWNING.contains(relation.getName())
                    || !MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(type)) continue;
            found.put(type.getName(), describe(type, relation));
        }
        types = Collections.unmodifiableMap(found);
    }

    public Collection<MetadataTypeDescriptor> all() { return types.values(); }

    public MetadataTypeDescriptor require(String name) {
        var value = types.get(name);
        if (value == null) throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION", "metadata type: " + name);
        return value;
    }

    public static MetadataTypeDescriptor describe(EClass type, EReference owner) {
        var props = new LinkedHashMap<String, EAttribute>();
        for (var f : type.getEAllAttributes()) {
            if (f.isChangeable() && !f.isDerived() && !f.isMany() && PROPERTY_NAMES.contains(f.getName())) props.put(f.getName(), f);
        }
        var children = new LinkedHashMap<String, EReference>();
        var modules = new LinkedHashMap<String, EReference>();
        for (var f : type.getEAllReferences()) {
            if (f.isDerived() || !f.isChangeable()) continue;
            if (!f.isMany() && f.getEReferenceType().getName().equals("Module")) modules.put(f.getName(), f);
            if (f.isMany() && CHILD_NAMES.contains(f.getName()) && MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(f.getEReferenceType())) children.put(f.getName(), f);
        }
        return new MetadataTypeDescriptor(type.getName(), type, owner, props, children, modules);
    }

    public JsonObject catalog() {
        var values = new JsonArray();
        all().forEach(d -> values.add(json(d)));
        return object("types", values, "count", values.size());
    }

    public static JsonObject json(MetadataTypeDescriptor d) {
        var properties = new JsonObject();
        d.properties().forEach((name, f) -> {
            var value = object("type", f.getEType().getName());
            if (f.getEType() instanceof EEnum e) value.add("values", new Gson().toJsonTree(e.getELiterals().stream().map(EEnumLiteral::getLiteral).toList()));
            properties.add(name, value);
        });
        var children = new JsonObject();
        d.children().forEach((name, f) -> children.add(name, object("type", f.getEReferenceType().getName(),
                "containment", f.isContainment(), "typed", f.getEReferenceType().getEStructuralFeature("type") != null,
                "form", MdClassPackage.Literals.BASIC_FORM.isSuperTypeOf(f.getEReferenceType()))));
        return object("name", d.name(), "relation", d.configurationRelation() == null ? null : d.configurationRelation().getName(),
                "createSupported",!d.name().equals("Interface"),"unavailableReason",d.name().equals("Interface")?"Native resource mapping unavailable in EDT 2026.1.3":null,
                "properties", properties, "children", children, "modules", d.modules().keySet(),
                "typeBearing", d.type().getEStructuralFeature("type") != null);
    }
}
