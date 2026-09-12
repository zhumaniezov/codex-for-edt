package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import java.util.Arrays;
import com.google.gson.*;
import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.*;

/** Читает только состояние запусков, явно сопоставленных текущему проекту. */
public final class EdtDebugService {
    private EdtDebugService() { }
    public static JsonObject read(IProject project) throws Exception {
        var plugin=DebugPlugin.getDefault();
        if(plugin==null)throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","Eclipse debug service unavailable");
        var manager=plugin.getLaunchManager();var configurations=new JsonArray();var launches=new JsonArray();
        for(var configuration:manager.getLaunchConfigurations()) if(belongs(configuration,project))
            configurations.add(object("name",configuration.getName(),"type",configuration.getType().getIdentifier()));
        for(var launch:manager.getLaunches()) if(belongs(launch.getLaunchConfiguration(),project)) {
            var targets=new JsonArray();
            for(var target:launch.getDebugTargets())targets.add(object("name",target.getName(),"terminated",target.isTerminated(),"suspended",target.isSuspended()));
            launches.add(object("configuration",launch.getLaunchConfiguration().getName(),"mode",launch.getLaunchMode(),"terminated",launch.isTerminated(),"targets",targets));
        }
        var breakpoints=new JsonArray();
        for(var breakpoint:plugin.getBreakpointManager().getBreakpoints()) {
            var marker=breakpoint.getMarker();
            if(marker!=null && marker.exists() && project.equals(marker.getResource().getProject()))
                breakpoints.add(object("path",marker.getResource().getProjectRelativePath().toPortableString(),"enabled",breakpoint.isEnabled(),"line",marker.getAttribute(org.eclipse.core.resources.IMarker.LINE_NUMBER,-1)));
        }
        return object("configurations",configurations,"launches",launches,"breakpoints",breakpoints,"unmappedConfigurationsExcluded",true);
    }
    private static boolean belongs(ILaunchConfiguration configuration,IProject project) throws Exception {
        if(configuration==null)return false;
        var resources=configuration.getMappedResources();
        return resources!=null && Arrays.stream(resources).anyMatch(r->r!=null && project.equals(r.getProject()));
    }
}
