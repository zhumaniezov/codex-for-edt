package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.semantic.MetadataPlan.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import java.util.*;
import com.google.gson.*;
import org.eclipse.emf.ecore.*;
import com._1c.g5.v8.bm.core.*;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.mcore.TypeItem;

/** Один исполнитель typed descriptors для разных семейств метаданных. */
final class MetadataOperationEngine {
    private final EdtServices services;
    private final EdtTypeService types;
    private final MetadataTypeRegistry registry = new MetadataTypeRegistry();
    private final java.util.function.Consumer<MdObject> boundary;

    MetadataOperationEngine(EdtServices services, EdtTypeService types, java.util.function.Consumer<MdObject> boundary) {
        this.services=services; this.types=types; this.boundary=boundary;
    }

    JsonObject apply(JsonObject op, Configuration config, IConfigurationProject project, IBmNamespace namespace, IBmPlatformTransaction tx) {
        var descriptor=registry.require(text(op,"kind"));
        String action=text(op,"operation"), name=text(op,"name");
        MdObject target=find(config,descriptor,name);
        if (action.equals("create")) {
            if(descriptor.name().equals("Interface")) throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","Interface: native resource mapping unavailable in EDT 2026.1.3");
            if(target!=null) return object("status","alreadyExists","object",EdtMetadataService.describe(target,true));
            target=services.factory().create(descriptor.type(),project);
            initialize(target,op);
            if (!descriptor.configurationRelation().isContainment()) tx.attachTopObject(namespace,(IBmObject)target,
                    services.names().generateStandaloneObjectFqn(descriptor.type(),name));
            members(config,descriptor.configurationRelation()).add(target);
            if(target instanceof BasicForm form) EdtFormService.generate(form,config,project,op.has("form")?op.getAsJsonObject("form"):object());
            fill(target,op,config,project,namespace,tx);
            attachResources(target,namespace,tx,new HashSet<>());
            boundary.accept(target);
            return object("status","created","object",EdtMetadataService.describe(target,true));
        }
        if(target==null) throw new EdtToolException("OBJECT_NOT_FOUND",descriptor.name()+": "+name);
        boundary.accept(target);
        if(op.has("path")) for(var step:op.getAsJsonArray("path")) {
            var path=step.getAsJsonObject();
            target=requiredChild(target,MetadataOperations.child(target.eClass(),text(path,"collection")),text(path,"name"));
        }
        switch(action) {
        case "update" -> fill(target,op,config,project,namespace,tx);
        case "addChild", "updateChild" -> {
            var relation=MetadataOperations.child(target.eClass(),text(op,"collection"));
            var input=op.getAsJsonObject("child");
            var found=members(target,relation).stream().filter(m->m.getName().equalsIgnoreCase(text(input,"name"))).findFirst();
            if(action.equals("addChild") && found.isPresent()) return object("status","alreadyExists","object",EdtMetadataService.describe(found.get(),true));
            if(action.equals("updateChild")) {
                if(found.isEmpty()) throw new EdtToolException("OBJECT_NOT_FOUND",text(input,"name"));
                fill(found.get(),input,config,project,namespace,tx);
            } else createChild(target,relation,input,config,project,namespace,tx);
        }
        case "removeChild" -> {
            var relation=MetadataOperations.child(target.eClass(),text(op,"collection"));
            MdObject child=requiredChild(target,relation,text(op,"childName"));
            if(!relation.isContainment() || child instanceof BasicForm || relation.getName().equals("templates"))
                throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","Removal needs a resource/reference adapter");
            ensureNoReferences(config,child);
            members(target,relation).remove(child);
        }
        case "addReference" -> {
            var ref=op.getAsJsonObject("reference");
            MdObject value=find(config,registry.require(text(ref,"kind")),text(ref,"name"));
            if(value==null) throw new EdtToolException("REFERENCE_NOT_FOUND",text(ref,"name"));
            @SuppressWarnings("unchecked") var content=(List<MdObject>)target.eGet(target.eClass().getEStructuralFeature("content"));
            if(!content.contains(value)) content.add(value);
        }
        default -> throw new EdtToolException("INVALID_PROPERTY",action);
        }
        attachResources(target,namespace,tx,new HashSet<>());
        return object("status","updated","object",EdtMetadataService.describe(target,true));
    }

    private void fill(MdObject target,JsonObject node,Configuration config,IConfigurationProject project,IBmNamespace namespace,IBmPlatformTransaction tx) {
        if(node.has("synonym")) target.getSynonym().put("ru",text(node,"synonym"));
        if(node.has("properties")) MetadataOperations.properties(target.eClass(),node.getAsJsonObject("properties")).forEach(target::eSet);
        if(node.has("type")) target.eSet(target.eClass().getEStructuralFeature("type"),types.createResolved(node.getAsJsonObject("type"),project.getVersion(),
                (kind,name)->resolveType(config,kind,name)));
        if(node.has("form")) EdtFormService.apply((BasicForm)target,node.getAsJsonObject("form"),input->types.createResolved(input,project.getVersion(),(kind,name)->resolveType(config,kind,name)));
        if(node.has("children")) for(var entry:node.getAsJsonObject("children").entrySet()) {
            var relation=MetadataOperations.child(target.eClass(),entry.getKey());
            for(var value:entry.getValue().getAsJsonArray()) {
                var input=value.getAsJsonObject();
                if(members(target,relation).stream().anyMatch(m->m.getName().equalsIgnoreCase(text(input,"name"))))
                    throw new EdtToolException("OBJECT_ALREADY_EXISTS",text(input,"name"));
                createChild(target,relation,input,config,project,namespace,tx);
            }
        }
    }

