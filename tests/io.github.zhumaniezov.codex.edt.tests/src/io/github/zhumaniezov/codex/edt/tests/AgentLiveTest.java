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
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.*;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;

public class AgentLiveTest {
    @Test
    public void realWriteApprovalsCommandsAndRefreshInEdt() throws Exception {
        Assume.assumeTrue("Запись выполняется только явно в изолированном проекте",
                Boolean.getBoolean("codex.edt.agentLive"));
        var session = new CodexSessionService(line -> System.out.println("AGENT_DIAGNOSTIC " + line));
        var registration = FrameworkUtil.getBundle(getClass()).getBundleContext()
                .registerService(CodexClientFactory.class, () -> session, null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var p = ResourcesPlugin.getWorkspace().getRoot().getProject("codex-agent-live-" + UUID.randomUUID());
        IViewPart view = null;
        ITextEditor editor = null;
        Path outside = null;
        try {
            p.create(null);
            p.open(null);
            var root = Path.of(p.getLocationURI());
            assertTrue(root.toRealPath().toString().contains("codex-edt"));
            Files.writeString(root.resolve("test-agent-mode.txt"), "initial\n");
            Files.writeString(root.resolve("sentinel.txt"), "DO NOT CHANGE");
            Files.writeString(root.resolve("AgentCommand.java"),
                    "class AgentCommand { public static void main(String[] args) { System.out.println(\"AGENT COMMAND OUTPUT\"); } }\n");
            p.refreshLocal(IResource.DEPTH_INFINITE, null);
            editor = (ITextEditor) IDE.openEditor(page, p.getFile("test-agent-mode.txt"),
                    "org.eclipse.ui.DefaultTextEditor");
            var document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            view = page.showView("io.github.zhumaniezov.codex.edt.views.Codex");
            var shell = view.getSite().getShell();
            var prompt = (Text) find(shell, "prompt");
            var send = find(shell, "send");
            var response = find(shell, "response");
            prompt.setText("ready");
            waitFor(send::isEnabled, 90, () -> {
            });
            var selected = session.selectPermission(PermissionMode.STRICT).toCompletableFuture();
            waitFor(selected::isDone, 30, () -> {
            });
            selected.get();
            var approved = new HashSet<String>();
            run(shell, session,
                    "Используй apply_patch с Update File (не Delete File и не Add File): замени строку initial в test-agent-mode.txt на AGENT WRITE TEST. Остальные файлы не меняй. Не выполняй дополнительных проверок; ответь DONE.",
                    approved, "accept");
            assertEquals("AGENT WRITE TEST", Files.readString(root.resolve("test-agent-mode.txt")).strip());
            waitFor(() -> document.get().strip().equals("AGENT WRITE TEST"), 10, () -> {
            });
            assertFalse("Не было реального approval", approved.isEmpty());
            var activity = (AgentActivity.Snapshot) find(shell, "agentActivity").getData("codex.activity");
            assertFalse("Нет diff", activity.diff().isBlank());
            String thread = session.snapshot().threadId();
            run(shell, session,
                    "Через apply_patch создай только created-agent-test.txt с текстом CREATED. Никаких других операций. Ответь DONE.",
                    approved, "accept");
            assertTrue(p.getFile("created-agent-test.txt").exists());
            run(shell, session,
                    "Через apply_patch удали только created-agent-test.txt. Другие файлы не меняй. Ответь DONE.",
                    approved, "accept");
            assertFalse(p.getFile("created-agent-test.txt").exists());
            run(shell, session, "Собери существующий AgentCommand.java командой & '" + System.getProperty("java.home")
                    + "/bin/javac.exe' -d . AgentCommand.java; затем запусти & '" + System.getProperty("java.home")
                    + "/bin/java.exe' -cp . AgentCommand. "
                    + "Это проверка сборки: исходники не изменяй, class-артефакт разрешён только в текущем cwd. Используй command execution. Ответь DONE.",
                    approved, "accept");
            assertTrue(p.getFile("AgentCommand.class").exists());
            activity = (AgentActivity.Snapshot) find(shell, "agentActivity").getData("codex.activity");
            assertTrue(activity.items().stream()
                    .anyMatch(i -> i.has("aggregatedOutput") && !i.get("aggregatedOutput").isJsonNull()
                            && i.get("aggregatedOutput").getAsString().contains("AGENT COMMAND OUTPUT")));
            assertTrue(activity.items().stream().anyMatch(
                    i -> i.has("exitCode") && !i.get("exitCode").isJsonNull() && i.get("exitCode").getAsInt() == 0));
            assertEquals(thread, session.snapshot().threadId());
            assertEquals("DO NOT CHANGE", Files.readString(root.resolve("sentinel.txt")));
            outside = root.getParent().resolve("boundary-" + UUID.randomUUID() + ".txt");
            Files.writeString(outside, "OUTSIDE UNCHANGED");
            var ask = session.selectPermission(PermissionMode.ASK).toCompletableFuture();
            waitFor(ask::isDone, 30, () -> {
            });
            ask.get();
            int priorApprovals = approved.size();
            run(shell, session, "Проверь границу sandbox: через apply_patch замени содержимое только файла " + outside
                    + " на OUTSIDE WRITE. Это специальная тестовая фикстура. Если сервер запросит разрешение и оно отклонено, сразу заверши turn, не повторяй и не меняй другие файлы.",
                    approved, "decline");
            assertEquals("OUTSIDE UNCHANGED", Files.readString(outside));
            assertTrue("Выход за root не запросил approval", approved.size() > priorApprovals);
            System.out.println("AGENT LIVE BOUNDARY: externalApprovalDeclined=true outsideUnchanged=true");
            var mode = session.selectPermission(PermissionMode.READ_ONLY).toCompletableFuture();
            waitFor(mode::isDone, 30, () -> {
            });
            mode.get();
            var before = hashes(root);
            run(shell, session, "Прочитай test-agent-mode.txt и скажи его содержимое. Ничего не изменяй.", approved,
                    "decline");
            assertEquals(before, hashes(root));
            System.out.println("AGENT LIVE PASS model=" + session.model() + " approvals=" + approved.size()
                    + " edit=true create=true delete=true command=true diff=true EDTRefresh=true cleanEditor=true readOnly=true sameThread=true sentinelUnchanged=true");
        } finally {
            if (view != null) {
                page.hideView(view);
            }
            session.close();
            session.termination().get(10, TimeUnit.SECONDS);
            registration.unregister();
            if (outside != null) {
                Files.deleteIfExists(outside);
            }
            if (editor != null) {
                page.closeEditor(editor, false);
            }
            if (p.exists()) {
                p.delete(true, true, null);
            }
        }
    }

    private void run(Composite shell, CodexSessionService session, String message, Set<String> approved,
            String decision) {
        var prompt = (Text) find(shell, "prompt");
        var send = find(shell, "send");
        prompt.setText(message);
        waitFor(() -> send.isEnabled() && !Boolean.TRUE.equals(send.getData("codex.running")), 30, () -> {
        });
        send.notifyListeners(SWT.Selection, new Event());
        waitFor(() -> session.snapshot().state().running() || session.snapshot().state() == SessionData.State.ERROR
                || session.snapshot().state() == SessionData.State.DISCONNECTED, 30,
                () -> approve(shell, approved, decision));
        assertTrue("Не удалось начать turn: " + session.snapshot().state(), session.snapshot().state().running());
        waitFor(() -> !session.snapshot().state().running() && send.isEnabled()
                && !Boolean.TRUE.equals(send.getData("codex.running")), 180, () -> {
                    approve(shell, approved, decision);
                    if (prompt.getText().isEmpty()) {
                        prompt.setText("next");
                    }
                });
        assertEquals("Реальный turn завершился с ошибкой", SessionData.State.READY, session.snapshot().state());
    }

    private void approve(Composite root, Set<String> approved, String decision) {
        for (var child : root.getChildren()) {
            if (child instanceof Composite panel) {
                var key = panel.getData("codex.approval");
                if (key != null && !approved.contains(key.toString())) {
                    for (var control : panel.getChildren()) {
                        if (control instanceof Button button && decision.equals(button.getData("codex.decision"))
                                && button.isEnabled()) {
                            approved.add(key.toString());
                            button.notifyListeners(SWT.Selection, new Event());
                            return;
                        }
                    }
                }
                approve(panel, approved, decision);
            }
        }
    }
}
