package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.*;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.semantic.*;

public class NativePlatformLiveTest {
    @Test public void realCodexUsesNativeMetadataFormsAndBslInOneThread() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("codex.edt.toolPlatformLive"));
        var creation=CompletableFuture.supplyAsync(()->SemanticFixture.create("codex-native-live-"+UUID.randomUUID()));
        waitFor(creation::isDone,90,()->{});var project=creation.get();
        var session=new CodexSessionService(line->System.out.println("NATIVE_LIVE_DIAGNOSTIC "+line));
        var registration=FrameworkUtil.getBundle(getClass()).getBundleContext().registerService(CodexClientFactory.class,()->session,null);
        var page=PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();IViewPart view=null;
        try {
            page.closeAllEditors(false);view=page.showView("io.github.zhumaniezov.codex.edt.views.Codex");
            waitFor(()->session.snapshot().state()==SessionData.State.READY,90,()->{});
            var mode=session.selectPermission(PermissionMode.STRICT).toCompletableFuture();waitFor(mode::isDone,30,()->{});mode.get();
            var approvals=new AtomicInteger();var shell=view.getSite().getShell();
            run(shell,session,"Используй только EDT native tools, не shell/apply_patch/XML. Исследуй descriptors и создай подсистему Продажи; справочник Номенклатура с Артикул Строка(30), Цена Число(15,2); документ ЗаказКлиента с табличной частью Товары: Номенклатура СправочникСсылка.Номенклатура, Количество Число(15,3); общий модуль ПродажиСервер server=true. Включи справочник и документ в подсистему. Через генератор EDT создай форму документа ФормаДокумента OBJECT с таблицей Товары, командой и кнопкой Заполнить. Через edt_bsl_read/edt_bsl_edit запиши клиентский обработчик Заполнить(Команда), вызывающий Сообщить(\"OK\"). Сохрани через native API, запусти edt_validate_project и кратко сообщи результат. Не добавляй больше объектов.",approvals);
            String thread=session.snapshot().threadId();
            query(()->{try(var metadata=new EdtMetadataService(project)){
                var catalog=get(metadata,"Catalog","Номенклатура");assertEquals(2,catalog.getAsJsonArray("attributes").size());
                var document=get(metadata,"Document","ЗаказКлиента");assertEquals(1,document.getAsJsonArray("tabularSections").size());
                assertTrue(document.toString().contains("Заполнить"));assertTrue(document.toString().contains("Table"));
                assertTrue(get(metadata,"CommonModule","ПродажиСервер").toString().contains("ПродажиСервер"));
            }});
            System.out.println("NATIVE_LIVE_STEP sales=true approvals="+approvals.get());
            run(shell,session,"Только EDT native tools. Создай HTTPService ПроверкаHTTP rootURL=ping, urlTemplate Ping template=/ping, метод Get httpMethod=GET handler=Ping. Через native BSL tools реализуй функцию Ping(Запрос): новый HTTPСервисОтвет(200), УстановитьТелоИзСтроки(\"pong\"), вернуть ответ. Создай InformationRegister СостоянияОбмена: dimension Ключ String(50); resources Успешно Boolean, Значение Number(15,2). Сохрани и выполни edt_validate_project. Не запускай живую базу и HTTP server.",approvals);
            query(()->{try(var metadata=new EdtMetadataService(project)){
                assertEquals(2,get(metadata,"InformationRegister","СостоянияОбмена").getAsJsonArray("resources").size());
                var bsl=new EdtBslService(new EdtToolExecutionContext(project,"test", "test",()->true));
                assertTrue(bsl.read(object("path","src/HTTPServices/ПроверкаHTTP/Module.bsl")).get("text").getAsString().contains("pong"));
            }});
            System.out.println("NATIVE_LIVE_STEP http=true register=true");
            run(shell,session,"Только EDT native tools. В существующую форму документа ЗаказКлиента/ФормаДокумента добавь команду и кнопку Проверить с обработчиком Проверить. В модуль формы добавь &НаКлиенте Процедура Проверить(Команда), Сообщить(\"OK\"), КонецПроцедуры. Сохрани уже существующий обработчик Заполнить, не заменяй форму. Запусти edt_validate_project. Исследуй актуальный модуль сначала через edt_bsl_read.",approvals);
            query(()->{try(var metadata=new EdtMetadataService(project)){
                var doc=get(metadata,"Document","ЗаказКлиента");assertTrue(doc.toString().contains("Проверить"));assertTrue(doc.toString().contains("Заполнить"));
                var bsl=new EdtBslService(new EdtToolExecutionContext(project,"test","test",()->true));
                var code=bsl.read(object("path","src/Documents/ЗаказКлиента/Forms/ФормаДокумента/Module.bsl"));
                assertTrue(code.get("text").getAsString().contains("Проверить"));assertTrue(code.get("text").getAsString().contains("Заполнить"));
                assertEquals(0,code.getAsJsonObject("syntax").getAsJsonArray("parseErrors").size());
            }});
            int previous=approvals.get();
            run(shell,session,"Только прочитай через semantic tools структуру текущей конфигурации: подсистемы, справочники, документы, регистры, формы, команды и модули. Ничего не изменяй. Кратко перечисли найденное, не угадывай XML.",approvals);
            assertEquals(previous,approvals.get());assertEquals(thread,session.snapshot().threadId());assertTrue(approvals.get()>0);
            query(()->NativePlatformTest.artifacts(project,"live-acceptance"));
            System.out.println("NATIVE_LIVE_PASS model="+session.snapshot().model()+" approvals="+approvals.get()+" oneThread=true nativeOnly=true");
        } finally {
            if(view!=null)page.hideView(view);session.close();registration.unregister();waitFor(()->session.termination().isDone(),30,()->{});
            page.closeAllEditors(false);query(()->SemanticFixture.delete(project));
        }
    }
    private static com.google.gson.JsonObject get(EdtMetadataService service,String kind,String name){return service.read("edt_get_metadata_object",object("kind",kind,"name",name)).getAsJsonArray("objects").get(0).getAsJsonObject();}
    @FunctionalInterface interface Check {void run()throws Exception;}
    private static void query(Check check)throws Exception {var future=CompletableFuture.runAsync(()->{try{check.run();}catch(Exception e){throw new CompletionException(e);}});waitFor(future::isDone,120,()->{});future.get();}
    private static void run(Shell shell,CodexSessionService session,String text,AtomicInteger approvals)throws Exception {
        var prompt=(Text)find(shell,"prompt");var send=find(shell,"send");prompt.setText(text);waitFor(send::isEnabled,30,()->{});send.notifyListeners(SWT.Selection,new Event());
        waitFor(()->session.snapshot().state().running() || session.snapshot().state()==SessionData.State.ERROR,45,()->{});assertTrue(session.snapshot().state().running());
        waitFor(()->!session.snapshot().state().running() && send.isEnabled() && !Boolean.TRUE.equals(send.getData("codex.running")),420,()->{answer(shell,approvals);if(prompt.getText().isBlank())prompt.setText("next");});
        if(session.snapshot().state()!=SessionData.State.READY) {
            var history=session.history("").toCompletableFuture();waitFor(history::isDone,30,()->{});
            System.out.println("NATIVE_LIVE_FAILED_TURN "+history.get().messages());
        }
        assertEquals(SessionData.State.READY,session.snapshot().state());
    }
    private static void answer(Composite parent,AtomicInteger approvals) {
        for(var child:parent.getChildren()) {
            if(child instanceof Button button && button.isEnabled()) {
                boolean nativeApproval="semanticAccept".equals(button.getData("codex.role"));
                boolean mcpApproval=Boolean.TRUE.equals(button.getData("codex.nativeMcpApproval"));
                if(nativeApproval || (mcpApproval?"accept":"decline").equals(button.getData("codex.decision"))) {
                    System.out.println("NATIVE_LIVE_DECISION "+(nativeApproval?"native-plan":mcpApproval?"native-mcp":"non-native-declined"));
                    button.setEnabled(false);if(nativeApproval)approvals.incrementAndGet();button.notifyListeners(SWT.Selection,new Event());return;
                }
            }
            if(child instanceof Composite composite)answer(composite,approvals);
        }
    }
}
