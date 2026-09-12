package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.semantic.MetadataPlan.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import java.util.*;
import java.util.function.Function;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import com.google.gson.*;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.form.generator.IFormGenerator;
import com._1c.g5.v8.dt.form.generator.IFormFieldGenerator;
import com._1c.g5.v8.dt.form.generator.FormType;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.form.service.item.*;
import com._1c.g5.v8.dt.form.service.attribute.FormAttributeManagementService;
import com._1c.g5.v8.dt.form.service.command.FormCommandManagementService;

/** Адаптер формы: генератор и management services EDT задают native invariants. */
public final class EdtFormService {
    private static <T>T service(Class<T> type) {
        var provider=IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(URI.createURI("codex.form"));
        T result=provider==null?null:provider.get(type);
        if(result==null) throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION",type.getSimpleName());return result;
    }
    public static void validate(JsonObject input) {
        MetadataOperations.keys(input,Set.of("template","attributes","commands","items"));
        if(input.has("template")) try {FormType.valueOf(text(input,"template"));} catch(IllegalArgumentException error) {throw new EdtToolException("INVALID_PROPERTY","form template");}
        for(String group:List.of("attributes","commands","items")) {
            if(array(input,group).size()>64)throw new EdtToolException("INVALID_PROPERTY","Form collection limit: 64");
            var names=new HashSet<String>();
            for(var value:array(input,group)) {
                var node=value.getAsJsonObject(); name(text(node,"name"));
                if(!names.add(text(node,"name").toLowerCase(Locale.ROOT))) throw new EdtToolException("OBJECT_ALREADY_EXISTS",text(node,"name"));
                MetadataOperations.keys(node,group.equals("attributes")?Set.of("name","title","type"):group.equals("commands")?Set.of("name","title","handler"):Set.of("name","title","kind","parent","command","dataPath"));
                if(group.equals("attributes")) validateType(node.getAsJsonObject("type"));
                if(group.equals("commands")) name(text(node,"handler"));
                if(group.equals("items") && !Set.of("button","field","table","group").contains(text(node,"kind"))) throw new EdtToolException("INVALID_PROPERTY","form item kind");
                if(node.has("dataPath")) for(String segment:text(node,"dataPath").split("\\.",-1)) name(segment);
                if(node.has("parent")) name(text(node,"parent"));
                if(node.has("command")) name(text(node,"command"));
            }
        }
    }
    public static void generate(BasicForm wrapper,MdObject owner,IV8Project project,JsonObject input) {
        var template=input.has("template")?FormType.valueOf(text(input,"template")):
                wrapper instanceof com._1c.g5.v8.dt.metadata.mdclass.CommonForm?FormType.GENERIC:FormType.OBJECT;
        var fields=service(IFormFieldGenerator.class).getFormGeneratorFields(owner,template,project.getScriptVariant(),project.getVersion());
        if(fields==null) throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","Form fields unavailable: "+template);
        String language=project.getDefaultLanguage()==null?"ru":project.getDefaultLanguage().getLanguageCode();
        var form=service(IFormGenerator.class).generateForm(owner,wrapper,template,project.getScriptVariant(),language,project.getVersion(),fields,1,project.getInterfaceCompatibilityMode());
        if(form==null) throw new EdtToolException("EDT_OPERATION_FAILED","Form generator returned null");
        wrapper.setForm(form);
    }
    public static void apply(BasicForm wrapper,JsonObject input,Function<JsonObject,TypeDescription> typeResolver) {
        if(!(wrapper.getForm() instanceof Form form)) throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","Managed form body unavailable");
        var commands=new TreeMap<String,FormCommand>(String.CASE_INSENSITIVE_ORDER);form.getFormCommands().forEach(c->commands.put(c.getName(),c));
        var items=new TreeMap<String,FormItem>(String.CASE_INSENSITIVE_ORDER);collect(form,items);
        var attributes=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);form.getAttributes().forEach(a->attributes.add(a.getName()));
        var commandNames=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);commandNames.addAll(commands.keySet());
        var itemNames=new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);itemNames.addAll(items.keySet());
        for(var value:array(input,"attributes")) if(!attributes.add(text(value.getAsJsonObject(),"name"))) throw new EdtToolException("OBJECT_ALREADY_EXISTS","form attribute");
        for(var value:array(input,"commands")) if(!commandNames.add(text(value.getAsJsonObject(),"name"))) throw new EdtToolException("OBJECT_ALREADY_EXISTS","form command");
        for(var value:array(input,"items")) {
            var node=value.getAsJsonObject();
            if(node.has("parent") && !itemNames.contains(text(node,"parent"))) throw new EdtToolException("OBJECT_NOT_FOUND","form parent");
            if(!itemNames.add(text(node,"name"))) throw new EdtToolException("OBJECT_ALREADY_EXISTS","form item");
            if(text(node,"kind").equals("button") && !commandNames.contains(text(node,"command"))) throw new EdtToolException("REFERENCE_NOT_FOUND","form command");
        }
        for(var value:array(input,"attributes")) {
            var node=value.getAsJsonObject();var attribute=FormFactory.eINSTANCE.createFormAttribute();
            attribute.setName(text(node,"name"));attribute.getTitle().put("ru",title(node));attribute.setValueType(typeResolver.apply(node.getAsJsonObject("type")));
            service(FormAttributeManagementService.class).addAttribute(form,attribute);
        }
        for(var value:array(input,"commands")) {
            var node=value.getAsJsonObject();var command=FormFactory.eINSTANCE.createFormCommand();command.setName(text(node,"name"));command.getTitle().put("ru",title(node));
            var handler=FormFactory.eINSTANCE.createCommandHandler();handler.setName(text(node,"handler"));
            var container=FormFactory.eINSTANCE.createFormCommandHandlerContainer();container.setHandler(handler);command.setAction(container);
            service(FormCommandManagementService.class).addCommand(form,command);commands.put(command.getName(),command);
        }
        var management=service(IFormItemManagementService.class);
        for(var value:array(input,"items")) {
            var node=value.getAsJsonObject();var parent=node.has("parent")?items.get(text(node,"parent")):form;
            if(!(parent instanceof FormItemContainer owner)) throw new EdtToolException("INVALID_PROPERTY","Item cannot contain children");
            var descriptor=new FormNewItemDescriptor(text(node,"name"),Map.of("ru",title(node)),false);
            DataPath path=null;
            if(node.has("dataPath")) {path=FormFactory.eINSTANCE.createDataPath();path.getSegments().addAll(Arrays.asList(text(node,"dataPath").split("\\.")));}
            FormItem item=switch(text(node,"kind")) {
                case "button" -> management.addButton(owner,commands.get(text(node,"command")),null,form,descriptor);
                case "group" -> management.addGroup(owner,form,descriptor);
                case "field" -> management.addField(owner,path,form,descriptor);
                case "table" -> management.addTable(owner,path,true,form,descriptor);
                default -> throw new EdtToolException("INVALID_PROPERTY","form item");
            };
            items.put(item.getName(),item);
        }
    }
    public static JsonObject describe(Form form) {
        var items=new LinkedHashMap<String,FormItem>();collect(form,items);
        return object("attributes",form.getAttributes().stream().map(a->object("name",a.getName(),"type",EdtTypeService.describe(a.getValueType()),"main",a.isMain())).toList(),
                "commands",form.getFormCommands().stream().map(c->object("name",c.getName(),"handler",c.getAction() instanceof FormCommandHandlerContainer a && a.getHandler()!=null?a.getHandler().getName():null)).toList(),
                "items",items.values().stream().map(i->object("name",i.getName(),"kind",i.eClass().getName(),"id",i.getId())).toList());
    }
    private static void collect(FormItemContainer parent,Map<String,FormItem> items) {for(var item:parent.getItems()){items.put(item.getName(),item);if(item instanceof FormItemContainer child)collect(child,items);}}
    private static String title(JsonObject node) {return node.has("title")?text(node,"title"):text(node,"name");}
    private static JsonArray array(JsonObject node,String field) {return node.has(field)?node.getAsJsonArray(field):new JsonArray();}
}
