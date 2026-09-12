package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
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
import io.github.zhumaniezov.codex.edt.Messages;
import io.github.zhumaniezov.codex.edt.client.*;
import io.github.zhumaniezov.codex.edt.client.SessionData.*;
import io.github.zhumaniezov.codex.edt.context.*;

/**
 * Соединяет native-компоненты; процесс и протокол находятся в клиентском слое.
 */
public final class CodexView extends ViewPart {
    public static final String ID = "io.github.zhumaniezov.codex.edt.views.Codex";
    private final CodexClient client = new DeferredCodexClient(CodexPlugin::createClient, CodexPlugin::release);
    private final ContextProvider contextProvider = new EclipseContextProvider();
    private ComposerComponent composer;
    private ThreadListComponent threads;
    private ChatComponent chat;
    private AgentActivityComponent activity;
    private ProjectWriteGuard writeGuard;
    private StatusComponent status;
    private AccountComponent account;
    private IconButton reconnect;
    private IconButton newChat;
    private IconButton older;
    private FileLinkService links;
    private IconButton refresh;
    private AttachmentController attachments;
    private ThemePalette palette;
    private Display display;
    private Composite root;
    private volatile long responseEpoch;
    private boolean ready;
    private boolean busy;
    private boolean running;
    private volatile boolean disposed;
    private String historyCursor = "";
    private String project = tr("text033");
    private final AtomicReference<ResponseUpdate> latestText = new AtomicReference<>();
    private final AtomicBoolean updateQueued = new AtomicBoolean();
    private final AtomicReference<AgentActivity.Snapshot> latestActivity = new AtomicReference<>();
    private final AtomicBoolean activityQueued = new AtomicBoolean();

    private record ResponseUpdate(long epoch, String text) {
    }

    @Override
    public void createPartControl(Composite parent) {
        root = parent;
        display = parent.getDisplay();
        parent.addDisposeListener(event -> closeResources());
        var layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        layout.verticalSpacing = 8;
        parent.setLayout(layout);
        palette = new ThemePalette(parent);
        palette.apply(parent, "panel", "text");
        SettingsAccess.attach(client);
        links = new FileLinkService(parent, () -> client.snapshot().cwd(), () -> getSite().getPage());
        var header = new HeaderComponent(parent, palette);
        newChat = header.newChat;
        refresh = header.refresh;
        account = header.account;
        var settings = header.settings;
        newChat.addListener(SWT.Selection, event -> action(client.newThread(), ignored -> {
            chat.clear();
            if (activity != null) {
                activity.clear();
            }
            composer.prompt.setText("");
            attachments.clear();
            historyCursor = "";
            older.setEnabled(false);
            composer.context("");
            composer.prompt.setFocus();
        }));
        refresh.addListener(SWT.Selection, event -> loadThreads(""));
        settings.addListener(SWT.Selection, event -> {
            PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(parent.getShell(),
                    "io.github.zhumaniezov.codex.edt.preferences", null, null);
            if (dialog != null) {
                dialog.open();
                status.showDiagnostic();
            }
        });
        threads = new ThreadListComponent(parent, palette, this::resume, this::loadThreads);
        var tools = new Composite(parent, SWT.NONE);
        tools.setLayout(new GridLayout(4, false));
        palette.apply(tools, "panel", "muted");
        var conversationLabel = new Label(tools, SWT.NONE);
        conversationLabel.setText(tr("conversation"));
        conversationLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        palette.apply(conversationLabel, "panel", "muted");
        tools.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        status = new StatusComponent(parent, tools, palette);
        reconnect = new IconButton(tools, palette, "refresh", Messages.RECONNECT(), false);
        reconnect.setData("codex.role", "reconnect");
        reconnect.addListener(SWT.Selection, event -> connect());
        older = new IconButton(tools, palette, "history", tr("text035"), false);
        older.setEnabled(false);
        older.addListener(SWT.Selection, event -> action(client.history(historyCursor), page -> {
            chat.history(page.messages(), true);
            historyCursor = page.cursor();
            update();
        }));
        chat = new ChatComponent(parent, palette, links::open);
        activity = new AgentActivityComponent(parent, palette,
                (key, decision) -> client.approve(key, decision).whenComplete((v, error) -> ui(() -> {
                    if (error != null) {
                        showError(error);
                    }
                })), links::open);
        composer = new ComposerComponent(parent, palette, this::sendMessage, this::stop,
                (model, effort) -> action(client.select(model, effort), ignored -> {
                }), () -> attachments.menu());
        composer.permissions.selection = mode -> action(client.selectPermission(mode), ignored -> {
        });
        attachments = new AttachmentController(parent, composer.attachments,
                () -> contextProvider.capture(getSite().getPage()), this::showError);
        client.setListener(new CodexClient.Listener() {
            public void approval(AgentApproval value) {
                ui(() -> {
                    activity.approval(value);
                    threads.compact(true);
                });
            }

            public void approvalResolved(String key) {
                ui(() -> {
                    activity.resolved(key);
                    threads.compact(activity.hasApprovals());
                });
            }

            public void activity(AgentActivity.Snapshot value) {
                latestActivity.set(value);
                if (activityQueued.compareAndSet(false, true)) {
                    ui(() -> {
                        activityQueued.set(false);
                        var newest = latestActivity.getAndSet(null);
                        if (newest != null) {
                            activity.activity(newest);
                        }
                    });
                }
            }

            public void resourcesChanged(String directory) {
                ProjectRefreshService.refresh(directory).whenComplete((v, error) -> {
                    if (error != null) {
                        ui(() -> showError(error));
                    }
                });
            }

            public void status(String text) {
                ui(() -> status.text(text));
            }

            public void changed(Snapshot snapshot) {
                ui(() -> CodexView.this.changed(snapshot));
            }

            public void disconnected(Throwable error) {
                CodexPlugin.log(tr("text039"), error);
                ui(() -> {
                    ready = false;
                    busy = false;
                    running = false;
                    showError(error);
                    status.text(tr("text026"));
                    update();
                });
            }
        });
        busy = true;
        update();
        ui(this::connect);
    }

