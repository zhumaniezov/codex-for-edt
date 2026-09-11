package com.admglobal.codex.edt.ui;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import com.admglobal.codex.edt.client.ChatRequest;
import com.admglobal.codex.edt.client.CodexClient;
import com.admglobal.codex.edt.client.MockCodexClient;
import com.admglobal.codex.edt.context.EclipseContextProvider;

/** Закрепляемая панель; все обращения к виджетам выполняются на UI-потоке. */
public final class CodexView extends ViewPart {
    public static final String ID = "com.admglobal.codex.edt.views.Codex";

    private final CodexClient client = new MockCodexClient();
    private final EclipseContextProvider contextProvider = new EclipseContextProvider();
    private Text prompt;
    private Text response;
    private Button send;
    private boolean busy;
    private volatile boolean disposed;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        Label heading = new Label(parent, SWT.NONE);
        heading.setText("Codex");
        heading.setFont(JFaceResources.getHeaderFont());

        Label mode = new Label(parent, SWT.WRAP);
        mode.setText("Локальный тестовый режим");
        mode.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(parent, SWT.NONE).setText("Сообщение");
        prompt = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        prompt.setData("codex.role", "prompt");
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
            + "Для проверки контекста сначала выделите текст в открытом BSL-модуле.");
    }

    private void sendMessage() {
        String message = prompt.getText().strip();
        if (message.isEmpty() || busy) {
            return;
        }
        busy = true;
        updateSendButton();
        var display = prompt.getDisplay();
        try {
            var request = new ChatRequest(message, contextProvider.capture(getSite().getPage()));
            response.setText("Обработка…");
            client.send(request).whenComplete((reply, error) -> {
                if (disposed || display.isDisposed()) {
                    return;
                }
                display.asyncExec(() -> {
                    if (disposed || response.isDisposed()) {
                        return;
                    }
                    busy = false;
                    response.setText(error == null ? reply : "Не удалось получить тестовый ответ.");
                    updateSendButton();
                });
            });
        } catch (RuntimeException error) {
            busy = false;
            response.setText("Не удалось прочитать контекст или получить тестовый ответ.");
            updateSendButton();
        }
    }

    private void updateSendButton() {
        send.setEnabled(!busy && !prompt.getText().isBlank());
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
        client.close();
        super.dispose();
    }
}
