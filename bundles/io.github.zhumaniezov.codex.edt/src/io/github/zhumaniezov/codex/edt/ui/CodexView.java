package io.github.zhumaniezov.codex.edt.ui;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import io.github.zhumaniezov.codex.edt.CodexPlugin;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.*;
import io.github.zhumaniezov.codex.edt.context.*;

/** Соединяет native-компоненты; процесс и протокол находятся в клиентском слое. */
public final class CodexView extends ViewPart {
    public static final String ID = "io.github.zhumaniezov.codex.edt.views.Codex";
    private final CodexClient client = CodexPlugin.createClient();
    private final ContextProvider contextProvider = new EclipseContextProvider();
    private ComposerComponent composer;
    private ThreadListComponent threads;
    private ChatComponent chat;
    private StatusComponent status;
    private AccountComponent account;
    private Button reconnect;
    private Button newChat;
    private Button older;
    private Display display;
    private boolean ready;
    private boolean busy;
    private boolean running;
    private volatile boolean disposed;
    private String historyCursor = "";
    private String project = "не выбран";
    private final AtomicReference<String> latestText = new AtomicReference<>();
    private final AtomicBoolean updateQueued = new AtomicBoolean();

    @Override public void createPartControl(Composite parent) {
        display = parent.getDisplay(); parent.setLayout(new GridLayout(1, false));
        var header = new Composite(parent, SWT.NONE); header.setLayout(new GridLayout(4, false));
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        var title = new Label(header, SWT.NONE); title.setText("Codex"); title.setFont(JFaceResources.getHeaderFont());
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        newChat = new Button(header, SWT.PUSH); newChat.setText("Новый чат"); newChat.setData("codex.role", "newThread");
        newChat.addListener(SWT.Selection, event -> action(client.newThread(), ignored -> {
            chat.clear(); composer.prompt.setText(""); historyCursor = ""; older.setEnabled(false);
            composer.context(""); composer.prompt.setFocus();
        }));
        account = new AccountComponent(header, () -> action(client.logout(), ignored -> { ready = false; chat.clear(); update(); }));
        var settings = new Button(header, SWT.PUSH); settings.setText("⋯"); settings.setToolTipText("Настройки Codex для EDT");
        settings.addListener(SWT.Selection, event -> {
            PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(parent.getShell(),
                "io.github.zhumaniezov.codex.edt.preferences", null, null);
            if (dialog != null) { dialog.open(); status.showDiagnostic(); }
        });
        threads = new ThreadListComponent(parent, this::resume, this::loadThreads);
        var tools = new Composite(parent, SWT.NONE); tools.setLayout(new GridLayout(2, false));
        tools.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        reconnect = new Button(tools, SWT.PUSH); reconnect.setText("Переподключить"); reconnect.setData("codex.role", "reconnect");
        reconnect.addListener(SWT.Selection, event -> connect());
        older = new Button(tools, SWT.PUSH); older.setText("Предыдущие сообщения"); older.setEnabled(false);
        older.addListener(SWT.Selection, event -> action(client.history(historyCursor), page -> {
            chat.history(page.messages(), true); historyCursor = page.cursor(); update();
        }));
        status = new StatusComponent(parent);
        chat = new ChatComponent(parent);
        composer = new ComposerComponent(parent, this::sendMessage, this::stop, (model, effort) ->
            action(client.select(model, effort), ignored -> { }));
        client.setListener(new CodexClient.Listener() {
            public void status(String text) { ui(() -> status.text(text)); }
            public void changed(Snapshot snapshot) { ui(() -> CodexView.this.changed(snapshot)); }
            public void disconnected(Throwable error) {
                CodexPlugin.log("Соединение Codex прервано", error);
                ui(() -> { ready = false; busy = false; running = false; showError(error); status.text("Отключён"); update(); });
            }
        });
        connect();
    }
    private void changed(Snapshot snapshot) {
        running = snapshot.state().running();
        if (snapshot.state() == State.AUTH_REQUIRED || snapshot.state() == State.DISCONNECTED || snapshot.state() == State.CONNECTING) { ready = false; }
        else if (!snapshot.model().isBlank()) { ready = true; }
        composer.catalog(snapshot); status.details(snapshot, project); account.account(snapshot.account());
        threads.current(snapshot.threadId()); update();
    }
    private void connect() {
        busy = true; ready = false; running = false; status.text("Подключение..."); update();
        client.connect().whenComplete((connection, error) -> ui(() -> {
            busy = false; ready = error == null;
            if (error == null) {
                chat.clear(); historyCursor = ""; changed(client.snapshot()); ready = true;
                status.text("Подключён"); loadThreads("");
            } else { showError(error); }
            update();
        }));
    }
    private <T> void action(CompletionStage<T> result, Consumer<T> success) {
        busy = true; update();
        result.whenComplete((value, error) -> ui(() -> {
            busy = false;
            if (error != null) { showError(error); threads.current(client.snapshot().threadId()); } else { success.accept(value); }
            update();
        }));
    }
    private void loadThreads(String cursor) {
        if (!ready) { return; }
        client.threads(cursor).whenComplete((page, error) -> ui(() -> {
            if (error == null) { threads.page(page, !cursor.isBlank()); threads.current(client.snapshot().threadId()); update(); }
            else { showError(error); }
        }));
    }
    private void resume(String id) {
        if (busy || !ready) { return; }
        action(client.resume(id), page -> {
            chat.history(page.messages(), false); historyCursor = page.cursor(); composer.prompt.setText("");
            composer.context("Продолжение чата"); update();
        });
    }
    private void sendMessage() {
        String message = composer.prompt.getText().strip();
        if (message.isEmpty() || busy || !ready) { return; }
        try {
            var context = contextProvider.capture(getSite().getPage());
            project = context.projectName();
            status.details(client.snapshot(), project + "\n" + context.projectDirectory());
            String module = context.modulePath().substring(context.modulePath().lastIndexOf('/') + 1);
            composer.context(module + (context.dirty() ? " · несохранён" : ""));
            busy = true; update(); chat.begin(message); composer.prompt.setText("");
            client.send(new ChatRequest(message, context), this::stream).whenComplete((reply, error) -> ui(() -> {
                busy = false; running = false;
                if (error == null) { chat.finish(reply); loadThreads(""); }
                else { showError(error); composer.prompt.setText(message); }
                update();
            }));
        } catch (RuntimeException error) { busy = false; showError(error); update(); }
    }
    private void stop() {
        if (!running) { return; }
        client.interrupt().whenComplete((ignored, error) -> ui(() -> { if (error != null) { showError(error); } }));
    }
    private void stream(String text) {
        latestText.set(text);
        if (updateQueued.compareAndSet(false, true)) {
            ui(() -> { updateQueued.set(false); chat.stream(latestText.get()); });
        }
    }
    private void update() {
        composer.state(ready, busy, running);
        reconnect.setEnabled(!busy && !running); newChat.setEnabled(ready && !busy);
        threads.enabled(ready && !busy); account.enabled(!busy);
        older.setEnabled(ready && !busy && !historyCursor.isBlank());
    }
    private void showError(Throwable error) {
        while (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException)) { error = error.getCause(); }
        CodexPlugin.log("Ошибка клиента Codex", error);
        String message = error.getMessage() == null ? "Не удалось выполнить запрос. См. Error Log." : error.getMessage();
        message = io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.redact(message);
        status.text(message.contains("Требуется вход") ? "Требуется вход" : "Ошибка"); chat.error(message);
    }
    private void ui(Runnable task) {
        if (disposed || display.isDisposed()) { return; }
        try { display.asyncExec(() -> { if (!disposed) { task.run(); } }); }
        catch (org.eclipse.swt.SWTException error) { if (!display.isDisposed()) { throw error; } }
    }
    @Override public void setFocus() { if (composer != null && !composer.prompt.isDisposed()) { composer.prompt.setFocus(); } }
    @Override public void dispose() {
        disposed = true; if (chat != null) { chat.close(); } CodexPlugin.release(client); super.dispose();
    }
}
