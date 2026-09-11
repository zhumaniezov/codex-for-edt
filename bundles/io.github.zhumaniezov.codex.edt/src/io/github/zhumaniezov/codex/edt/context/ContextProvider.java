package io.github.zhumaniezov.codex.edt.context;

import org.eclipse.ui.IWorkbenchPage;

/** Будущие поставщики семантического контекста также возвращают неизменяемый снимок. */
public interface ContextProvider {
    EditorContext capture(IWorkbenchPage page);
}