    private MdObject createChild(MdObject parent,EReference relation,JsonObject input,Configuration config,IConfigurationProject project,IBmNamespace namespace,IBmPlatformTransaction tx) {
        MdObject child=services.factory().create(relation.getEReferenceType(),parent,project.getVersion());
        initialize(child,input);
        if(!relation.isContainment()) throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","Non-containment child requires adapter: "+relation.getName());
        members(parent,relation).add(child);
        if(child instanceof BasicForm form) EdtFormService.generate(form,parent,project,input.has("form")?input.getAsJsonObject("form"):object());
        attachResources(child,namespace,tx,new HashSet<>());
        fill(child,input,config,project,namespace,tx);
        attachResources(child,namespace,tx,new HashSet<>());
        return child;
    }

    private TypeItem resolveType(Configuration config,String kind,String name) {
        MdObject target=find(config,registry.require(kind.substring(0,kind.length()-3)),name);
        if(target==null) throw new EdtToolException("REFERENCE_NOT_FOUND",kind+": "+name);
        var nativeType=com._1c.g5.v8.dt.metadata.mdclass.util.MdProducedTypesUtil.getProducedType(target,com._1c.g5.v8.dt.metadata.mdtype.MdTypePackage.Literals.MD_REF_TYPE);
        if(nativeType!=null)return nativeType;
        var produced=target.eClass().getEStructuralFeature("producedTypes");
        if(produced==null || !(target.eGet(produced) instanceof EObject values)) throw new EdtToolException("INVALID_TYPE",kind);
        var ref=values.eClass().getEStructuralFeature("refType");
        if(ref==null || !(values.eGet(ref) instanceof EObject value)) throw new EdtToolException("INVALID_TYPE",kind);
        var type=value.eClass().getEStructuralFeature("type");
        if(type==null || !(value.eGet(type) instanceof TypeItem item)) throw new EdtToolException("INVALID_TYPE",kind+": native type not available in this editing task");
        return item;
    }

    private void attachResources(EObject value,IBmNamespace namespace,IBmPlatformTransaction tx,Set<EObject> seen) {
        if(!seen.add(value)) return;
        for(var relation:value.eClass().getEAllReferences()) {
            if(relation.isDerived()) continue;
            if(relation.isContainment()) {
                if(relation.isMany()) for(var child:(Collection<?>)value.eGet(relation)) {if(child instanceof EObject object) attachResources(object,namespace,tx,seen);}
                else if(value.eGet(relation) instanceof EObject child) attachResources(child,namespace,tx,seen);
            } else if(!relation.isMany() && Set.of("Module","AbstractForm","AbstractRoleDescription").contains(relation.getEReferenceType().getName())
                    && value.eGet(relation) instanceof IBmObject child && child.bmIsTransient()) {
                String fqn=services.names().generateExternalPropertyFqn(value,relation);
                var existing=tx.getTopObjectByFqn(namespace,fqn);
                if(existing!=null && relation.getEReferenceType().getName().equals("Module")) value.eSet(relation,existing);
                else {tx.attachTopObject(namespace,child,fqn);attachResources((EObject)child,namespace,tx,seen);}
            }
        }
    }

    private void ensureNoReferences(Configuration configuration,MdObject removed) {
        for(var descriptor:registry.all()) for(var root:members(configuration,descriptor.configurationRelation())) {
            var objects=new ArrayList<EObject>(); objects.add(root); root.eAllContents().forEachRemaining(objects::add);
            for(var object:objects) for(var ref:object.eClass().getEAllReferences()) {
                if(ref.isContainment() || ref.isDerived()) continue;
                Object value=object.eGet(ref,false);
                if(value==removed || value instanceof Collection<?> values && values.contains(removed))
                    throw new EdtToolException("EDITING_CONFLICT","Child is referenced: "+removed.getName());
            }
        }
    }

    static MdObject find(Configuration config,MetadataTypeDescriptor descriptor,String name) {
        return members(config,descriptor.configurationRelation()).stream().filter(o->o.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }
    private static MdObject requiredChild(MdObject owner,EReference relation,String name) {
        return members(owner,relation).stream().filter(o->o.getName().equalsIgnoreCase(name)).findFirst().orElseThrow(()->new EdtToolException("OBJECT_NOT_FOUND",name));
    }
    @SuppressWarnings("unchecked") static List<MdObject> members(EObject owner,EReference reference) {return (List<MdObject>)owner.eGet(reference);}
    private static void initialize(MdObject object,JsonObject input) {object.setName(text(input,"name")); if(input.has("synonym")) object.getSynonym().put("ru",text(input,"synonym"));}
}
