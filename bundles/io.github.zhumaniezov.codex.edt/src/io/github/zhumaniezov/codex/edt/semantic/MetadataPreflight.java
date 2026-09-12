package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.semantic.MetadataPlan.text;
import java.util.*;
import org.eclipse.emf.ecore.EClass;
import com.google.gson.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;

/** Проверяет последовательность на отдельном описании структуры, не меняя BM. */
final class MetadataPreflight {
    private record Node(EClass type, Map<String,Map<String,Node>> children) { }
    private final MetadataTypeRegistry registry=new MetadataTypeRegistry();
    private final Map<String,Node> roots=new LinkedHashMap<>();

    static void validate(Configuration configuration,JsonArray operations) {
        var check=new MetadataPreflight();
        for(var descriptor:check.registry.all()) for(var root:MetadataOperationEngine.members(configuration,descriptor.configurationRelation()))
            check.roots.put(key(descriptor.name(),root.getName()),check.snapshot(root));
        for(var entry:operations) check.operation(entry.getAsJsonObject());
    }

    private Node snapshot(MdObject object) {
        var node=new Node(object.eClass(),new LinkedHashMap<>());
        MetadataTypeRegistry.describe(object.eClass(),null).children().forEach((name,relation)->{
            var items=new LinkedHashMap<String,Node>();
            for(var value:MetadataOperationEngine.members(object,relation)) items.put(lower(value.getName()),snapshot(value));
            node.children.put(name,items);
        });
        return node;
    }

    private void operation(JsonObject op) {
        String action=text(op,"operation"),kind=text(op,"kind"),name=text(op,"name");
        if(!MetadataOperations.ACTIONS.contains(action)) {
            if(action.equals("createCatalog") || action.equals("createCommonModule")) {
                kind=action.equals("createCatalog")?"Catalog":"CommonModule";
                roots.putIfAbsent(key(kind,name),new Node(registry.require(kind).type(),new LinkedHashMap<>()));
            }
            return;
        }
        var descriptor=registry.require(kind);
        Node target=roots.get(key(kind,name));
        if(action.equals("create")) {
            if(kind.equals("Interface"))fail("UNSUPPORTED_BY_EDT_VERSION","Interface resource mapping unavailable");
            if(op.has("path")) fail("INVALID_PROPERTY","create cannot target a child path");
            if(target!=null) return;
            target=new Node(descriptor.type(),new LinkedHashMap<>());
            roots.put(key(kind,name),target);
            fill(target,op); return;
        }
        if(target==null) fail("OBJECT_NOT_FOUND",kind+"."+name);
        if(op.has("path")) for(var value:op.getAsJsonArray("path")) {
            var step=value.getAsJsonObject();
            target=children(target,text(step,"collection")).get(lower(text(step,"name")));
            if(target==null) fail("OBJECT_NOT_FOUND",text(step,"name"));
        }
        switch(action) {
        case "update" -> {if(op.has("form") && op.getAsJsonObject("form").has("template"))fail("INVALID_PROPERTY","Existing form cannot be regenerated implicitly");fill(target,op);}
        case "addReference" -> {
            var ref=op.getAsJsonObject("reference");
            if(!roots.containsKey(key(text(ref,"kind"),text(ref,"name")))) fail("REFERENCE_NOT_FOUND",ref.toString());
        }
        case "addChild","updateChild" -> {
            var child=op.getAsJsonObject("child"); String collection=text(op,"collection");
            var items=children(target,collection); var node=items.get(lower(text(child,"name")));
            if(action.equals("addChild") && node!=null) return;
            if(action.equals("updateChild") && node==null) fail("OBJECT_NOT_FOUND",text(child,"name"));
            if(node==null) {node=new Node(MetadataOperations.child(target.type,collection).getEReferenceType(),new LinkedHashMap<>());items.put(lower(text(child,"name")),node);}
            fill(node,child);
        }
        case "removeChild" -> {
            String collection=text(op,"collection");
            var relation=MetadataOperations.child(target.type,collection);
            if(!relation.isContainment() || MdClassPackage.Literals.BASIC_FORM.isSuperTypeOf(relation.getEReferenceType()) || collection.equals("templates"))
                fail("UNSUPPORTED_BY_EDT_VERSION","Removal requires a dedicated reference/resource adapter");
            if(children(target,collection).remove(lower(text(op,"childName")))==null) fail("OBJECT_NOT_FOUND",text(op,"childName"));
        }
        default -> fail("INVALID_PROPERTY",action);
        }
    }

    private void fill(Node node,JsonObject input) {
        if(input.has("type")) referenceType(input.getAsJsonObject("type"));
        if(input.has("children")) for(var group:input.getAsJsonObject("children").entrySet()) {
            var relation=MetadataOperations.child(node.type,group.getKey());
            if(!relation.isContainment()) fail("UNSUPPORTED_BY_EDT_VERSION","Non-containment child: "+group.getKey());
            var items=children(node,group.getKey());
            for(var value:group.getValue().getAsJsonArray()) {
                var child=value.getAsJsonObject(); String name=text(child,"name");
                if(items.containsKey(lower(name))) fail("OBJECT_ALREADY_EXISTS",name);
                var next=new Node(relation.getEReferenceType(),new LinkedHashMap<>());
                items.put(lower(name),next);fill(next,child);
            }
        }
    }
    private void referenceType(JsonObject input) {
        String kind=text(input,"kind");
        if(kind.equals("Composite")) {for(var value:input.getAsJsonArray("types")) referenceType(value.getAsJsonObject());return;}
        if(kind.endsWith("Ref")) {
            String name=text(input,kind.equals("CatalogRef")?"catalog":"name");
            if(!roots.containsKey(key(kind.substring(0,kind.length()-3),name))) fail("REFERENCE_NOT_FOUND",kind+"."+name);
        }
    }
    private static Map<String,Node> children(Node node,String collection) {return node.children.computeIfAbsent(collection,k->new LinkedHashMap<>());}
    private static String key(String kind,String name) {return lower(kind+"."+name);}
    private static String lower(String text) {return text.toLowerCase(Locale.ROOT);}
    private static void fail(String code,String detail) {throw new EdtToolException(code,detail);}
}
