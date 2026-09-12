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
        MetadataPlan.validateType(input);
        var factory = McoreFactory.eINSTANCE;
        var result = factory.createTypeDescription();
        String kind = text(input, "kind");
        TypeItem item;
        if (kind.equals("CatalogRef")) {
            item = catalogReference.apply(text(input, "catalog"));
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
