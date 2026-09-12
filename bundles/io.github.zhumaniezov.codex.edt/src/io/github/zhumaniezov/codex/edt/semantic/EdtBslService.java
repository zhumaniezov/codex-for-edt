package io.github.zhumaniezov.codex.edt.semantic;

import static io.github.zhumaniezov.codex.edt.protocol.CodexProtocol.object;
import static io.github.zhumaniezov.codex.edt.semantic.MetadataPlan.text;
import java.util.*;
import java.util.concurrent.Callable;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.*;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.model.*;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.formatting2.*;
import org.eclipse.xtext.formatting2.regionaccess.*;
import com.google.gson.*;
import com._1c.g5.v8.dt.bsl.model.Method;

/** BSL изменяется как текст Xtext document; AST используется только для чтения. */
public final class EdtBslService {
    private final EdtToolExecutionContext context;
    public EdtBslService(EdtToolExecutionContext context) {this.context=context;}
    public IFile file(String path) throws Exception {
        var relative=org.eclipse.core.runtime.Path.fromPortableString(path.replace('\\','/'));
        if(relative.isAbsolute() || relative.getDevice()!=null || Arrays.asList(relative.segments()).contains("..") || relative.segmentCount()==0)
            throw new EdtToolException("PROJECT_BOUNDARY","Project-relative path required");
        var file=context.project().getFile(relative);
        var root=java.nio.file.Path.of(context.project().getLocationURI()).toRealPath();
        if(file.isLinked(IResource.CHECK_ANCESTORS) || file.getLocationURI()==null || !io.github.zhumaniezov.codex.edt.client.AgentPolicy.inside(root,java.nio.file.Path.of(file.getLocationURI()).toString()))
            throw new EdtToolException("PROJECT_BOUNDARY",path);
        return file;
    }
    private record Access(IFile file,IEditorInput input,IDocumentProvider provider,IXtextDocument document,boolean owned,boolean dirty) { }
    private Access connect(IFile file) throws Exception {
        if(!"bsl".equalsIgnoreCase(file.getFileExtension())) throw new EdtToolException("INVALID_PROPERTY","BSL module required");
        URI moduleUri;
        try(var services=new EdtServices();var names=com._1c.g5.wiring.ServiceAccess.supplier(com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter.class,getClass())) {
            var fqn=names.get().getFqn(file);
            moduleUri=services.models().executeReadOnlyTask(tx->{
                var module=fqn==null?null:tx.getTopObjectByFqn(services.models().getBmNamespace(file.getProject()),fqn.toString());
                return module==null?URI.createPlatformResourceURI(file.getFullPath().toPortableString(),true):org.eclipse.emf.ecore.util.EcoreUtil.getURI((org.eclipse.emf.ecore.EObject)module);
            });
        }
        return ui(()->{
            for(var window:PlatformUI.getWorkbench().getWorkbenchWindows()) for(var page:window.getPages()) for(var ref:page.getEditorReferences()) {
                var editor=ref.getEditor(false);if(editor==null)continue;
                var textEditor=findEditor(editor,file);
                if(textEditor!=null && textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput()) instanceof IXtextDocument doc)
                    return new Access(file,textEditor.getEditorInput(),textEditor.getDocumentProvider(),doc,false,textEditor.isDirty());
            }
            var resourceProvider=IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(URI.createPlatformResourceURI(file.getFullPath().toPortableString(),true));
            if(!file.exists() && resourceProvider!=null) {
                var editor=io.github.zhumaniezov.codex.edt.context.ProjectWriteGuard.openNativeEditor(file.getProject(),
                    ()->resourceProvider.get(org.eclipse.xtext.ui.editor.IURIEditorOpener.class).open(moduleUri,false));
                var textEditor=editor==null?null:findEditor(editor,file);
                if(textEditor!=null && textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput()) instanceof IXtextDocument doc)
                    return new Access(file,textEditor.getEditorInput(),textEditor.getDocumentProvider(),doc,false,textEditor.isDirty());
                throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","Native BSL editor could not open the module: "+moduleUri);
            }
            var provider=resourceProvider==null?null:resourceProvider.get(XtextDocumentProvider.class);
            if(provider==null)throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","BSL document provider unavailable");
            var input=new FileEditorInput(file);provider.connect(input);
            if(!(provider.getDocument(input) instanceof IXtextDocument doc)){provider.disconnect(input);throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","IXtextDocument unavailable");}
            return new Access(file,input,provider,doc,true,false);
        });
    }
    private static ITextEditor findEditor(IEditorPart editor,IFile file) {
        if(editor instanceof MultiPageEditorPart multi) {
            for(var child:multi.findEditors(new FileEditorInput(file))) {var found=findEditor(child,file);if(found!=null)return found;}
            if(multi.getSelectedPage() instanceof IEditorPart selected && selected!=editor) {var found=findEditor(selected,file);if(found!=null)return found;}
        }
        var textEditor=editor instanceof ITextEditor text?text:editor.getAdapter(ITextEditor.class);
        return textEditor!=null && file.equals(ResourceUtil.getFile(textEditor.getEditorInput()))?textEditor:null;
    }
    public JsonObject read(JsonObject input) throws Exception {
        context.check();var access=connect(file(text(input,"path")));
        try {
            String content=ui(access.document::get);
            int offset=input.has("offset")?input.get("offset").getAsInt():0;
            if(offset<0 || offset>content.length())throw new EdtToolException("INVALID_PROPERTY","offset");
            int end=Math.min(content.length(),offset+128000);
            var result=object("path",text(input,"path"),"dirty",access.dirty,"sha256",hash(content),"text",content.substring(offset,end),"offset",offset,"totalLength",content.length(),"truncated",end<content.length());
            result.add("syntax",access.document.readOnly(resource->{
                var methods=new JsonArray();
                resource.getAllContents().forEachRemaining(value->{if(value instanceof Method method){var node=NodeModelUtils.getNode(method);methods.add(object("name",method.getName(),"kind",method.eClass().getName(),"export",method.isExport(),"parameters",method.getFormalParams().stream().map(p->p.getName()).toList(),"offset",node==null?null:node.getOffset(),"length",node==null?null:node.getLength()));}});
                var errors=resource.getErrors().stream().map(e->object("message",e.getMessage(),"line",e.getLine(),"column",e.getColumn())).toList();
                var leaf=resource.getParseResult()==null?null:NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(),offset);
                var selected=leaf==null?null:NodeModelUtils.findActualSemanticObjectFor(leaf);
                var syntax=object("methods",methods,"parseErrors",errors,"elementAtOffset",selected==null?null:selected.eClass().getName());
                if(input.has("inspect") && input.get("inspect").getAsBoolean() && selected!=null) {
                    org.eclipse.emf.ecore.EObject owner=selected;
                    while(owner!=null && !(owner instanceof com._1c.g5.v8.dt.mcore.Environmental))owner=owner.eContainer();
                    if(owner instanceof com._1c.g5.v8.dt.mcore.Environmental environmental) {
                        var environments=environmental.environments();syntax.addProperty("environments",environments.toString());
                        var provider=resource.getResourceServiceProvider();
                        syntax.add("types",object("values",provider.get(com._1c.g5.v8.dt.bsl.resource.TypesComputer.class).computeTypes(selected,environments).stream().map(t->t.getName()).toList()));
                        var module=resource.getContents().stream().filter(com._1c.g5.v8.dt.bsl.model.Module.class::isInstance).map(com._1c.g5.v8.dt.bsl.model.Module.class::cast).findFirst().orElse(null);
                        var method=selected instanceof Method m?m:org.eclipse.xtext.EcoreUtil2.getContainerOfType(selected,Method.class);
                        var scopeValues=new JsonArray();var names=new HashSet<String>();boolean truncated=false;
                        if(module!=null) for(var environment:environments.toArray()) {
                            var spec=com._1c.g5.v8.dt.bsl.model.BslFactory.eINSTANCE.createMethodsScopeSpec();spec.setModule(module);spec.setMethod(method);spec.setEnvironments(new com._1c.g5.v8.dt.mcore.util.Environments(environment));spec.setIgnoreModuleItems(false);spec.setIgnoreServerCalls(false);
                            var scope=provider.get(org.eclipse.xtext.scoping.IScopeProvider.class).getScope(spec,com._1c.g5.v8.dt.bsl.model.BslPackage.Literals.METHODS_SCOPE_SPEC__METHOD_REF);
                            for(var entry:scope.getAllElements()) {if(scopeValues.size()>=100){truncated=true;break;}if(names.add(entry.getName().toString()))scopeValues.add(object("name",entry.getName().toString(),"uri",entry.getEObjectURI().toString()));}
                        }
                        syntax.add("scope",object("methods",scopeValues,"truncated",truncated));
                    }
                }
                return syntax;
            }));
            return result;
        } finally {disconnect(access);}
    }
    public static void validateEdit(JsonObject input) {
        MetadataOperations.keys(input,Set.of("operation","path","expectedSha256","edits"));
        if(text(input,"path").isBlank() || !text(input,"expectedSha256").matches("[a-fA-F0-9]{64}")) throw new EdtToolException("INVALID_PROPERTY","path and expectedSha256 required");
        if(!text(input,"operation").equals("bslFormat")) {
            var edits=input.getAsJsonArray("edits");
            if(edits==null || edits.isEmpty() || edits.size()>64)throw new EdtToolException("INVALID_PROPERTY","edits: 1..64");
            int size=0;
            for(var entry:edits){var edit=entry.getAsJsonObject();MetadataOperations.keys(edit,Set.of("offset","length","text"));
                if(edit.get("offset").getAsBigDecimal().intValueExact()<0 || edit.get("length").getAsBigDecimal().intValueExact()<0 || !edit.get("text").isJsonPrimitive() || !edit.get("text").getAsJsonPrimitive().isString())throw new EdtToolException("INVALID_PROPERTY","edit range/text");size+=text(edit,"text").length();}
            if(size>256000)throw new EdtToolException("INVALID_PROPERTY","edit text limit: 256000");
        }
    }
    public JsonObject edit(JsonObject input) throws Exception {
        validateEdit(input);context.check();var access=connect(file(text(input,"path")));
        try {
            if(access.dirty)throw new EdtToolException("EDITING_CONFLICT","Unsaved editor; save explicitly before an agent turn");
            String before=ui(access.document::get);
            if(!hash(before).equalsIgnoreCase(text(input,"expectedSha256")))throw new EdtToolException("EDITING_CONFLICT","Document changed; read the current module again");
            var edits=new ArrayList<JsonObject>();
            if(text(input,"operation").equals("bslFormat")) {
                edits.addAll(access.document.readOnly(resource->{
                    var provider=resource.getResourceServiceProvider();var formatter=provider.get(IFormatter2.class);
                    if(formatter==null)throw new EdtToolException("UNSUPPORTED_BY_EDT_VERSION","Xtext formatter unavailable");
                    var request=new FormatterRequest().setTextRegionAccess(provider.get(TextRegionAccessBuilder.class).forNodeModel(resource).create());
                    return formatter.format(request).stream().map(e->object("offset",e.getOffset(),"length",e.getLength(),"text",e.getReplacementText())).toList();
                }));
            } else input.getAsJsonArray("edits").forEach(e->edits.add(e.getAsJsonObject()));
            edits.sort(Comparator.comparingInt((JsonObject e)->e.get("offset").getAsInt()).reversed());
            int boundary=before.length();var changed=new StringBuilder(before);
            for(var edit:edits) {int start=edit.get("offset").getAsInt(),length=edit.get("length").getAsInt();if(start>boundary || length>boundary-start)throw new EdtToolException("INVALID_PROPERTY","Overlapping/out-of-bounds edits");changed.replace(start,start+length,text(edit,"text"));boundary=start;}
            String after=changed.toString();
            ui(()->{context.check();if(!before.equals(access.document.get()))throw new EdtToolException("EDITING_CONFLICT","Document changed before edit");
                for(var edit:edits)access.document.replace(edit.get("offset").getAsInt(),edit.get("length").getAsInt(),text(edit,"text"));return null;});
            try {
                context.check();access.provider.saveDocument(new NullProgressMonitor(),access.input,access.document,false);
            } catch(Exception error) {
                try {ui(()->{if(after.equals(access.document.get()))access.document.set(before);return null;});}
                catch(Exception cleanup){error.addSuppressed(cleanup);}
                throw error;
            }
            return object("status","completed","path",text(input,"path"),"sha256",hash(after),"edits",edits.size(),"mechanism","IXtextDocument + IDocumentProvider.saveDocument");
        } finally {disconnect(access);}
    }
    private void disconnect(Access access) throws Exception {if(access.owned)ui(()->{access.provider.disconnect(access.input);return null;});}
    public static String hash(String text) {try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    public static <T>T ui(Callable<T> action) throws Exception {
        if(!PlatformUI.isWorkbenchRunning())throw new EdtToolException("PROJECT_CONTEXT_UNAVAILABLE","Workbench unavailable");
        var display=PlatformUI.getWorkbench().getDisplay();var result=new java.util.concurrent.CompletableFuture<T>();
        if(display.isDisposed())throw new EdtToolException("PROJECT_CONTEXT_UNAVAILABLE","Workbench disposed");
        Runnable run=()->{if(result.isDone())return;try{result.complete(action.call());}catch(Exception error){result.completeExceptionally(error);}};
        if(display.getThread()==Thread.currentThread())run.run();else display.asyncExec(run);
        try{return result.get(60,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException error){if(error.getCause() instanceof Exception e)throw e;throw error;}
        catch(java.util.concurrent.TimeoutException error){result.cancel(false);throw error;}
        catch(InterruptedException error){result.cancel(false);Thread.currentThread().interrupt();throw error;}
    }
}
