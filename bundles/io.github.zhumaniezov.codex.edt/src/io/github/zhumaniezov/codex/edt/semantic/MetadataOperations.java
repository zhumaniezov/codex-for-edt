package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.semantic.MetadataPlan.*;
import java.util.*;
import com.google.gson.*;
import org.eclipse.emf.ecore.*;

/** Валидация descriptor-based плана до обращения к изменяемой модели. */
public final class MetadataOperations {
    public static final Set<String> ACTIONS = Set.of("create", "update", "addChild", "updateChild", "removeChild", "addReference");
    private MetadataOperations() { }

    public static void validate(JsonObject op) {
        keys(op, Set.of("operation", "kind", "name", "synonym", "properties", "type", "children", "form", "path", "collection", "child", "childName", "reference"));
        var descriptor = new MetadataTypeRegistry().require(text(op, "kind"));
        name(text(op,"name"));
        EClass owner = descriptor.type();
        if (op.has("path")) {
            if (op.getAsJsonArray("path").size()>8) fail("INVALID_PROPERTY","path depth");
            for (var entry:op.getAsJsonArray("path")) {
                var step=entry.getAsJsonObject(); keys(step,Set.of("collection","name")); name(text(step,"name"));
                owner=child(owner,text(step,"collection")).getEReferenceType();
            }
        }
        switch(text(op,"operation")) {
        case "create", "update" -> validateNode(owner,op,0);
        case "addChild", "updateChild" -> {
            var relation=child(owner,text(op,"collection"));
            if(!op.has("child")) fail("INVALID_PROPERTY","child required");
            validateNode(relation.getEReferenceType(),op.getAsJsonObject("child"),1);
        }
        case "removeChild" -> { child(owner,text(op,"collection")); name(text(op,"childName")); }
        case "addReference" -> {
            if (!owner.getName().equals("Subsystem") || !text(op,"collection").equals("content")) fail("INVALID_PROPERTY","reference relation");
            var reference=op.getAsJsonObject("reference"); keys(reference,Set.of("kind","name"));
            new MetadataTypeRegistry().require(text(reference,"kind")); name(text(reference,"name"));
        }
        default -> fail("INVALID_PROPERTY","operation");
        }
    }

    public static EReference child(EClass owner, String name) {
        var relation=MetadataTypeRegistry.describe(owner,null).children().get(name);
        if(relation==null || relation.getEReferenceType().isAbstract()) fail("INVALID_PROPERTY","child relation: "+owner.getName()+"."+name);
        return relation;
    }

    private static void validateNode(EClass type,JsonObject node,int depth) {
        if(depth>8) fail("INVALID_PROPERTY","children depth: 8");
        name(text(node,"name"));
        if(depth>0) keys(node,Set.of("name","synonym","properties","type","children","form"));
        if(node.has("form")) {
            if(!com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.BASIC_FORM.isSuperTypeOf(type)) fail("INVALID_PROPERTY","form body owner");
            EdtFormService.validate(node.getAsJsonObject("form"));
        }
        if(node.has("synonym") && text(node,"synonym").length()>1024) fail("INVALID_PROPERTY","synonym length");
        if(node.has("properties")) properties(type,node.getAsJsonObject("properties"));
        var typeFeature=type.getEStructuralFeature("type");
        if(node.has("type")) {
            if(typeFeature==null || !typeFeature.getEType().getName().equals("TypeDescription")) fail("INVALID_TYPE",type.getName());
            validateType(node.getAsJsonObject("type"));
        }
        if(node.has("children")) {
            var groups=node.getAsJsonObject("children"); int total=0;
            for(var entry:groups.entrySet()) {
                var relation=child(type,entry.getKey());
                var names=new HashSet<String>();
                for(var element:entry.getValue().getAsJsonArray()) {
                    if(++total>64) fail("INVALID_PROPERTY","children: 64");
                    var value=element.getAsJsonObject();
                    if(!names.add(text(value,"name").toLowerCase(Locale.ROOT))) fail("OBJECT_ALREADY_EXISTS","duplicate child: "+text(value,"name"));
                    validateNode(relation.getEReferenceType(),value,depth+1);
                }
            }
        }
    }

    public static Map<EAttribute,Object> properties(EClass type,JsonObject input) {
        var result=new LinkedHashMap<EAttribute,Object>();
        var allowed=MetadataTypeRegistry.describe(type,null).properties();
        for(var entry:input.entrySet()) {
            var f=allowed.get(entry.getKey()); var v=entry.getValue();
            if(f==null || !v.isJsonPrimitive()) fail("INVALID_PROPERTY",type.getName()+"."+entry.getKey());
            var primitive=v.getAsJsonPrimitive(); Object converted;
            var data=f.getEAttributeType();
            if(data instanceof EEnum e) {
                if(!primitive.isString()) fail("INVALID_PROPERTY",f.getName());
                var literal=e.getEEnumLiteralByLiteral(v.getAsString());
                if(literal==null) fail("INVALID_PROPERTY","enum "+f.getName()+": "+v.getAsString());
                converted=literal.getInstance();
            } else if(data.getInstanceClass()==boolean.class || data.getInstanceClass()==Boolean.class) {
                if(!primitive.isBoolean()) fail("INVALID_PROPERTY",f.getName()); converted=v.getAsBoolean();
            } else if(data.getInstanceClass()==String.class) {
                if(!primitive.isString() || v.getAsString().length()>4096) fail("INVALID_PROPERTY",f.getName()); converted=v.getAsString();
            } else if(data.getInstanceClass()==int.class || data.getInstanceClass()==Integer.class || data.getInstanceClass()==long.class || data.getInstanceClass()==Long.class) {
                if(!primitive.isNumber()) fail("INVALID_PROPERTY",f.getName());
                long number; try {number=v.getAsBigDecimal().longValueExact();}catch(ArithmeticException error){throw new EdtToolException("INVALID_PROPERTY",f.getName());}
                if(number<0 || number>Integer.MAX_VALUE) fail("INVALID_PROPERTY",f.getName());
                if(Set.of("codeLength","numberLength").contains(f.getName()) && number>50) fail("INVALID_PROPERTY",f.getName());
                if(f.getName().equals("descriptionLength") && number>150) fail("INVALID_PROPERTY",f.getName());
                converted=(data.getInstanceClass()==int.class || data.getInstanceClass()==Integer.class) ? (Object)(int)number : number;
            } else throw new EdtToolException("INVALID_PROPERTY","unsupported property type: "+f.getName());
            result.put(f,converted);
        }
        return result;
    }

    static void keys(JsonObject value,Set<String> allowed) {
        if(value==null || !allowed.containsAll(value.keySet())) fail("INVALID_PROPERTY","Unknown fields");
    }
    static void fail(String code,String detail) { throw new EdtToolException(code,detail); }
}
