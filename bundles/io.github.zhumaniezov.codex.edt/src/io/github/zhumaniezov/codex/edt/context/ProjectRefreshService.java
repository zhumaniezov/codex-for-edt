package io.github.zhumaniezov.codex.edt.context;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.util.concurrent.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;

public final class ProjectRefreshService {
    private ProjectRefreshService() {
    }

    public static CompletionStage<Void> refresh(String directory) {
        var result = new CompletableFuture<Void>();
        try {
            IProject project = ProjectWriteGuard.project(directory);
            Job job = new Job(tr("agentRefresh")) {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    try {
                        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                        result.complete(null);
                        return Status.OK_STATUS;
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                        return new Status(IStatus.ERROR, "io.github.zhumaniezov.codex.edt", tr("agentRefresh"), error);
                    }
                }
            };
            job.setRule(project);
            job.setSystem(true);
            job.schedule();
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }
}
