package io.github.zhumaniezov.codex.edt.semantic;

import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;
import com._1c.g5.v8.dt.core.platform.*;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;

/**
 * Публичные сервисы запрашиваются при каждом обращении; ссылки освобождаются
 * владельцем.
 */
public final class EdtServices implements AutoCloseable {
    private final ServiceSupplier<IV8ProjectManager> projects = ServiceAccess.supplier(IV8ProjectManager.class,
            getClass());
    private final ServiceSupplier<IBmModelManager> models = ServiceAccess.supplier(IBmModelManager.class, getClass());
    private final ServiceSupplier<IModelObjectFactory> factory = ServiceAccess.supplier(IModelObjectFactory.class,
            getClass(), "service.name", "MdObjectFactory");
    private final ServiceSupplier<ITopObjectFqnGenerator> names = ServiceAccess.supplier(ITopObjectFqnGenerator.class,
            getClass());
    private final ServiceSupplier<IResourceLookup> resources = ServiceAccess.supplier(IResourceLookup.class,
            getClass());
    private final ServiceSupplier<com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider> files = ServiceAccess
            .supplier(com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider.class, getClass());

    public IV8ProjectManager projects() {
        return projects.get();
    }

    public IBmModelManager models() {
        return models.get();
    }

    public IModelObjectFactory factory() {
        return factory.get();
    }

    public ITopObjectFqnGenerator names() {
        return names.get();
    }

    public IResourceLookup resources() {
        return resources.get();
    }

    public com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider files() {
        return files.get();
    }

    @Override
    public void close() {
        projects.close();
        models.close();
        factory.close();
        names.close();
        resources.close();
        files.close();
    }
}
