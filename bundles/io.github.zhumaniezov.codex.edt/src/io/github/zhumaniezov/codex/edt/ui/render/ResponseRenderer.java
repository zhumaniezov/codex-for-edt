package io.github.zhumaniezov.codex.edt.ui.render;

import org.eclipse.swt.widgets.Control;

public interface ResponseRenderer extends AutoCloseable {
    Control control();
    void render(String markdown);
    @Override void close();
}
