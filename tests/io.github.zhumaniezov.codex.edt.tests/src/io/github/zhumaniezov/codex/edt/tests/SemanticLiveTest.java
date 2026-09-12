package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.core.resources.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.junit.*;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.semantic.*;

public class SemanticLiveTest {
    @Test
    public void realNativePlanWithoutEditorThenBslFileEdit() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("codex.edt.semanticLive"));
        var creation = CompletableFuture
                .supplyAsync(() -> SemanticFixture.create("codex-semantic-live-" + UUID.randomUUID()));
        waitFor(creation::isDone, 90, () -> {
        });
        var project = creation.get();
        var session = new CodexSessionService(line -> System.out.println("SEMANTIC_LIVE_DIAGNOSTIC " + line));
        var registration = FrameworkUtil.getBundle(getClass()).getBundleContext()
                .registerService(CodexClientFactory.class, () -> session, null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IViewPart view = null;
        try {
            page.closeAllEditors(false);
            assertNull(page.getActiveEditor());
            view = page.showView("io.github.zhumaniezov.codex.edt.views.Codex");
            var shell = view.getSite().getShell();
            waitFor(() -> session.snapshot().state() == SessionData.State.READY, 90, () -> {
            });
            var mode = session.selectPermission(PermissionMode.STRICT).toCompletableFuture();
            waitFor(mode::isDone, 30, () -> {
            });
            mode.get();
            var count = new java.util.concurrent.atomic.AtomicInteger();
            run(shell, session,
                    "Через EDT native semantic tools одним планом создай справочник Товары с реквизитами Артикул Строка(30), Цена Число(15,2), Активен Булево; табличной частью ДополнительныеКоды с реквизитом Код Строка(50); и общий модуль ОбщегоНазначения с server=true, clientManagedApplication=false. Только native tools, XML и BSL пока не меняй. После результата ответь NATIVE DONE.",
                    count, true);
            assertTrue("Не было native approval", count.get() > 0);
            var root = Path.of(project.getLocationURI());
            assertTrue(Files.exists(root.resolve("src/Catalogs/Товары/Товары.mdo")));
            assertTrue(Files.exists(root.resolve("src/CommonModules/ОбщегоНазначения/ОбщегоНазначения.mdo")));
            var queryModel = CompletableFuture.runAsync(() -> {
                try (var metadata = new EdtMetadataService(project)) {
                    var catalog = metadata
                            .read("edt_get_metadata_object", io.github.zhumaniezov.codex.edt.protocol.CodexProtocol
                                    .object("kind", "Catalog", "name", "Товары"))
                            .getAsJsonArray("objects").get(0).getAsJsonObject();
                    assertEquals(3, catalog.getAsJsonArray("attributes").size());
                    assertEquals(1, catalog.getAsJsonArray("tabularSections").size());
                }
            });
            waitFor(queryModel::isDone, 30, () -> {
            });
            queryModel.get();
            System.out.println("SEMANTIC_LIVE_STEP native-created approvals=" + count.get());
            String thread = session.snapshot().threadId();
            run(shell, session,
                    "Через native tool попробуй создать только справочник ОтказПроверка. Если пользователь отклонит план, сразу заверши без повторов и обходов.",
                    count, false);
            try (var metadata = new EdtMetadataService(project)) {
                var query = CompletableFuture.supplyAsync(() -> metadata.read("edt_find_metadata_object",
                        io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("kind", "Catalog", "name",
                                "ОтказПроверка")));
                waitFor(query::isDone, 20, () -> {
                });
                assertEquals("notFound", query.get().get("status").getAsString());
            }
            run(shell, session,
                    "Для уже созданного общего модуля ОбщегоНазначения через штатный apply_patch запиши в src/CommonModules/ОбщегоНазначения/Module.bsl только комментарий // NATIVE BSL FOLLOWUP. Если пустой BSL ещё не выгружен EDT, разрешено создать этот один файл. Метаданные и другие файлы не меняй. Ответь BSL DONE.",
                    count, true);
            assertTrue(Files.readString(root.resolve("src/CommonModules/ОбщегоНазначения/Module.bsl"))
                    .contains("NATIVE BSL FOLLOWUP"));
            assertEquals(thread, session.snapshot().threadId());
            assertEquals(root.toRealPath().toString(), session.snapshot().cwd());
            System.out.println("SEMANTIC_LIVE_PASS model=" + session.snapshot().model() + " approvals=" + count.get()
                    + " oneThread=true editorRequired=false");
        } finally {
            if (view != null) {
                page.hideView(view);
            }
            session.close();
            registration.unregister();
            waitFor(() -> session.termination().isDone(), 20, () -> {
            });
            var cleanup = CompletableFuture.runAsync(() -> SemanticFixture.delete(project));
            waitFor(cleanup::isDone, 60, () -> {
            });
            cleanup.get();
        }
    }

    private static void run(Shell shell, CodexSessionService session, String text,
            java.util.concurrent.atomic.AtomicInteger count, boolean accept) throws Exception {
        var prompt = (Text) find(shell, "prompt");
        var send = find(shell, "send");
        prompt.setText(text);
        waitFor(send::isEnabled, 30, () -> {
        });
        send.notifyListeners(SWT.Selection, new Event());
        waitFor(() -> session.snapshot().state().running() || session.snapshot().state() == SessionData.State.ERROR, 45,
                () -> {
                });
        assertTrue(session.snapshot().toString(), session.snapshot().state().running());
        waitFor(() -> !session.snapshot().state().running() && send.isEnabled()
                && !Boolean.TRUE.equals(send.getData("codex.running")), 300, () -> {
                    approve(shell, count, accept);
                    if (prompt.getText().isBlank()) {
                        prompt.setText("next");
                    }
                });
        assertEquals(SessionData.State.READY, session.snapshot().state());
    }

    private static void approve(Composite parent, java.util.concurrent.atomic.AtomicInteger count, boolean accept) {
        for (var child : parent.getChildren()) {
            if (child instanceof Button button && button.isEnabled()) {
                String role = String.valueOf(button.getData("codex.role"));
                boolean nativeApproval = role.equals(accept ? "semanticAccept" : "semanticDecline");
                boolean fileApproval = (accept ? "accept" : "decline").equals(button.getData("codex.decision"));
                if (nativeApproval || fileApproval) {
                    button.setEnabled(false);
                    if (nativeApproval) {
                        count.incrementAndGet();
                    }
                    button.notifyListeners(SWT.Selection, new Event());
                    return;
                }
            }
            if (child instanceof Composite composite) {
                approve(composite, count, accept);
            }
        }
    }
}
