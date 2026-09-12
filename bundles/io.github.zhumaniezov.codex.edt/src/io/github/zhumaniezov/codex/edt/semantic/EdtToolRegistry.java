package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import java.util.*;
import com.google.gson.*;

/** Каталог соединения, построенный по доступному контексту и сервисам EDT. */
public final class EdtToolRegistry {
    private final Map<String,EdtToolDescriptor> tools=new LinkedHashMap<>();
    public EdtToolRegistry(boolean writableConfiguration) {
        for(var value:SemanticTools.list().getAsJsonArray("tools")) {
            var definition=value.getAsJsonObject(); String name=definition.get("name").getAsString();
            if(SemanticTools.READ.contains(name)) tools.put(name,new EdtToolDescriptor(name,"metadata",false,definition));
        }
        var kind=object("type","string","enum",new MetadataTypeRegistry().all().stream().map(MetadataTypeDescriptor::name).toList());
        var target=object("kind",kind,"name",object("type","string"));
        add("edt_list_metadata_types","metadata",false,"List actual metadata types, safe properties, enums, children and module slots of this EDT version.",object(),List.of());
        add("edt_describe_type","metadata",false,"Describe native metadata type before planning changes; child relation names and property enum literals come from EDT.",object("kind",kind),List.of("kind"));
        for(String name:List.of("edt_get_children","edt_get_modules","edt_get_references"))
            add(name,"metadata",false,"Read children, module filesystem paths or direct metadata references of one exact object through EDT.",target,List.of("kind","name"));
        add("edt_capabilities","platform",false,"Inspect native tools, API status and bound project; internal APIs are disabled.",object(),List.of());
        add("edt_get_problems","validation",false,"Read standard Eclipse/EDT Problems markers for this project. No custom BSL validator.",object(),List.of());
        add("edt_debug_state","debug",false,"Read Eclipse launch configurations, debug target state and breakpoints explicitly mapped to this project. Does not start, stop or connect to a database; no configuration attributes or credentials are exposed.",object(),List.of());
        add("edt_bsl_read","bsl",false,"Read current Xtext document (including unsaved buffer), native BSL methods/parameters, parse diagnostics and element at offset. SHA-256 identifies the exact document for edits. path is project-relative Module.bsl. Text is explicitly paged at 128000 characters.",object("path",object("type","string"),"offset",object("type","integer","minimum",0)),List.of("path"));
        add("edt_bsl_context","bsl",false,"Inspect native AST element at offset, inferred types and available methods in its real client/server environment through EDT TypesComputer/IScopeProvider. No synthetic BSL parser or invented platform API.",object("path",object("type","string"),"offset",object("type","integer","minimum",0)),List.of("path","offset"));
        add("edt_open_resource","navigation",false,"Open a project-relative resource in its native EDT editor; optionally select a 1-based line in a text editor. Does not change files.",object("path",object("type","string"),"line",object("type","integer","minimum",1)),List.of("path"));
        if(writableConfiguration) {
            var edit=object("type","object","properties",object("offset",object("type","integer","minimum",0),"length",object("type","integer","minimum",0),"text",object("type","string")),"required",List.of("offset","length","text"),"additionalProperties",false);
            add("edt_bsl_edit","bsl",true,"Apply bounded non-overlapping edits to native Xtext document, then save through EDT document provider. Read edt_bsl_read first, pass expectedSha256. Empty missing native modules can be populated. Dirty editor is protected. Do not edit BSL through shell/file patches when this tool is available.",object("path",object("type","string"),"expectedSha256",object("type","string"),"edits",object("type","array","items",edit,"minItems",1,"maxItems",64)),List.of("path","expectedSha256","edits"));
            add("edt_bsl_format","bsl",true,"Format BSL using the installed Xtext formatter and save via document provider. Requires exact expectedSha256 from edt_bsl_read.",object("path",object("type","string"),"expectedSha256",object("type","string")),List.of("path","expectedSha256"));
            add("edt_validate_project","validation",true,"Run normal Eclipse/EDT incremental project builders, await standard build jobs and return Problems. Does not launch a live 1C database or implement a custom compiler.",object(),List.of());
            var operation=object("type","object","properties",object("operation",object("type","string","enum",MetadataOperations.ACTIONS),"kind",kind,"name",object("type","string"),
                    "synonym",object("type","string"),"properties",object("type","object"),"type",object("type","object"),"children",object("type","object"),"form",object("type","object","description","Managed form: template=GENERIC/OBJECT/LIST/RECORD_SET etc; attributes [{name,title,type}], commands [{name,title,handler}], items [{kind:button/field/table/group,name,title,parent?,command?,dataPath?}]. Existing forms are modified incrementally; never replace their XML."),
                    "path",object("type","array","items",object("type","object")),"collection",object("type","string"),"child",object("type","object"),"childName",object("type","string"),"reference",object("type","object")),
                    "required",List.of("operation","kind","name"),"additionalProperties",false);
            add("edt_apply_metadata_plan","metadata",true,
                    "Create/update native EDT metadata in one validated BM editing context. Read edt_describe_type first. Operations: create/update use kind,name,synonym,properties,type,children. children maps public collection names to arrays of {name,synonym,properties,type,children}. addChild/updateChild use collection,child. path traverses children as [{collection,name}]. removeChild uses collection,childName and requires approval. addReference on Subsystem uses collection=content,reference={kind,name}. Primitive type kinds: String(length),Number(precision,scale),Boolean,Date(fractions). CatalogRef(catalog), DocumentRef/EnumRef/ChartOfCharacteristicTypesRef/ChartOfAccountsRef/ChartOfCalculationTypesRef/ExchangePlanRef/BusinessProcessRef/TaskRef(name). Composite(types). Create referenced objects earlier in the same plan. Never generate metadata XML. EDT saves and synchronizes native resources; read-only rejects mutations.",
                    object("operations",object("type","array","items",operation,"minItems",1,"maxItems",32)),List.of("operations"));
        }
    }
    public void add(String name,String area,boolean writes,String description,JsonObject properties,List<String> required) {
        var props=properties.deepCopy(); props.add("turnKey",object("type","string","description","Current IDE turnKey; never reuse a previous turn key."));
        var fields=new ArrayList<>(required);fields.add("turnKey");
        var definition=object("name",name,"description",description,"inputSchema",object("type","object","properties",props,"required",fields,"additionalProperties",false),
                "annotations",object("readOnlyHint",!writes,"destructiveHint",writes,"openWorldHint",false));
        tools.put(name,new EdtToolDescriptor(name,area,writes,definition));
    }
    public EdtToolDescriptor require(String name) {
        var tool=tools.get(name); if(tool==null) throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION",name);return tool;
    }
    public JsonObject catalog() {var values=new JsonArray();tools.values().forEach(t->values.add(t.definition().deepCopy()));return object("tools",values);}
    public JsonObject diagnostic() {return object("tools",tools.values().stream().map(EdtToolDescriptor::diagnostic).toList(),"toolCount",tools.size(),"publicCapabilityCount",tools.size(),"experimentalCapabilityCount",0,"disabledInternalCapabilities",List.of("directBslFormatter"),"metadataTypeCount",new MetadataTypeRegistry().all().size(),"internalApisEnabled",false);}
}