    private void changed(Snapshot snapshot) {
        running = snapshot.state().running();
        if (snapshot.state() == State.AUTH_REQUIRED || snapshot.state() == State.DISCONNECTED
                || snapshot.state() == State.CONNECTING) {
            ready = false;
        } else if (!snapshot.model().isBlank()) {
            ready = true;
        }
        composer.permissions.update(client.permissionMode(), client.permissionOptions());
        composer.catalog(snapshot);
        status.details(snapshot, project);
        account.account(snapshot.account());
        threads.current(snapshot.threadId());
        update();
    }

    private void connect() {
        busy = true;
        ready = false;
        running = false;
        responseEpoch++;
        status.text(Messages.CONNECTING());
        update();
        client.connect().whenComplete((connection, error) -> ui(() -> {
            busy = false;
            ready = error == null;
            if (error == null) {
                chat.clear();
                if (activity != null) {
                    activity.clear();
                }
                attachments.clear();
                historyCursor = "";
                changed(client.snapshot());
                ready = true;
                status.text(tr("text027"));
                loadThreads("");
            } else {
                showError(error);
                if (client.snapshot().state() != State.AUTH_REQUIRED) {
                    status.text(Messages.CONNECTION_ERROR());
                }
            }
            update();
        }));
    }

    private <T> void action(CompletionStage<T> result, Consumer<T> success) {
        busy = true;
        update();
        result.whenComplete((value, error) -> ui(() -> {
            busy = false;
            if (error != null) {
                showError(error);
                threads.current(client.snapshot().threadId());
            } else {
                success.accept(value);
            }
            update();
        }));
    }

    private void loadThreads(String cursor) {
        if (!ready) {
            return;
        }
        client.threads(cursor).whenComplete((page, error) -> ui(() -> {
            if (error == null) {
                threads.page(page, !cursor.isBlank());
                threads.current(client.snapshot().threadId());
                update();
            } else {
                showError(error);
            }
        }));
    }

    private void resume(String id) {
        if (busy || !ready) {
            return;
        }
        action(client.resume(id), page -> {
            activity.clear();
            chat.history(page.messages(), false);
            historyCursor = page.cursor();
            composer.prompt.setText("");
            attachments.clear();
            composer.context(tr("text036"));
            update();
        });
    }

