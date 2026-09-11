package io.github.zhumaniezov.codex.edt.ui;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.zhumaniezov.codex.edt.client.ChatRequest;
import io.github.zhumaniezov.codex.edt.client.CodexClient;
import io.github.zhumaniezov.codex.edt.CodexPlugin;
import io.github.zhumaniezov.codex.edt.client.ConnectionInfo;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.github.zhumaniezov.codex.edt.context.EclipseContextProvider;

/** Закрепляемая панель; все обращения к виджетам выполняются на UI-потоке. */
public final class CodexView extends ViewPart {
    public static final String ID = "io.github.zhumaniezov.codex.edt.views.Codex";

    private final CodexClient client = CodexPlugin.createClient();
    private final EclipseContextProvider contextProvider = new EclipseContextProvider();
    private Text prompt;
    private Text response;
    private Button send;
    private Button reconnect;
    private Label mode;
    private Label diagnostic;
    private org.eclipse.swt.widgets.Display display;
    private ConnectionInfo connection;
    private String project = "не выбран";
    private boolean ready;
    private final AtomicReference<String> latestText = new AtomicReference<>();
    private final AtomicBoolean updateQueued = new AtomicBoolean();
    private int streamingUpdates;
    private boolean busy;
    private volatile boolean disposed;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));
        Label heading = new Label(parent, SWT.NONE);
        heading.setText("Codex");
        heading.setFont(JFaceResources.getHeaderFont());

        mode = new Label(parent, SWT.WRAP);
        mode.setData("codex.role", "status");
        mode.setText("Codex: подключение...");
        mode.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        diagnostic = new Label(parent, SWT.WRAP);
        diagnostic.setData("codex.role", "diagnostic");
        diagnostic.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        updateDiagnostic();
        reconnect = new Button(parent, SWT.PUSH);
        reconnect.setText("Переподключить");
        reconnect.addListener(SWT.Selection, event -> connect());

        new Label(parent, SWT.NONE).setText("Сообщение");
        prompt = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        prompt.setData("codex.role", "prompt");
        prompt.setTextLimit(32768);
        GridData promptLayout = new GridData(SWT.FILL, SWT.FILL, true, false);
        promptLayout.heightHint = 90;
        promptLayout.widthHint = 280;
        prompt.setLayoutData(promptLayout);

        send = new Button(parent, SWT.PUSH);
        send.setText("Send");
        send.setData("codex.role", "send");
        send.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        send.setEnabled(false);
        send.addListener(SWT.Selection, event -> sendMessage());
        prompt.addModifyListener(event -> updateSendButton());

        new Label(parent, SWT.NONE).setText("Ответ");
        response = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        response.setData("codex.role", "response");
        response.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        response.setText("Введите сообщение и нажмите Send.\n"
            + "Откройте BSL-модуль проекта. Codex работает только в режиме чтения.");
        client.setListener(new CodexClient.Listener() {
            public void status(String value) { ui(() -> setStatus(value)); }
            public void disconnected(Throwable error) {
                CodexPlugin.log("Соединение Codex прервано", error);
                ui(() -> { ready = false; busy = false; setStatus("Codex: отключён"); updateSendButton(); });
            }
        });
        connect();
    }

    private void connect() {
        busy = true;
        ready = false;
        connection = null;
        updateDiagnostic();
        setStatus("Codex: подключение...");
        updateSendButton();
        client.connect().whenComplete((info, error) -> ui(() -> {
            busy = false;
            ready = error == null;
            connection = info;
            updateDiagnostic();
            if (error == null) { setStatus("Codex: подключён — только чтение"); }
            else { showError(error); }
            updateSendButton();
        }));
    }

    private void sendMessage() {
        String message = prompt.getText().strip();
        if (message.isEmpty() || busy || !ready) {
            return;
        }
        busy = true;
        updateSendButton();
        try {
            var request = new ChatRequest(message, contextProvider.capture(getSite().getPage()));
            project = request.context().projectDirectory().isBlank() ? "не выбран"
                : request.context().projectName() + "\n" + request.context().projectDirectory();
            updateDiagnostic();
            response.setText("");
            streamingUpdates = 0;
            response.setData("codex.streamingUpdates", 0);
            client.send(request, this::stream).whenComplete((reply, error) -> {
                ui(() -> {
                    busy = false;
                    if (error == null) { response.setText(reply); }
                    else { showError(error); }
                    updateSendButton();
                });
            });
        } catch (RuntimeException error) {
            busy = false;
            showError(error);
            updateSendButton();
        }
    }

    private void updateSendButton() {
        send.setEnabled(ready && !busy && !prompt.getText().isBlank());
        reconnect.setEnabled(!busy);
    }

    private void stream(String text) {
        latestText.set(text);
        if (updateQueued.compareAndSet(false, true)) {
            ui(() -> {
                updateQueued.set(false);
                response.setText(latestText.get());
                response.setData("codex.streamingUpdates", ++streamingUpdates);
            });
        }
    }

    private void ui(Runnable task) {
        if (disposed || display.isDisposed()) { return; }
        try { display.asyncExec(() -> { if (!disposed) { task.run(); } }); }
        catch (org.eclipse.swt.SWTException error) { if (!display.isDisposed()) { throw error; } }
    }

    private void setStatus(String text) { mode.setText(text); mode.getParent().layout(true, true); }

    private void updateDiagnostic() {
        diagnostic.setText("Версия Codex: " + (connection == null ? "—" : connection.version())
            + "\nМодель: " + (connection == null ? "—" : connection.model()) + "\nПроект: " + project);
        diagnostic.getParent().layout(true, true);
    }

    private void showError(Throwable error) {
        while (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException)) { error = error.getCause(); }
        CodexPlugin.log("Ошибка клиента Codex", error);
        String message = error.getMessage() == null ? "Не удалось выполнить запрос. См. Error Log." : error.getMessage();
        setStatus(message.contains("Требуется вход") ? "Codex: требуется вход" : "Codex: ошибка");
        response.append("\n\n" + message);
    }

    @Override
    public void setFocus() {
        if (prompt != null && !prompt.isDisposed()) {
            prompt.setFocus();
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        CodexPlugin.release(client);
        super.dispose();
    }
}
