package io.github.zhumaniezov.codex.edt.ui.render;

import org.eclipse.swt.widgets.Control;

public interface ResponseRenderer extends AutoCloseable {
    Control control();
    void render(String markdown);
    default void conversation(java.util.List<io.github.zhumaniezov.codex.edt.client.SessionData.Message> messages) {
        render(messages.stream().map(message -> "## "+message.role()+"\n\n"+message.text()).collect(java.util.stream.Collectors.joining("\n\n")));
    }
    @Override void close();
}