    private void sendMessage() {
        String message = composer.prompt.getText().strip();
        if (message.isEmpty() || busy || !ready) {
            return;
        }
        try {
            var captured = contextProvider.capture(getSite().getPage());
            if (client.permissionMode().writes()) {
                String protectedDirectory = captured.projectDirectory();
                writeGuard = ProjectWriteGuard.acquire(getSite().getWorkbenchWindow().getWorkbench(), root.getShell(),
                        captured.projectDirectory(), () -> {
                            client.interrupt();
                            status.text(tr("dirtyRequired"));
                        });
                if (writeGuard == null) {
                    return;
                }
                captured = contextProvider.capture(getSite().getPage());
                if (!protectedDirectory.equals(captured.projectDirectory())) {
                    throw new IllegalStateException(tr("agentContextChanged"));
                }
            }
            var context = io.github.zhumaniezov.codex.edt.settings.EdtPreferencesService.context(captured);
            project = context.projectName();
            status.details(client.snapshot(), project + "\n" + context.projectDirectory());
            String module = context.modulePath().substring(context.modulePath().lastIndexOf('/') + 1);
            composer.context(module + (context.dirty() ? tr("text037") : ""));
            long epoch = ++responseEpoch;
            busy = true;
            update();
            chat.begin(message);
            composer.prompt.setText("");
            attachments.references(context)
                    .thenCompose(
                            refs -> client.send(new ChatRequest(message, context, refs), text -> stream(epoch, text)))
                    .whenComplete((reply, error) -> {
                        CompletionStage<Void> terminated = client.snapshot().state() == State.DISCONNECTED
                                ? client.termination()
                                : java.util.concurrent.CompletableFuture.completedFuture(null);
                        CompletionStage<Void> refresh = terminated.thenCompose(v -> client.permissionMode().writes()
                                ? ProjectRefreshService.refresh(context.projectDirectory())
                                : java.util.concurrent.CompletableFuture.completedFuture(null));
                        refresh.whenComplete((ignored, refreshError) -> ui(() -> {
                            releaseGuard();
                            if (epoch != responseEpoch) {
                                return;
                            }
                            responseEpoch++;
                            busy = false;
                            running = false;
                            if (error == null) {
                                attachments.clear();
                                chat.finish(reply);
                                loadThreads("");
                            } else {
                                showError(error);
                                composer.prompt.setText(message);
                            }
                            if (refreshError != null) {
                                showError(refreshError);
                            }
                            update();
                        }));
                    });
        } catch (RuntimeException error) {
            releaseGuard();
            busy = false;
            showError(error);
            update();
        }
    }

    private void releaseGuard() {
        if (writeGuard != null) {
            writeGuard.close();
            writeGuard = null;
        }
    }

    private void stop() {
        if (!running) {
            return;
        }
        client.interrupt().whenComplete((ignored, error) -> ui(() -> {
            if (error != null) {
                showError(error);
            }
        }));
    }

    private void stream(long epoch, String text) {
        if (disposed || epoch != responseEpoch) {
            return;
        }
        latestText.set(new ResponseUpdate(epoch, text));
        if (updateQueued.compareAndSet(false, true)) {
            ui(() -> {
                updateQueued.set(false);
                var value = latestText.getAndSet(null);
                if (value != null && value.epoch() == responseEpoch) {
                    chat.stream(value.text());
                }
            });
        }
    }

    private void update() {
        composer.state(ready, busy, running);
        reconnect.setEnabled(!busy && !running);
        newChat.setEnabled(ready && !busy);
        refresh.setEnabled(ready && !busy);
        threads.enabled(ready && !busy);
        account.enabled(!busy);
        older.setEnabled(ready && !busy && !historyCursor.isBlank());
    }

    private void showError(Throwable error) {
        while (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException)) {
            error = error.getCause();
        }
        CodexPlugin.log(tr("text040"), error);
        String message = error.getMessage() == null ? tr("text038") : error.getMessage();
        message = io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.redact(message);
        status.text(message.contains(tr("text031")) ? tr("text031") : tr("text025"));
        chat.error(message);
    }

    private void ui(Runnable task) {
        if (disposed || display == null || display.isDisposed()) {
            return;
        }
        try {
            display.asyncExec(() -> {
                if (!disposed && root != null && !root.isDisposed()) {
                    task.run();
                }
            });
        } catch (org.eclipse.swt.SWTException error) {
            if (!display.isDisposed()) {
                throw error;
            }
        }
    }

    @Override
    public void setFocus() {
        if (composer != null && !composer.prompt.isDisposed()) {
            composer.prompt.setFocus();
        }
    }

    private void closeResources() {
        if (disposed) {
            return;
        }
        disposed = true;
        responseEpoch++;
        latestText.set(null);
        if (attachments != null) {
            attachments.close();
        }
        if (chat != null) {
            chat.close();
        }
        SettingsAccess.detach(client);
        if (links != null) {
            links.close();
        }
        client.close();
        var guard = writeGuard;
        writeGuard = null;
        if (guard != null) {
            client.termination().whenComplete((v, error) -> {
                if (!display.isDisposed()) {
                    try {
                        display.asyncExec(() -> guard.close());
                    } catch (org.eclipse.swt.SWTException ignored) {
                    }
                }
            });
        }
    }

    @Override
    public void dispose() {
        closeResources();
        super.dispose();
    }
}
