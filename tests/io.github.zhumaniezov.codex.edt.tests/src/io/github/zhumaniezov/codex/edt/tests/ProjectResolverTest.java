package io.github.zhumaniezov.codex.edt.tests;

import static org.junit.Assert.*;
import org.junit.Test;
import org.eclipse.core.resources.*;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import io.github.zhumaniezov.codex.edt.context.ProjectContextResolver;

public class ProjectResolverTest {
    private static IProject create() throws Exception {
        var p = ResourcesPlugin.getWorkspace().getRoot().getProject("resolver-" + java.util.UUID.randomUUID());
        p.create(null);
        p.open(null);
        return p;
    }

    private static IWorkbenchPage page(Object selection) {
        return (IWorkbenchPage) java.lang.reflect.Proxy.newProxyInstance(IWorkbenchPage.class.getClassLoader(),
                new Class[] { IWorkbenchPage.class }, (proxy, method,
                        args) -> method.getName().equals("getSelection") ? new StructuredSelection(selection) : null);
    }

    @Test
    public void navigatorSelectionDoesNotRequireEditor() throws Exception {
        var p = create();
        try (var resolver = new ProjectContextResolver()) {
            var c = resolver.capture(page(p), null, "");
            assertEquals(p.getName(), c.projectName());
            assertEquals("", c.modulePath());
        } finally {
            p.delete(true, true, null);
        }
    }

    @Test
    public void boundThreadWinsOverNavigator() throws Exception {
        var a = create();
        var b = create();
        try (var resolver = new ProjectContextResolver()) {
            assertEquals(a.getName(),
                    resolver.capture(page(b), null, ProjectContextResolver.directory(a)).projectName());
        } finally {
            a.delete(true, true, null);
            b.delete(true, true, null);
        }
    }

    @Test
    public void noWindowAndNoPageStillResolvesBoundProject() throws Exception {
        var p = create();
        try (var resolver = new ProjectContextResolver()) {
            assertEquals(p.getName(), resolver.capture(null, null, ProjectContextResolver.directory(p)).projectName());
        } finally {
            p.delete(true, true, null);
        }
    }

    @Test
    public void unavailableBoundProjectNeverFallsBackToOtherProject() throws Exception {
        var p = create();
        try (var resolver = new ProjectContextResolver()) {
            assertThrows(IllegalStateException.class,
                    () -> resolver.capture(page(p), null, p.getLocation().toOSString() + "/missing"));
            assertNull(ProjectContextResolver.find(""));
        } finally {
            p.delete(true, true, null);
        }
    }

    @Test
    public void resolverSkipsClosedProjects() throws Exception {
        var a = create();
        var b = create();
        try {
            a.close(null);
            assertEquals(b, ProjectContextResolver.first(a, b));
        } finally {
            a.delete(true, true, null);
            b.delete(true, true, null);
        }
    }
}
