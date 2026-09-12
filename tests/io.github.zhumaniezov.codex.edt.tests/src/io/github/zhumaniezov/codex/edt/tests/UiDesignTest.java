package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import static io.github.zhumaniezov.codex.edt.tests.ViewScenario.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.ui.presentation.AttachmentSelection;

public class UiDesignTest {
    @Test
    public void lightNativeLayoutAndAttachmentRemoval() throws Exception {
        layout(false);
    }

    @Test
    public void darkNativeLayoutAndAttachmentRemoval() throws Exception {
        layout(true);
    }

    private void layout(boolean dark) throws Exception {
        var display = Display.getCurrent();
        var shell = new Shell(display);
        shell.setLayout(new FillLayout());
        // Цвета только тестовой поверхности; настройки и тема EDT не изменяются.
        var base = new Color(display, dark ? new RGB(35, 38, 41) : new RGB(240, 240, 240));
        var foreground = new Color(display, dark ? new RGB(230, 230, 230) : new RGB(30, 30, 30));
        shell.setBackground(base);
        shell.setForeground(foreground);
        var client = new CodexSessionService(TestServer.command("normal"), "test", line -> {
        });
        var registration = FrameworkUtil.getBundle(getClass()).getBundleContext()
                .registerService(CodexClientFactory.class, () -> client, null);
        IViewPart view = null;
        try {
            var element = java.util.Arrays
                    .stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.views"))
                    .filter(value -> "io.github.zhumaniezov.codex.edt.views.Codex".equals(value.getAttribute("id")))
                    .findFirst().orElseThrow();
            view = (IViewPart) element.createExecutableExtension("class");
            var site = (IViewSite) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { IViewSite.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getShell" -> shell;
                    case "getWorkbenchWindow" -> PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    case "getId" -> "io.github.zhumaniezov.codex.edt.views.Codex";
                    default -> null;
                    });
            view.init(site);
            var body = new Composite(shell, SWT.NONE);
            view.createPartControl(body);
            var model = (Combo) find(body, "model");
            waitFor(model::isEnabled, 15, () -> {
            });
            var palette = field(view, "palette");
            var chat = field(view, "chat");
            var history = chat.getClass().getDeclaredMethod("history", List.class, boolean.class);
            history.setAccessible(true);
            history.invoke(chat, List.of(new SessionData.Message("Пользователь", "Что делает выделенный код?"),
                    new SessionData.Message("Codex",
                            "**Этот код выводит сообщение.**\n\n- Процедура вызывается в модуле.\n- `Сообщить()` показывает текст пользователю.\n\n```bsl\nПроцедура Тест()\n    Сообщить(\"Привет\");\nКонецПроцедуры\n```\n\n[Module.bsl](src/Module.bsl)")),
                    false);
            var prompt = (Text) find(body, "prompt");
            prompt.setText("Объясни, где вызывается эта процедура");
            var bar = field(field(view, "composer"), "attachments");
            var selection = (AttachmentSelection) field(bar, "selection");
            var directory = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "ui-attachment-");
            selection.add(AttachmentSelection.validate(directory,
                    Files.writeString(directory.resolve("Module.bsl"), "saved")));
            var refresh = bar.getClass().getDeclaredMethod("refresh");
            refresh.setAccessible(true);
            refresh.invoke(bar);
            var response = (StyledText) find(body, "response");
            waitFor(() -> response.getText().contains("КонецПроцедуры"), 10, () -> {
            });
            var threadList = field(view, "threads");
            var page = threadList.getClass().getDeclaredMethod("page", SessionData.ThreadPage.class, boolean.class);
            page.setAccessible(true);
            long now = java.time.Instant.now().getEpochSecond();
            page.invoke(threadList, new SessionData.ThreadPage(List.of(
                    new SessionData.ThreadSummary("first", "Анализ общего модуля и длинное название диалога", now - 300,
                            ""),
                    new SessionData.ThreadSummary("second", "Обмен с бухгалтерией", now - 7200, ""),
                    new SessionData.ThreadSummary("third", "Обработчик команды формы", now - 82800, "")), ""), false);
            var table = (Table) find(body, "threads");
            if (table.getItemCount() > 0) {
                table.getItem(0).setText(0, "Анализ общего модуля и длинное название диалога");
                table.setSelection(0);
            }
            for (int width : new int[] { 320, 420 }) {
                var trim = shell.computeTrim(0, 0, width, 740);
                shell.setSize(trim.width, trim.height);
                shell.open();
                shell.layout(true, true);
                // Повторная тема перерисовывает уже созданные controls, включая Markdown.
                var reload = palette.getClass().getDeclaredMethod("reload");
                reload.setAccessible(true);
                reload.invoke(palette);
                body.layout(true, true);
                response.setTopPixel(0);
                body.redraw();
                body.update();
                assertTrue("Область диалога остаётся видимой", response.getSize().y > 100);
                var send = find(body, "send");
                assertEquals(send.getSize().x, send.getSize().y);
                assertEquals(36, send.getSize().x);
                var composer = find(body, "composer");
                var at = send.toDisplay(0, 0);
                var edge = body.toDisplay(body.getSize().x, body.getSize().y);
                assertTrue(at.x + send.getSize().x <= edge.x);
                assertTrue(at.y + send.getSize().y <= edge.y);
                assertNotEquals(body.getBackground().getRGB(), prompt.getBackground().getRGB());
                assertEquals(prompt.getBackground(), composer.getBackground());
                assertNotNull(find(body, "permissions"));
                var image = new Image(display, body.getSize().x, body.getSize().y);
                var gc = new GC(body);
                try {
                    gc.copyArea(image, 0, 0);
                    var loader = new ImageLoader();
                    loader.data = new ImageData[] { image.getImageData() };
                    loader.save(
                            Path.of(System.getProperty("java.io.tmpdir"),
                                    "codex-v050-" + (dark ? "dark" : "light") + "-" + width + ".png").toString(),
                            SWT.IMAGE_PNG);
                } finally {
                    gc.dispose();
                    image.dispose();
                }
            }
            var agent = field(view, "activity");
            var activityMethod = agent.getClass().getDeclaredMethod("activity", AgentActivity.Snapshot.class);
            activityMethod.setAccessible(true);
            var actionItem = io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("id", "patch-1", "type",
                    "fileChange", "status", "inProgress", "changes",
                    List.of(io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("path", "src/Module.bsl",
                            "kind", io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("type", "delete"),
                            "diff", "-old")));
            activityMethod.invoke(agent, new AgentActivity.Snapshot("thread", "turn", List.of(actionItem), "", false));
            var approvalMethod = agent.getClass().getDeclaredMethod("approval", AgentApproval.class);
            approvalMethod.setAccessible(true);
            approvalMethod.invoke(agent,
                    new AgentApproval("ui-approval", new com.google.gson.JsonPrimitive(1),
                            "item/fileChange/requestApproval", "thread", "turn", "patch-1",
                            io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("reason",
                                    "Удалить только тестовый файл по запросу пользователя"),
                            directory, PermissionMode.STRICT));
            var compact = threadList.getClass().getDeclaredMethod("compact", boolean.class);
            compact.setAccessible(true);
            compact.invoke(threadList, true);
            var trim = shell.computeTrim(0, 0, 320, 740);
            shell.setSize(trim.width, trim.height);
            body.layout(true, true);
            body.update();
            var agentSend = find(body, "send");
            var agentEdge = body.toDisplay(body.getSize().x, body.getSize().y);
            var sendAt = agentSend.toDisplay(0, 0);
            assertTrue("Composer доступен при approval", sendAt.y + agentSend.getSize().y <= agentEdge.y);
            assertTrue("Ответ виден при approval", response.getSize().y > 30);
            approvalBounds((Composite) find(body, "agentActivity"), agentEdge);
            var image = new Image(display, body.getSize().x, body.getSize().y);
            var gc = new GC(body);
            try {
                gc.copyArea(image, 0, 0);
                var loader = new ImageLoader();
                loader.data = new ImageData[] { image.getImageData() };
                loader.save(Path.of(System.getProperty("java.io.tmpdir"),
                        "codex-v060-agent-" + (dark ? "dark" : "light") + ".png").toString(), SWT.IMAGE_PNG);
            } finally {
                gc.dispose();
                image.dispose();
            }
            var clear = agent.getClass().getDeclaredMethod("clear");
            clear.setAccessible(true);
            clear.invoke(agent);
            compact.invoke(threadList, false);
            var attachmentRoot = (Composite) find(body, "attachments");
            assertTrue(attachmentRoot.isVisible());
            var chip = (Composite) attachmentRoot.getChildren()[0];
            chip.getChildren()[1].notifyListeners(SWT.Selection, new Event());
            assertTrue(selection.items().isEmpty());
            assertFalse(attachmentRoot.isVisible());
            prompt.setText("");
            assertFalse(find(body, "send").isEnabled());
            find(body, "newThread").notifyListeners(SWT.Selection, new Event());
            waitFor(() -> find(body, "newThread").isEnabled(), 10, () -> {
            });
            assertTrue(client.processAlive());
            assertEquals("", client.snapshot().threadId());
        } finally {
            if (view != null) {
                view.dispose();
            }
            shell.dispose();
            base.dispose();
            foreground.dispose();
            registration.unregister();
            client.close();
            client.termination().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(client.processAlive());
        }
    }

    @Test
    public void roundButtonKeyboardAndDisabledState() throws Exception {
        var shell = new Shell(Display.getCurrent());
        var parent = new Composite(shell, SWT.NONE);
        var bundle = Platform.getBundle("io.github.zhumaniezov.codex.edt");
        var paletteType = bundle.loadClass("io.github.zhumaniezov.codex.edt.ui.ThemePalette");
        var palette = paletteType.getConstructor(Control.class).newInstance(parent);
        var type = bundle.loadClass("io.github.zhumaniezov.codex.edt.ui.IconButton");
        try {
            var button = (Control) type
                    .getConstructor(Composite.class, paletteType, String.class, String.class, boolean.class)
                    .newInstance(parent, palette, "send", "Отправить", true);
            var count = new java.util.concurrent.atomic.AtomicInteger();
            button.addListener(SWT.Selection, event -> count.incrementAndGet());
            for (int key : new int[] { SWT.SPACE, SWT.CR }) {
                var event = new Event();
                event.keyCode = key;
                button.notifyListeners(SWT.KeyDown, event);
            }
            assertEquals(2, count.get());
            button.setEnabled(false);
            var event = new Event();
            event.keyCode = SWT.CR;
            button.notifyListeners(SWT.KeyDown, event);
            assertEquals(2, count.get());
            var traverse = new Event();
            traverse.detail = SWT.TRAVERSE_TAB_NEXT;
            traverse.doit = false;
            button.notifyListeners(SWT.Traverse, traverse);
            assertTrue(traverse.doit);
            assertNotNull(button.getAccessible());
            assertEquals("send", button.getData("codex.icon"));
        } finally {
            shell.dispose();
        }
    }

    @Test
    public void fileAttachmentMenuFeedsCurrentReadOnlyTurn() throws Exception {
        var workspace = org.eclipse.core.resources.ResourcesPlugin.getWorkspace();
        var project = workspace.getRoot().getProject("attachment-ui-" + java.util.UUID.randomUUID());
        project.create(null);
        project.open(null);
        var file = project.getFile("Module.bsl");
        file.create(new java.io.ByteArrayInputStream("saved".getBytes(java.nio.charset.StandardCharsets.UTF_8)), true,
                null);
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var editor = (org.eclipse.ui.texteditor.ITextEditor) org.eclipse.ui.ide.IDE.openEditor(page, file,
                "org.eclipse.ui.DefaultTextEditor");
        editor.getDocumentProvider().getDocument(editor.getEditorInput()).set("unsaved-buffer");
        var client = new CodexSessionService(TestServer.command("normal"), "test", line -> {
        });
        var registration = FrameworkUtil.getBundle(getClass()).getBundleContext()
                .registerService(CodexClientFactory.class, () -> client, null);
        IViewPart view = null;
        try {
            view = page.showView("io.github.zhumaniezov.codex.edt.views.Codex");
            var shell = view.getSite().getShell();
            waitFor(() -> find(shell, "model").isEnabled(), 15, () -> {
            });
            find(shell, "attach").notifyListeners(SWT.Selection, new Event());
            var controller = field(view, "attachments");
            var popup = (Menu) field(controller, "popup");
            assertFalse(popup.getItem(2).isEnabled());
            popup.getItem(0).notifyListeners(SWT.Selection, new Event());
            popup.setVisible(false);
            var bar = field(field(view, "composer"), "attachments");
            var selection = (AttachmentSelection) field(bar, "selection");
            waitFor(() -> selection.items().size() == 1, 10, () -> {
            });
            assertEquals(List.of("Module.bsl"), selection.references(project.getLocation().toFile().toPath()));
            var prompt = (Text) find(shell, "prompt");
            prompt.setText("Explain attached Module.bsl");
            find(shell, "send").notifyListeners(SWT.Selection, new Event());
            var response = (StyledText) find(shell, "response");
            waitFor(() -> response.getText().contains("Привет") && find(shell, "newThread").isEnabled(), 15, () -> {
            });
            assertTrue(selection.items().isEmpty());
            assertTrue(editor.isDirty());
            assertEquals("saved", Files.readString(file.getLocation().toFile().toPath()));
            assertFalse(client.snapshot().threadId().isBlank());
            find(shell, "attach").notifyListeners(SWT.Selection, new Event());
            popup = (Menu) field(controller, "popup");
            popup.getItem(0).notifyListeners(SWT.Selection, new Event());
            popup.setVisible(false);
            waitFor(() -> !selection.items().isEmpty(), 10, () -> {
            });
            find(shell, "newThread").notifyListeners(SWT.Selection, new Event());
            waitFor(() -> selection.items().isEmpty() && client.snapshot().threadId().isBlank(), 10, () -> {
            });
        } finally {
            if (view != null) {
                page.hideView(view);
            }
            client.close();
            client.termination().get(10, java.util.concurrent.TimeUnit.SECONDS);
            registration.unregister();
            page.closeEditor(editor, false);
            project.delete(true, true, null);
            workspace.save(true, null);
        }
    }

    @Test
    public void paletteFollowsDockedParentAndReleasesOldColors() throws Exception {
        var shell = new Shell(Display.getCurrent());
        var light = new Composite(shell, SWT.NONE);
        var dark = new Composite(shell, SWT.NONE);
        var lightColor = new Color(shell.getDisplay(), 240, 240, 240);
        var darkColor = new Color(shell.getDisplay(), 35, 38, 41);
        light.setBackground(lightColor);
        dark.setBackground(darkColor);
        var owner = new Composite(light, SWT.NONE);
        var type = Platform.getBundle("io.github.zhumaniezov.codex.edt")
                .loadClass("io.github.zhumaniezov.codex.edt.ui.ThemePalette");
        var palette = type.getConstructor(Control.class).newInstance(owner);
        var color = type.getMethod("color", String.class);
        var old = (Color) color.invoke(palette, "panel");
        try {
            assertEquals(lightColor.getRGB(), old.getRGB());
            assertTrue(owner.setParent(dark));
            var reload = type.getDeclaredMethod("reload");
            reload.setAccessible(true);
            reload.invoke(palette);
            assertEquals(darkColor.getRGB(), ((Color) color.invoke(palette, "panel")).getRGB());
            assertTrue(old.isDisposed());
            var current = (Color) color.invoke(palette, "panel");
            owner.dispose();
            assertTrue(current.isDisposed());
            assertFalse(darkColor.isDisposed());
        } finally {
            shell.dispose();
            lightColor.dispose();
            darkColor.dispose();
        }
    }

    @Test
    public void markdownKeepsGreetingVisibleAndDoesNotStealScrollOrSelection() throws Exception {
        var shell = new Shell(Display.getCurrent());
        shell.setLayout(new FillLayout());
        var body = new Composite(shell, SWT.NONE);
        body.setLayout(new FillLayout());
        var renderer = new io.github.zhumaniezov.codex.edt.ui.render.NativeMarkdownRenderer(body);
        var text = (StyledText) renderer.control();
        try {
            shell.setSize(360, 300);
            shell.open();
            shell.layout(true, true);
            renderer.render("**Здравствуйте**\n\nЗадайте вопрос.");
            waitFor(() -> text.getText().contains("Задайте"), 5, () -> {
            });
            assertEquals(0, text.getTopPixel());
            String content = java.util.stream.IntStream.range(0, 60).mapToObj(i -> "Line " + i + "\n\n")
                    .collect(java.util.stream.Collectors.joining());
            renderer.render(content);
            waitFor(() -> text.getText().contains("Line 59"), 5, () -> {
            });
            text.setTopPixel(0);
            text.setSelection(0, 4);
            renderer.render(content + "More");
            waitFor(() -> text.getText().contains("More"), 5, () -> {
            });
            assertEquals(0, text.getTopPixel());
            assertEquals(new Point(0, 4), text.getSelection());
        } finally {
            renderer.close();
            shell.dispose();
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void approvalBounds(Composite parent, org.eclipse.swt.graphics.Point edge) {
        for (var c : parent.getChildren()) {
            if (c.getData("codex.decision") != null) {
                var at = c.toDisplay(0, 0);
                assertTrue("Approval button fits narrow panel", at.x + c.getSize().x <= edge.x);
            }
            if (c instanceof Composite nested) {
                approvalBounds(nested, edge);
            }
        }
    }
}
