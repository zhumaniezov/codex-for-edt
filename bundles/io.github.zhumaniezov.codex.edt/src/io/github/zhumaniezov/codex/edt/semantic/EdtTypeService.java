package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.semantic.MetadataPlan.text;
import com.google.gson.JsonObject;
import com._1c.g5.v8.dt.mcore.*;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.platform.scoping.IPlatformScopeProvider;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import java.util.function.Function;

public final class EdtTypeService {
    public TypeDescription create(JsonObject input, Version version, Function<String, TypeItem> catalogReference) {
        return createResolved(input, version, (kind, name) -> {
            if (!kind.equals("CatalogRef")) throw new EdtToolException("INVALID_TYPE", kind);
            return catalogReference.apply(name);
        });
    }

    public TypeDescription createResolved(JsonObject input, Version version,
            java.util.function.BiFunction<String, String, TypeItem> reference) {
        MetadataPlan.validateType(input);
        var factory = McoreFactory.eINSTANCE;
        var result = factory.createTypeDescription();
        String kind = text(input, "kind");
        if (kind.equals("Composite")) {
            for (var member : input.getAsJsonArray("types")) {
                var value = createResolved(member.getAsJsonObject(), version, reference);
                result.getTypes().addAll(value.getTypes());
                if (value.getStringQualifiers() != null) result.setStringQualifiers(value.getStringQualifiers());
                if (value.getNumberQualifiers() != null) result.setNumberQualifiers(value.getNumberQualifiers());
                if (value.getDateQualifiers() != null) result.setDateQualifiers(value.getDateQualifiers());
            }
            return result;
        }
        TypeItem item;
        if (kind.endsWith("Ref")) {
            item = reference.apply(kind, text(input, kind.equals("CatalogRef") ? "catalog" : "name"));
        } else {
            var provider = IResourceServiceProvider.Registry.INSTANCE
                    .getResourceServiceProvider(URI.createURI("model.mdo"));
            if (provider == null) {
                throw new IllegalStateException("EDT metadata language service unavailable");
            }
            var scope = provider.get(IPlatformScopeProvider.class)
                    .getScope(McorePackage.Literals.TYPE_DESCRIPTION__TYPES, null, version);
            var description = scope.getSingleElement(QualifiedName.create(kind));
            if (description == null || !(description.getEObjectOrProxy() instanceof TypeItem type)) {
                throw new IllegalArgumentException("Unknown platform type: " + kind);
            }
            item = type;
        }
        if (item == null) {
            throw new IllegalArgumentException("Type is unavailable: " + kind);
        }
        result.getTypes().add(item);
        switch (kind) {
        case "String" -> {
            var q = factory.createStringQualifiers();
            q.setLength(input.get("length").getAsInt());
            q.setFixed(false);
            result.setStringQualifiers(q);
        }
        case "Number" -> {
            var q = factory.createNumberQualifiers();
            q.setPrecision(input.get("precision").getAsInt());
            q.setScale(input.get("scale").getAsInt());
            result.setNumberQualifiers(q);
        }
        case "Date" -> {
            var q = factory.createDateQualifiers();
            q.setDateFractions(switch (text(input, "fractions")) {
            case "Date" -> DateFractions.DATE;
            case "Time" -> DateFractions.TIME;
            default -> DateFractions.DATE_TIME;
            });
            result.setDateQualifiers(q);
        }
        default -> {
        }
        }
        return result;
    }

    public static JsonObject describe(TypeDescription value) {
        var result = new JsonObject();
        if (value == null) {
            return result;
        }
        var names = new com.google.gson.JsonArray();
        value.getTypes().forEach(t -> names.add(McoreUtil.getTypeName(t)));
        result.add("types", names);
        if (value.getStringQualifiers() != null) {
            result.addProperty("length", value.getStringQualifiers().getLength());
        }
        if (value.getNumberQualifiers() != null) {
            result.addProperty("precision", value.getNumberQualifiers().getPrecision());
            result.addProperty("scale", value.getNumberQualifiers().getScale());
        }
        if (value.getDateQualifiers() != null) {
            result.addProperty("fractions", value.getDateQualifiers().getDateFractions().getLiteral());
        }
        return result;
    }
}
