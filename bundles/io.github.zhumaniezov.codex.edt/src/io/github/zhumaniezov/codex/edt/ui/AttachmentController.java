package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.context.EditorContext;
import io.github.zhumaniezov.codex.edt.ui.presentation.AttachmentSelection;

final class AttachmentController implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "codex-attachments");
        thread.setDaemon(true);
        return thread;
    });
    private final Control owner;
    private final Display display;
    private final AttachmentChipBar bar;
    private final Supplier<EditorContext> context;
    private final Consumer<Throwable> errors;
    private volatile boolean closed;
    private long epoch;
    private Menu popup;

    AttachmentController(Control owner, AttachmentChipBar bar, Supplier<EditorContext> context,
            Consumer<Throwable> errors) {
        this.owner = owner;
        display = owner.getDisplay();
        this.bar = bar;
        this.context = context;
        this.errors = errors;
        owner.addDisposeListener(event -> { if (popup != null && !popup.isDisposed()) { popup.dispose(); } });
    }

    void menu() {
        if (popup != null && !popup.isDisposed()) { popup.dispose(); }
        var menu = new Menu(owner); popup = menu;
        var active = new MenuItem(menu, SWT.PUSH);
        active.setText(tr("attachCurrent"));
        active.addListener(SWT.Selection, event -> choose(true));
        var project = new MenuItem(menu, SWT.PUSH);
        project.setText(tr("attachProject"));
        project.addListener(SWT.Selection, event -> choose(false));
        var disk = new MenuItem(menu, SWT.PUSH);
        disk.setText(tr("attachOutsideUnavailable"));
        disk.setEnabled(false);
        menu.addListener(SWT.Hide, event -> ui(() -> {
            if (!menu.isDisposed()) {
                menu.dispose();
            }
        }));
        menu.setVisible(true);
    }

    private void choose(boolean active) {
        try {
            var snapshot = context.get();
            if (snapshot.projectDirectory().isBlank()) {
                throw new IllegalStateException(tr("attachmentProject"));
            }
            Path root = Path.of(snapshot.projectDirectory());
            Path file;
            if (active) {
                String module = snapshot.modulePath();
                String prefix = "/" + snapshot.projectName() + "/";
                if (!module.startsWith(prefix)) {
                    throw new IllegalStateException(tr("attachmentNoFile"));
                }
                file = root.resolve(module.substring(prefix.length()));
            } else {
                var dialog = new FileDialog(owner.getShell(), SWT.OPEN);
                dialog.setText(tr("attachProject"));
                dialog.setFilterPath(root.toString());
                String name = dialog.open();
                if (name == null) {
                    return;
                }
                file = Path.of(name);
            }
            long current = epoch;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return AttachmentSelection.validate(root, file);
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            }, worker).whenComplete((item, error) -> ui(() -> {
                if (epoch != current) {
                    return;
                }
                try {
                    if (error != null) {
                        throw new CompletionException(error);
                    }
                    bar.selection.add(item);
                    bar.refresh();
                } catch (RuntimeException failure) {
                    errors.accept(failure);
                }
            }));
        } catch (RuntimeException error) {
            errors.accept(error);
        }
    }

    CompletionStage<List<String>> references(EditorContext context) {
        epoch++;
        if (bar.selection.items().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return bar.selection.references(Path.of(context.projectDirectory()));
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, worker);
    }

    void clear() {
        epoch++;
        bar.clear();
    }

    private void ui(Runnable task) {
        if (closed || display.isDisposed()) {
            return;
        }
        try {
            display.asyncExec(() -> {
                if (!closed && !owner.isDisposed()) {
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
    public void close() {
        closed = true;
        worker.shutdownNow();
    }
}
