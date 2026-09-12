package io.github.zhumaniezov.codex.edt.semantic;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import com.google.gson.JsonArray;
import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;

public final class EdtValidationService {
    private EdtValidationService() {
    }

    public static JsonArray problems(IProject project) throws CoreException {
        var values = new JsonArray();
        for (var marker : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
            if (values.size() >= 100) {
                break;
            }
            values.add(object("path", marker.getResource().getProjectRelativePath().toPortableString(), "line",
                    marker.getAttribute(IMarker.LINE_NUMBER, 0), "severity", marker.getAttribute(IMarker.SEVERITY, 0),
                    "message", marker.getAttribute(IMarker.MESSAGE, "")));
        }
        return values;
    }
}
