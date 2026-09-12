package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import java.util.*;
import com.google.gson.*;
import org.junit.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.resources.IProject;
import io.github.zhumaniezov.codex.edt.semantic.*;

public class NativePlatformTest {
    static final String SALES="""
      {"operations":[
       {"operation":"create","kind":"Subsystem","name":"Продажи"},
       {"operation":"create","kind":"Catalog","name":"Номенклатура","children":{"attributes":[{"name":"Артикул","type":{"kind":"String","length":30}},{"name":"Цена","type":{"kind":"Number","precision":15,"scale":2}}]}},
       {"operation":"create","kind":"Enum","name":"Состояния","children":{"enumValues":[{"name":"Новый"},{"name":"Готов"}]}},
       {"operation":"create","kind":"Document","name":"ЗаказКлиента","children":{"attributes":[{"name":"Состояние","type":{"kind":"EnumRef","name":"Состояния"}}],"tabularSections":[{"name":"Товары","children":{"attributes":[{"name":"Номенклатура","type":{"kind":"CatalogRef","catalog":"Номенклатура"}},{"name":"Количество","type":{"kind":"Number","precision":15,"scale":3}}]}}]}},
       {"operation":"addChild","kind":"Document","name":"ЗаказКлиента","collection":"forms","child":{"name":"ФормаДокумента","form":{"template":"OBJECT","commands":[{"name":"Заполнить","handler":"Заполнить"}],"items":[{"kind":"button","name":"Заполнить","command":"Заполнить"}]}}},
       {"operation":"create","kind":"CommonModule","name":"ПродажиСервер","properties":{"server":true}},
       {"operation":"addReference","kind":"Subsystem","name":"Продажи","collection":"content","reference":{"kind":"Catalog","name":"Номенклатура"}},
       {"operation":"addReference","kind":"Subsystem","name":"Продажи","collection":"content","reference":{"kind":"Document","name":"ЗаказКлиента"}}
      ]}
      """;
    @Test public void rootRegistryFactoriesUpdateAndPersist() throws Exception {
        nativeRun(project->{
            try(var metadata=new EdtMetadataService(project)) {
                int tested=0;
                for(var descriptor:new MetadataTypeRegistry().all()) {
                    if(descriptor.name().equals("Interface"))continue;
                    String name="Проверка"+descriptor.name();
                    System.out.println("NATIVE_FACTORY "+descriptor.name());
                    metadata.apply(plan(object("operation","create","kind",descriptor.name(),"name",name)));
                    metadata.apply(plan(object("operation","update","kind",descriptor.name(),"name",name,"synonym","Проверено","properties",object("comment","Native API test"))));
                    var result=get(metadata,descriptor.name(),name);assertEquals(descriptor.name(),result.get("kind").getAsString());assertEquals("Проверено",result.getAsJsonObject("synonym").get("ru").getAsString());tested++;
                }
                assertEquals(48,tested);
                var counts=metadata.read("edt_get_configuration_info",object()).getAsJsonObject("metadataCounts");
                assertEquals(49,counts.size());assertEquals(1,counts.get("HTTPService").getAsInt());assertEquals(1,counts.get("InformationRegister").getAsInt());
                reopen(project);
                for(var descriptor:new MetadataTypeRegistry().all()) if(!descriptor.name().equals("Interface")) assertEquals("Проверено",get(metadata,descriptor.name(),"Проверка"+descriptor.name()).getAsJsonObject("synonym").get("ru").getAsString());
                assertThrows(EdtToolException.class,()->metadata.apply(plan(object("operation","create","kind","Interface","name","НеПоддержан"))));
                System.out.println("NATIVE_ROOT_COVERAGE create/update/reopen=48 registry=49");
                artifacts(project,"root-types");
            }
        });
    }
    @Test public void salesPlanFormBslAndPreflightRollback() throws Exception {
        nativeRun(project->{
            try(var metadata=new EdtMetadataService(project)) {
                metadata.apply(new MetadataPlan(JsonParser.parseString(SALES).getAsJsonObject()));
                var order=get(metadata,"Document","ЗаказКлиента");assertEquals(1,order.getAsJsonArray("tabularSections").size());
                var form=order.getAsJsonArray("forms").get(0).getAsJsonObject().getAsJsonObject("form");
                assertTrue(form.toString(),form.getAsJsonArray("items").asList().stream().anyMatch(e->e.getAsJsonObject().get("kind").getAsString().equals("Table")));
                assertTrue(form.toString().contains("Заполнить"));
                var context=new EdtToolExecutionContext(project,"test-thread","test-turn",()->true);
                var bsl=new EdtBslService(context);
                String module="src/Documents/ЗаказКлиента/Forms/ФормаДокумента/Module.bsl";
                replace(bsl,module,"&НаКлиенте\nПроцедура Заполнить(Команда)\n\tСообщить(\"OK\");\nКонецПроцедуры\n");
                var read=bsl.read(object("path",module));assertTrue(read.toString().contains("Заполнить"));
                assertEquals(0,read.getAsJsonObject("syntax").getAsJsonArray("parseErrors").size());
                var invalid=new MetadataPlan(object("operations",List.of(object("operation","create","kind","Document","name","НеСохранять"),object("operation","addChild","kind","Catalog","name","НетОбъекта","collection","attributes","child",object("name","Ошибка","type",object("kind","Boolean"))))));
                assertThrows(EdtToolException.class,()->metadata.apply(invalid));
                assertEquals("notFound",metadata.read("edt_find_metadata_object",object("kind","Document","name","НеСохранять")).get("status").getAsString());
                var failingForm=object("operation","create","kind","CommonForm","name","ОткатФормы","form",object("items",List.of(object("kind","button","name","Ошибка","command","НетКоманды"))));
                var failingPlan=new MetadataPlan(object("operations",List.of(object("operation","create","kind","Catalog","name","ОткатСправочника"),failingForm)));
                assertThrows(EdtToolException.class,()->metadata.apply(failingPlan));
                assertEquals("notFound",metadata.read("edt_find_metadata_object",object("kind","Catalog","name","ОткатСправочника")).get("status").getAsString());
                assertEquals("notFound",metadata.read("edt_find_metadata_object",object("kind","CommonForm","name","ОткатФормы")).get("status").getAsString());
                assertFalse(project.getFile("src/Catalogs/ОткатСправочника/ОткатСправочника.mdo").exists());
                reopen(project);assertTrue(get(metadata,"Document","ЗаказКлиента").toString().contains("Заполнить"));
                assertTrue(bsl.read(object("path",module)).toString().contains("Сообщить"));
                artifacts(project,"sales");
            }
        });
    }
    @Test public void httpServiceRegisterAndBslDiagnosticsRepair() throws Exception {
        nativeRun(project->{
            try(var metadata=new EdtMetadataService(project)) {
                metadata.apply(new MetadataPlan(JsonParser.parseString("""
                  {"operations":[{"operation":"create","kind":"HTTPService","name":"ПроверкаHTTP","properties":{"rootURL":"ping"},"children":{"urlTemplates":[{"name":"Ping","properties":{"template":"/ping"},"children":{"methods":[{"name":"Get","properties":{"httpMethod":"GET","handler":"Ping"}}]}}]}},
                  {"operation":"create","kind":"InformationRegister","name":"СостоянияОбмена","children":{"dimensions":[{"name":"Ключ","type":{"kind":"String","length":50}}],"resources":[{"name":"Успешно","type":{"kind":"Boolean"}},{"name":"Значение","type":{"kind":"Number","precision":15,"scale":2}}]}}]}
                  """).getAsJsonObject()));
                assertEquals(2,get(metadata,"InformationRegister","СостоянияОбмена").getAsJsonArray("resources").size());
                var bsl=new EdtBslService(new EdtToolExecutionContext(project,"t","u",()->true));
                String path="src/HTTPServices/ПроверкаHTTP/Module.bsl";
                metadata.apply(plan(object("operation","create","kind","CommonModule","name","ПервыйМодуль","properties",object("server",true))));
                bsl.read(object("path","src/CommonModules/ПервыйМодуль/Module.bsl"));
                var aborted=new java.util.concurrent.atomic.AtomicBoolean();
                var guard=EdtBslService.ui(()->{var workbench=org.eclipse.ui.PlatformUI.getWorkbench();return io.github.zhumaniezov.codex.edt.context.ProjectWriteGuard.acquire(workbench,workbench.getActiveWorkbenchWindow().getShell(),java.nio.file.Path.of(project.getLocationURI()).toString(),()->aborted.set(true));});
                try {
                replace(bsl,path,"Функция Ping(Запрос)\n\tВозврат ;\n");
                assertFalse("Открытие native BSL editor не должно прерывать turn",aborted.get());
                } finally {EdtBslService.ui(()->{guard.close();return null;});}
                assertFalse(bsl.read(object("path",path)).getAsJsonObject("syntax").getAsJsonArray("parseErrors").isEmpty());
                replace(bsl,path,"Функция Ping(Запрос)\n\tОтвет = Новый HTTPСервисОтвет(200);\n\tОтвет.УстановитьТелоИзСтроки(\"pong\");\n\tВозврат Ответ;\nКонецФункции\n");
                var read=bsl.read(object("path",path));assertTrue(read.toString().contains("pong"));assertEquals(0,read.getAsJsonObject("syntax").getAsJsonArray("parseErrors").size());
                var inspected=bsl.read(object("path",path,"offset",read.get("text").getAsString().indexOf("Ответ ="),"inspect",true));
                assertTrue(inspected.getAsJsonObject("syntax").has("scope"));
                bsl.edit(object("operation","bslFormat","path",path,"expectedSha256",read.get("sha256").getAsString()));
                assertTrue(bsl.read(object("path",path)).get("text").getAsString().contains("pong"));
                var file=project.getFile(path);
                var editor=EdtBslService.ui(()->{
                    var page=org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    var part=org.eclipse.ui.ide.IDE.openEditor(page,file,true);
                    return part instanceof org.eclipse.ui.texteditor.ITextEditor textEditor?textEditor:part.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
                });
                assertNotNull(editor);
                String clean=bsl.read(object("path",path)).get("text").getAsString();
                EdtBslService.ui(()->{editor.getDocumentProvider().getDocument(editor.getEditorInput()).set("// Несохранённый буфер\n"+clean);return null;});
                var dirty=bsl.read(object("path",path));assertTrue(dirty.get("dirty").getAsBoolean());assertTrue(dirty.get("text").getAsString().contains("Несохранённый буфер"));
                assertThrows(EdtToolException.class,()->bsl.edit(object("operation","bslEdit","path",path,"expectedSha256",dirty.get("sha256").getAsString(),"edits",List.of(object("offset",0,"length",0,"text","bad")))));
                EdtBslService.ui(()->{editor.getDocumentProvider().getDocument(editor.getEditorInput()).set(clean);editor.doSave(new NullProgressMonitor());return null;});
                assertTrue(EdtDebugService.read(project).getAsJsonArray("launches").isEmpty());
                try(var tools=new EdtToolPlatform(new EdtToolExecutionContext(project,"t","u",()->true))) {
                    assertEquals("completed",tools.apply(new MetadataPlan(object("operations",List.of(object("operation","validateProject"))))).get("status").getAsString());
                }
                assertThrows(EdtToolException.class,()->bsl.edit(object("operation","bslEdit","path",path,"expectedSha256","0".repeat(64),"edits",List.of(object("offset",0,"length",0,"text","bad")))));
                assertThrows(EdtToolException.class,()->bsl.file("../outside.bsl"));
                artifacts(project,"http-register");
            }
        });
    }
    @Test public void existingCommonFormGetsCommandAttributeAndButton() throws Exception {
        nativeRun(project->{
            try(var metadata=new EdtMetadataService(project)) {
                metadata.apply(plan(object("operation","create","kind","CommonForm","name","Проверка")));
                metadata.apply(new MetadataPlan(JsonParser.parseString("""
                    {"operations":[{"operation":"update","kind":"CommonForm","name":"Проверка","form":{"attributes":[{"name":"Текст","type":{"kind":"String","length":100}}],"commands":[{"name":"Проверить","handler":"Проверить"}],"items":[{"kind":"group","name":"ОсновнаяГруппа"},{"kind":"field","name":"Текст","parent":"ОсновнаяГруппа","dataPath":"Текст"},{"kind":"button","name":"Проверить","parent":"ОсновнаяГруппа","command":"Проверить"}]}}]}
                    """).getAsJsonObject()));
                String path="src/CommonForms/Проверка/Module.bsl";
                replace(new EdtBslService(new EdtToolExecutionContext(project,"t","u",()->true)),path,"&НаКлиенте\nПроцедура Проверить(Команда)\n\tСообщить(\"OK\");\nКонецПроцедуры\n");
                reopen(project);assertEquals(3,get(metadata,"CommonForm","Проверка").getAsJsonObject("form").getAsJsonArray("items").size());
                artifacts(project,"existing-form");
            }
        });
    }
    static void replace(EdtBslService bsl,String path,String text) throws Exception {
        var read=bsl.read(object("path",path));
        bsl.edit(object("operation","bslEdit","path",path,"expectedSha256",read.get("sha256").getAsString(),"edits",List.of(object("offset",0,"length",read.get("totalLength").getAsInt(),"text",text))));
    }
    private static JsonObject get(EdtMetadataService metadata,String kind,String name) {return metadata.read("edt_get_metadata_object",object("kind",kind,"name",name)).getAsJsonArray("objects").get(0).getAsJsonObject();}
    private static MetadataPlan plan(JsonObject operation) {return new MetadataPlan(object("operations",List.of(operation)));}
    private static void reopen(IProject project) throws Exception {
        EdtBslService.ui(()->{for(var window:org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows())for(var page:window.getPages())for(var ref:page.getEditorReferences()) {
            var editor=ref.getEditor(false);if(editor==null)continue;var file=org.eclipse.ui.ide.ResourceUtil.getFile(editor.getEditorInput());
            if(file!=null && project.equals(file.getProject()))assertTrue(page.closeEditor(editor,true));
        }return null;});
        var manager=com._1c.g5.wiring.ServiceAccess.get(com._1c.g5.v8.dt.core.platform.IV8ProjectManager.class);
        var stopped=new java.util.concurrent.CompletableFuture<Void>();
        com._1c.g5.v8.dt.core.event.IEventListener listener=event->{if(event instanceof com._1c.g5.v8.dt.core.platform.events.IV8ProjectLifecycleEvent lifecycle && lifecycle.getType()==com._1c.g5.v8.dt.core.platform.events.IV8ProjectLifecycleEvent.Type.DELETED)stopped.complete(null);};
        manager.addProjectListener(project,listener,com._1c.g5.v8.dt.core.platform.events.IV8ProjectLifecycleEvent.class);
        try{project.close(new NullProgressMonitor());stopped.get(60,java.util.concurrent.TimeUnit.SECONDS);}finally{manager.removeListener(listener);}
        SemanticFixture.active(project,false);project.open(new NullProgressMonitor());SemanticFixture.active(project,true);
    }
    @FunctionalInterface interface Scenario {void run(IProject project)throws Exception;}
    static void artifacts(IProject project,String scenario)throws Exception {
        String result=System.getProperty("codex.edt.smoke.result");if(result==null)return;
        var source=java.nio.file.Path.of(project.getLocationURI()).resolve("src");
        var output=java.nio.file.Path.of(result).getParent().resolve("native-artifacts").resolve(scenario);
        try(var files=java.nio.file.Files.walk(source)) {for(var path:files.filter(java.nio.file.Files::isRegularFile).toList()) {
            var target=output.resolve(source.relativize(path));java.nio.file.Files.createDirectories(target.getParent());java.nio.file.Files.copy(path,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }}
    }
    static void nativeRun(Scenario scenario)throws Exception {
        Assume.assumeTrue(Platform.getProduct()!=null && "com._1c.g5.v8.dt.product.application.rcp".equals(Platform.getProduct().getId()));
        var task=java.util.concurrent.CompletableFuture.runAsync(()->{var project=SemanticFixture.create("NativePlatform"+System.nanoTime());try{scenario.run(project);}catch(Throwable error){error.printStackTrace();throw new java.util.concurrent.CompletionException(error);}finally{SemanticFixture.delete(project);}});
        ViewScenario.waitFor(task::isDone,240,()->{});task.get();
    }
}
