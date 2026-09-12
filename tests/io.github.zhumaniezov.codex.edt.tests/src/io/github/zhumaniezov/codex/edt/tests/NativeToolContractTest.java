package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import java.util.*;
import org.junit.Test;
import com.google.gson.*;
import io.github.zhumaniezov.codex.edt.semantic.*;

public class NativeToolContractTest {
    @Test public void registryCoversActualConcreteRootKinds() {
        var registry=new MetadataTypeRegistry();assertEquals(49,registry.all().size());
        for(String kind:List.of("Catalog","Document","Constant","Enum","CommonModule","CommonForm","CommonCommand","Subsystem","Role","Report","DataProcessor","HTTPService","InformationRegister","AccountingRegister","AccumulationRegister","CalculationRegister","ExternalDataSource","ChartOfAccounts"))assertNotNull(registry.require(kind));
        assertThrows(EdtToolException.class,()->registry.require("ImaginaryObject"));
    }
    @Test public void descriptorNeverExposesUuidOrRawEObjectSetters() {
        for(var descriptor:new MetadataTypeRegistry().all()) {
            assertFalse(descriptor.properties().containsKey("uuid"));assertFalse(descriptor.properties().containsKey("objectBelonging"));assertFalse(descriptor.properties().containsKey("extendedConfigurationObject"));
        }
        assertFalse(MetadataTypeRegistry.json(new MetadataTypeRegistry().require("Interface")).get("createSupported").getAsBoolean());
    }
    @Test public void catalogAdaptsToReadOnlyExtensionContext() {
        var read=new EdtToolRegistry(false);assertThrows(EdtToolException.class,()->read.require("edt_apply_metadata_plan"));
        assertNotNull(read.require("edt_bsl_read"));assertNotNull(read.require("edt_get_references"));
        var full=new EdtToolRegistry(true);assertTrue(full.require("edt_bsl_edit").writes());
        assertFalse(full.require("edt_bsl_context").writes());assertFalse(full.diagnostic().get("internalApisEnabled").getAsBoolean());
    }
    @Test public void everyToolRequiresCurrentTurnKeyAndClosedSchema() {
        for(var entry:new EdtToolRegistry(true).catalog().getAsJsonArray("tools")) {
            var schema=entry.getAsJsonObject().getAsJsonObject("inputSchema");assertFalse(schema.get("additionalProperties").getAsBoolean());
            assertTrue(schema.getAsJsonArray("required").asList().stream().anyMatch(v->v.getAsString().equals("turnKey")));
        }
    }
    @Test public void genericPlanSupportsAllTierOneDescriptors() {
        for(var descriptor:new MetadataTypeRegistry().all()) {
            var plan=new MetadataPlan(object("operations",List.of(object("operation","create","kind",descriptor.name(),"name","Тест"))));
            assertEquals(descriptor.name(),plan.json().getAsJsonArray("operations").get(0).getAsJsonObject().get("kind").getAsString());
        }
    }
    @Test public void documentAndCompositeReferenceTypesAreValidated() {
        MetadataPlan.validateType(object("kind","Composite","types",List.of(object("kind","String","length",50),object("kind","DocumentRef","name","Заказ"))));
        assertThrows(IllegalArgumentException.class,()->MetadataPlan.validateType(object("kind","Composite","types",List.of(object("kind","Boolean"),object("kind","Boolean")))));
        assertThrows(IllegalArgumentException.class,()->MetadataPlan.validateType(object("kind","DocumentRef","name","../Заказ")));
    }
    @Test public void unsafePropertyAndWrongChildAreRejectedBeforeBm() {
        assertThrows(EdtToolException.class,()->plan(object("operation","update","kind","Catalog","name","Тест","properties",object("uuid","bad"))));
        assertThrows(EdtToolException.class,()->plan(object("operation","addChild","kind","CommonModule","name","Тест","collection","dimensions","child",object("name","Ошибка"))));
    }
    @Test public void nativePropertyEnumLiteralsAreChecked() {
        assertNotNull(plan(object("operation","create","kind","HTTPService","name","Тест","properties",object("rootURL","ping"))));
        var method=object("name","Получить","properties",object("httpMethod","InventedVerb"));
        var template=object("name","Ping","children",object("methods",List.of(method)));
        assertThrows(EdtToolException.class,()->plan(object("operation","create","kind","HTTPService","name","Тест","children",object("urlTemplates",List.of(template)))));
    }
    @Test public void formContractRejectsUnknownControlsAndDuplicateNames() {
        assertThrows(EdtToolException.class,()->EdtFormService.validate(object("items",List.of(object("name","Кнопка","kind","webview")))));
        assertThrows(EdtToolException.class,()->EdtFormService.validate(object("commands",List.of(object("name","Тест","handler","Тест"),object("name","тест","handler","Тест")))));
    }
    @Test public void bslEditsRequireRevisionAndBoundedRanges() {
        assertThrows(EdtToolException.class,()->EdtBslService.validateEdit(object("operation","bslEdit","path","Module.bsl","expectedSha256","missing")));
        var input=object("operation","bslEdit","path","Module.bsl","expectedSha256",EdtBslService.hash(""),"edits",List.of(object("offset",0,"length",0,"text","// Тест")));
        assertNotNull(plan(input));assertThrows(EdtToolException.class,()->new MetadataPlan(object("operations",List.of(input,input))));
    }
    @Test public void metadataPlanKeepsIndependentImmutableSnapshot() {
        var input=object("operation","create","kind","Document","name","Исходный");var plan=plan(input);input.addProperty("name","Измененный");
        assertEquals("Исходный",plan.json().getAsJsonArray("operations").get(0).getAsJsonObject().get("name").getAsString());
    }
    @Test public void expiredExecutionContextFailsBeforeServiceCalls() {
        assertThrows(EdtToolException.class,()->new EdtToolExecutionContext(null,"t","u",()->false).check());
    }
    @Test public void nativeMcpApprovalRequiresOwnServerAndCurrentTurn() {
        var request=mcpRequest();
        assertTrue(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.supported(request,"own","t","u"));
        assertFalse(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.supported(request,"other","t","u"));
        assertFalse(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.supported(request,"own","t","late"));
        assertFalse(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.supported(request,"own","other","u"));
    }
    @Test public void nativeMcpApprovalRejectsFormsRequiringInputOrUrl() {
        var request=mcpRequest();request.getAsJsonObject("requestedSchema").getAsJsonObject("properties").add("token",object("type","string"));
        assertFalse(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.supported(request,"own","t","u"));
        request=mcpRequest();request.addProperty("mode","url");assertFalse(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.supported(request,"own","t","u"));
        request=mcpRequest();request.remove("_meta");assertFalse(io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.supported(request,"own","t","u"));
    }
    @Test public void nativeMcpApprovalHasNoPersistentGrant() {
        var request=new io.github.zhumaniezov.codex.edt.client.AgentApproval("key",new JsonPrimitive(42),io.github.zhumaniezov.codex.edt.client.NativeMcpApproval.METHOD,"t","u","",mcpRequest(),null,io.github.zhumaniezov.codex.edt.client.PermissionMode.STRICT);
        assertEquals(List.of("accept","decline"),request.decisions());assertEquals("accept",request.response("accept").get("action").getAsString());
        assertEquals("decline",request.response("decline").get("action").getAsString());assertFalse(request.response("accept").has("_meta"));
        assertThrows(IllegalArgumentException.class,()->request.response("acceptForSession"));
    }
    @Test public void nativeDiagnosticsAreImmutableAndLocalized() {
        var details=new EdtToolRegistry(true).diagnostic();details.addProperty("status","ready");details.addProperty("edtVersion","1.35.3");
        var snapshot=new io.github.zhumaniezov.codex.edt.client.SessionData.Snapshot(io.github.zhumaniezov.codex.edt.client.SessionData.State.READY,"",List.of(),"","","","",io.github.zhumaniezov.codex.edt.client.SessionData.Account.NONE,details);
        details.addProperty("status","changed");assertEquals("ready",snapshot.edtTools().get("status").getAsString());snapshot.edtTools().addProperty("status","changed");assertEquals("ready",snapshot.edtTools().get("status").getAsString());
        assertFalse(MetadataPresentation.diagnostic(snapshot.edtTools()).isBlank());
        for(String key:List.of("nativeMcpApproval","nativeMcpPlanFollows","nativePlatformReady"))for(var locale:List.of(Locale.ENGLISH,Locale.forLanguageTag("ru")))assertFalse(io.github.zhumaniezov.codex.edt.settings.LocalizationService.text(key,locale).isBlank());
    }
    private static JsonObject mcpRequest(){return object("serverName","own","threadId","t","turnId","u","mode","form","requestedSchema",object("type","object","properties",object()),"_meta",object("codex_approval_kind","mcp_tool_call"));}
    private static MetadataPlan plan(JsonObject operation) {return new MetadataPlan(object("operations",List.of(operation)));}
}
