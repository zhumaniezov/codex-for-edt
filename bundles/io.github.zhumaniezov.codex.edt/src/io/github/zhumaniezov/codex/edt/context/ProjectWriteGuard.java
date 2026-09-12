package io.github.zhumaniezov.codex.edt.context;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import org.eclipse.core.resources.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.ide.ResourceUtil;

/**
 * UI-поток. Не допускает параллельного редактирования IDE buffer во время
 * записи агента.
 */
public final class ProjectWriteGuard implements AutoCloseable {
    private static final java.util.concurrent.ConcurrentMap<IProject, ProjectWriteGuard> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean valid = true;
    private int nativeEditorOpening;

    /** EDT активирует embedded editor ещё внутри createPartControl; проверяем его после возврата opener. */
    public static <T> T openNativeEditor(IProject project, java.util.concurrent.Callable<T> open) throws Exception {
        var guard=ACTIVE.get(project);
        if(guard==null)return open.call();
        if(!guard.valid || guard.closed)throw new IllegalStateException(tr("approvalExpired"));
        guard.nativeEditorOpening++;
        try {return open.call();}
        finally {
            guard.nativeEditorOpening--;
            if(guard.nativeEditorOpening==0 && !guard.closed) {
                guard.protect();
                if(!guard.valid)throw new IllegalStateException(tr("dirtyCannotProtect"));
            }
        }
    }

    public static boolean protectedProject(IProject project) {
        var guard = ACTIVE.get(project);
        return guard != null && guard.valid;
    }

    private final IWorkbench workbench;
    private final IProject project;
    private final Runnable abort;
    private final Map<Control, Boolean> locked = new IdentityHashMap<>();
    private final List<IWorkbenchPage> pages = new ArrayList<>();
    private boolean closed;
    private final IPartListener2 parts = new IPartListener2() {
        @Override
        public void partOpened(IWorkbenchPartReference part) {
            protect();
        }

        @Override
        public void partInputChanged(IWorkbenchPartReference part) {
            protect();
        }

        @Override
        public void partActivated(IWorkbenchPartReference part) {
            protect();
        }
    };
    private final IWindowListener windows = new IWindowListener() {
        public void windowOpened(IWorkbenchWindow window) {
            protect();
        }

        public void windowActivated(IWorkbenchWindow window) {
            protect();
        }

        public void windowDeactivated(IWorkbenchWindow window) {
        }

        public void windowClosed(IWorkbenchWindow window) {
        }
    };

    private ProjectWriteGuard(IWorkbench workbench, IProject project, Runnable abort) {
        this.workbench = workbench;
        this.project = project;
        this.abort = abort;
    }

    public static IProject project(String directory) {
        try {
            Path root = Path.of(directory).toRealPath();
            for (var p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (p.isOpen() && p.getLocationURI() != null && "file".equals(p.getLocationURI().getScheme())
                        && root.equals(Path.of(p.getLocationURI()).toRealPath())) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        throw new IllegalStateException(tr("agentProjectRequired"));
    }

    public static ProjectWriteGuard acquire(IWorkbench workbench, Shell shell, String directory, Runnable abort) {
        var guard = new ProjectWriteGuard(workbench, project(directory), abort);
        if (ACTIVE.containsKey(guard.project)) {
            throw new IllegalStateException(tr("projectBusy"));
        }
        var dirty = guard.editors().stream().filter(IEditorPart::isDirty).toList();
        if (!dirty.isEmpty()) {
            String names = dirty.stream().map(p -> p.getEditorInput().getName()).distinct()
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            int choice = new MessageDialog(shell, tr("dirtyTitle"), null, tr("dirtyRequired") + "\n\n" + names,
                    MessageDialog.WARNING, new String[] { tr("dirtySave"), tr("cancel") }, 1).open();
            if (choice != 0) {
                return null;
            }
            for (var editor : dirty) {
                if (!editor.getSite().getPage().saveEditor(editor, false) || editor.isDirty()) {
                    return null;
                }
            }
        }
        try {
            guard.lockEditors();
            workbench.addWindowListener(guard.windows);
            ACTIVE.put(guard.project, guard);
            return guard;
        } catch (RuntimeException error) {
            guard.close();
            throw error;
        }
    }

    private List<IEditorPart> editors() {
        var result = new ArrayList<IEditorPart>();
        for (var window : workbench.getWorkbenchWindows()) {
            for (var page : window.getPages()) {
                for (var ref : page.getEditorReferences()) {
                    var editor = ref.getEditor(false);
                    if (editor == null) {
                        continue;
                    }
                    if (editor.getEditorInput() instanceof org.eclipse.compare.CompareEditorInput compare
                            && !compare.getCompareConfiguration().isLeftEditable()
                            && !compare.getCompareConfiguration().isRightEditable() && !editor.isDirty()) {
                        continue;
                    }
                    var file = ResourceUtil.getFile(editor.getEditorInput());
                    // Неизвестный редактор может стать dirty во время turn. Если нельзя
                    // установить принадлежность и защитить ввод, запись не начинается.
                    if (file == null || project.equals(file.getProject())) {
                        result.add(editor);
                    }
                }
            }
        }
        return result;
    }

    private void lockEditors() {
        for (var window : workbench.getWorkbenchWindows()) {
            for (var page : window.getPages()) {
                if (!pages.contains(page)) {
                    pages.add(page);
                    page.addPartListener(parts);
                }
            }
        }
        for (var editor : editors()) {
            if (editor.isDirty()) {
                throw new IllegalStateException(tr("dirtyRequired"));
            }
            Control control = editor.getAdapter(Control.class);
            if (control == null) {
                var operations = editor.getAdapter(ITextOperationTarget.class);
                if (operations instanceof ITextViewer viewer) {
                    control = viewer.getTextWidget();
                }
            }
            if (control == null || control.isDisposed()) {
                throw new IllegalStateException(tr("dirtyCannotProtect") + " " + editor.getTitle());
            }
            if (!locked.containsKey(control)) {
                locked.put(control, control.getEnabled());
                control.setEnabled(false);
            }
        }
    }

    private void protect() {
        if (closed || nativeEditorOpening > 0) {
            return;
        }
        try {
            lockEditors();
        } catch (RuntimeException error) {
            valid = false;
            io.github.zhumaniezov.codex.edt.CodexPlugin.log(error.getMessage(), error);
            abort.run();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        valid = false;
        ACTIVE.remove(project, this);
        workbench.removeWindowListener(windows);
        pages.forEach(p -> p.removePartListener(parts));
        locked.forEach((control, enabled) -> {
            if (!control.isDisposed()) {
                control.setEnabled(enabled);
            }
        });
        locked.clear();
    }
}
