package io.github.zhumaniezov.codex.edt.tests;

import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.platform.version.Version;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

final class SemanticFixture {
    static IProject create(String name) {
        try {
            var factory = ServiceAccess.get(IModelObjectFactory.class, "service.name", "MdObjectFactory");
            Configuration config = factory.create(MdClassPackage.Literals.CONFIGURATION, Version.V8_3_27);
            config.setName("ПроверкаСемантики");
            return ServiceAccess.get(IConfigurationProjectManager.class).create(name, Version.V8_3_27, config,
                    new NullProgressMonitor());
        } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    static void delete(IProject project) {
        try {
            if (project.exists()) {
                project.delete(true, true, new NullProgressMonitor());
            }
        } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    static void active(IProject project, boolean expected) throws Exception {
        var manager = ServiceAccess.get(com._1c.g5.v8.dt.core.platform.IV8ProjectManager.class);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        while (manager.isServiceContextActive(project) != expected && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        org.junit.Assert.assertEquals("EDT context lifecycle", expected, manager.isServiceContextActive(project));
    }
}
