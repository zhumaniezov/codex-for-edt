package io.github.zhumaniezov.codex.edt.context;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.emf.ecore.EObject;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;

/** Выбор проекта независим от наличия открытого редактора. */
public final class ProjectContextResolver implements AutoCloseable {
    private final ServiceSupplier<IV8ProjectManager> projects = ServiceAccess.supplier(IV8ProjectManager.class,
            getClass());
    private final ServiceSupplier<IResourceLookup> resources = ServiceAccess.supplier(IResourceLookup.class,
            getClass());
    private IProject selected;

    public EditorContext capture(IWorkbenchPage page, Shell shell, String boundCwd) {
        EditorContext editor = new EclipseContextProvider().capture(page);
        IProject bound = boundCwd == null || boundCwd.isBlank() ? null : find(boundCwd);
        if (boundCwd != null && !boundCwd.isBlank() && bound == null) {
            throw new IllegalStateException(tr("projectBoundUnavailable"));
        }
        IProject active = editor.projectName().isBlank() ? null
                : ResourcesPlugin.getWorkspace().getRoot().getProject(editor.projectName());
        IProject chosen = first(bound, active);
        if (chosen == null) {
            chosen = first(selection(page), selected);
        }
        if (chosen == null) {
            var candidates = candidates();
            chosen = candidates.size() == 1 ? candidates.get(0) : choose(shell, candidates);
        }
        if (chosen == null) {
            throw new java.util.concurrent.CancellationException(tr("projectChoose"));
        }
        String cwd = directory(chosen);
        if (chosen.equals(active)) {
            return editor;
        }
        return new EditorContext(chosen.getName(), "", "", cwd);
    }

    public List<IProject> candidates() {
        return java.util.stream.Stream.concat(projects.get().getProjects(IConfigurationProject.class).stream(),projects.get().getProjects(com._1c.g5.v8.dt.core.platform.IExtensionProject.class).stream()).map(com._1c.g5.v8.dt.core.platform.IV8Project::getProject)
                .filter(ProjectContextResolver::usable).sorted(java.util.Comparator.comparing(IProject::getName))
                .toList();
    }

    public IProject choose(Shell shell, List<IProject> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException(tr("projectNone"));
        }
        var dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
            @Override
            public String getText(Object value) {
                return ((IProject) value).getName();
            }
        });
        dialog.setTitle(tr("projectChoose"));
        dialog.setMessage(tr("projectChooseMessage"));
        dialog.setElements(candidates.toArray());
        if (dialog.open() != Window.OK) {
            return null;
        }
        selected = (IProject) dialog.getFirstResult();
        return selected;
    }

    private IProject selection(IWorkbenchPage page) {
        if (page == null || !(page.getSelection() instanceof IStructuredSelection selection)) {
            return null;
        }
        Object value = selection.getFirstElement();
        if (value == null) {
            return null;
        }
        IResource resource = Adapters.adapt(value, IResource.class);
        if (resource != null) {
            return resource.getProject();
        }
        if (value instanceof EObject object) {
            return resources.get().getProject(object);
        }
        var v8 = Adapters.adapt(value, com._1c.g5.v8.dt.core.platform.IV8Project.class);
        return v8 == null ? null : v8.getProject();
    }

    public static IProject first(IProject... values) {
        for (IProject value : values) {
            if (usable(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean usable(IProject value) {
        return value != null && value.isOpen() && value.getLocationURI() != null
                && "file".equalsIgnoreCase(value.getLocationURI().getScheme());
    }

    public static String directory(IProject value) {
        try {
            return Path.of(value.getLocationURI()).toRealPath().toString();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(tr("projectBoundUnavailable"), error);
        }
    }

    public static IProject find(String directory) {
        if (directory == null || directory.isBlank()) {
            return null;
        }
        try {
            Path requested = Path.of(directory).toRealPath();
            for (var project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (usable(project) && requested.equals(Path.of(project.getLocationURI()).toRealPath())) {
                    return project;
                }
            }
        } catch (java.io.IOException | RuntimeException error) {
            return null;
        }
        return null;
    }

    @Override
    public void close() {
        projects.close();
        resources.close();
    }
}
