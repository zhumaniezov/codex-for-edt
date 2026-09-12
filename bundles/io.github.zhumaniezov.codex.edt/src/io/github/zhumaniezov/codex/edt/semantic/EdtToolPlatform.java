package io.github.zhumaniezov.codex.edt.semantic;

import com.google.gson.JsonObject;
import org.eclipse.core.resources.IProject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;

/** Независимый от транспорта фасад; approvals и turn routing остаются в session layer. */
public final class EdtToolPlatform implements AutoCloseable {
    private final EdtToolExecutionContext context;
    private final EdtMetadataService metadata;
    private final EdtToolRegistry registry;
    public EdtToolPlatform(EdtToolExecutionContext context) {
        this.context=context;metadata=new EdtMetadataService(context.project());registry=registry(context.project());
    }
    public static EdtToolRegistry registry(IProject project) {
        try(var services=new EdtServices()) {
            if(services.models()==null || services.factory()==null) throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","EDT model services unavailable");
            return new EdtToolRegistry(services.projects().getProject(project) instanceof IConfigurationProject);
        }
    }
    public EdtToolDescriptor tool(String name) {return registry.require(name);}
    public JsonObject read(String tool,JsonObject input) throws Exception {
        context.check();registry.require(tool);
        if(tool.equals("edt_capabilities")) {
            var value=registry.diagnostic();value.addProperty("project",context.project().getName());
            value.addProperty("edtCoreVersion",org.eclipse.core.runtime.Platform.getBundle("com._1c.g5.v8.dt.core").getVersion().toString());return value;
        }
        if(tool.equals("edt_get_problems")) return io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("problems",EdtValidationService.problems(context.project()));
        if(tool.equals("edt_debug_state"))return EdtDebugService.read(context.project());
        if(tool.equals("edt_bsl_read")) return new EdtBslService(context).read(input);
        if(tool.equals("edt_bsl_context")) {var request=input.deepCopy();request.addProperty("inspect",true);var result=new EdtBslService(context).read(request);result.remove("text");return result;}
        if(tool.equals("edt_open_resource")) {
            var file=new EdtBslService(context).file(MetadataPlan.text(input,"path"));
            if(!file.exists())throw new EdtToolException("OBJECT_NOT_FOUND",file.getName());
            return EdtBslService.ui(()->{
                context.check();var window=org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if(window==null || window.getActivePage()==null)throw new EdtToolException("PROJECT_CONTEXT_UNAVAILABLE","Workbench page unavailable");
                var editor=io.github.zhumaniezov.codex.edt.context.ProjectWriteGuard.openNativeEditor(file.getProject(),
                    ()->org.eclipse.ui.ide.IDE.openEditor(window.getActivePage(),file,true));
                var text=editor instanceof org.eclipse.ui.texteditor.ITextEditor value?value:editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
                if(input.has("line") && text!=null) {var document=text.getDocumentProvider().getDocument(text.getEditorInput());int line=input.get("line").getAsInt()-1;
                    if(line<0 || line>=document.getNumberOfLines())throw new EdtToolException("INVALID_PROPERTY","line");text.selectAndReveal(document.getLineOffset(line),0);}
                return io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("status","opened","path",file.getProjectRelativePath().toPortableString());
            });
        }
        return metadata.read(tool,input);
    }
    public MetadataPlan plan(String tool,JsonObject input) {
        registry.require(tool);
        if(tool.equals("edt_apply_metadata_plan"))return new MetadataPlan(input);
        String action=switch(tool){case "edt_bsl_edit"->"bslEdit";case "edt_bsl_format"->"bslFormat";case "edt_validate_project"->"validateProject";default->throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION",tool);};
        var operation=input.deepCopy();operation.addProperty("operation",action);
        return new MetadataPlan(io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("operations",java.util.List.of(operation)));
    }
    public JsonObject apply(MetadataPlan plan) throws Exception {
        context.check();var operation=plan.json().getAsJsonArray("operations").get(0).getAsJsonObject();
        String action=MetadataPlan.text(operation,"operation");
        if(action.startsWith("bsl"))return new EdtBslService(context).edit(operation);
        if(action.equals("validateProject")) {
            context.project().build(org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD,new org.eclipse.core.runtime.NullProgressMonitor());
            org.eclipse.core.runtime.jobs.Job.getJobManager().join(org.eclipse.core.resources.ResourcesPlugin.FAMILY_AUTO_BUILD,new org.eclipse.core.runtime.NullProgressMonitor());
            context.check();return io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object("status","completed","problems",EdtValidationService.problems(context.project()));
        }
        return metadata.apply(plan,context.valid());
    }
    @Override public void close() {metadata.close();}
}
