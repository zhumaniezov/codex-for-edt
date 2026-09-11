package io.github.zhumaniezov.codex.edt.context;

import java.util.Locale;
import java.nio.file.Path;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

/** Читает активный редактор на UI-потоке SWT через публичные API Eclipse. */
public final class EclipseContextProvider {
    public EditorContext capture(IWorkbenchPage page) {
        if (page == null || page.getActiveEditor() == null) {
            return EditorContext.EMPTY;
        }
        IEditorPart editor = page.getActiveEditor();
        // На странице формы без редактора прежнее выделение BSL не используется.
        while (editor instanceof MultiPageEditorPart multiPage) {
            if (!(multiPage.getSelectedPage() instanceof IEditorPart selectedEditor)) {
                return EditorContext.EMPTY;
            }
            editor = selectedEditor;
        }
        ITextEditor textEditor = editor instanceof ITextEditor text
            ? text : editor.getAdapter(ITextEditor.class);
        IFile file = ResourceUtil.getFile(
            textEditor == null ? editor.getEditorInput() : textEditor.getEditorInput());
        String project = file == null ? "" : file.getProject().getName();
        var location = file == null ? null : file.getProject().getLocationURI();
        String directory = location != null && "file".equalsIgnoreCase(location.getScheme())
            ? Path.of(location).toAbsolutePath().normalize().toString() : "";
        String name = file == null ? editor.getEditorInput().getName() : file.getName();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".bsl") || textEditor == null) {
            return new EditorContext(project, "", "", directory);
        }
        String module = file == null ? name : file.getFullPath().toPortableString();
        var provider = textEditor.getSelectionProvider();
        var selection = provider == null ? null : provider.getSelection();
        String selectedText = selection instanceof ITextSelection text ? text.getText() : "";
        return new EditorContext(project, module, selectedText == null ? "" : selectedText, directory);
    }
}
