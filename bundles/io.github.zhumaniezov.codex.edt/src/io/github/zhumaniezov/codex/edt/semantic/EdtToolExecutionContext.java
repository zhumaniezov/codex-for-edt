package io.github.zhumaniezov.codex.edt.semantic;

import java.util.function.BooleanSupplier;
import org.eclipse.core.resources.IProject;

public record EdtToolExecutionContext(IProject project,String threadId,String turnId,BooleanSupplier valid) {
    public void check() {if(!valid.getAsBoolean()) throw new EdtToolException("EDITING_CONFLICT","EDT turn expired");}
}
