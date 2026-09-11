package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import io.github.zhumaniezov.codex.edt.client.SessionData.Message;
import io.github.zhumaniezov.codex.edt.ui.render.*;

final class ChatComponent implements AutoCloseable {
    private final ResponseRenderer renderer;
    private final List<Message> messages = new ArrayList<>();
    private String reply = "";
    private int updates;
    private boolean shortened;
    ChatComponent(Composite parent, java.util.function.Consumer<String> links) {
        renderer = new NativeMarkdownRenderer(parent, links);
        var layout = new GridData(SWT.FILL, SWT.FILL, true, true); layout.widthHint = 0; layout.heightHint = 0;
        renderer.control().setLayoutData(layout);
        clear();
    }
    void clear() { renderer.control().setData("codex.answer", ""); messages.clear(); reply = ""; shortened = false; refresh(); }
    void history(List<Message> values, boolean prepend) {
        reply = "";
        if (!prepend) { messages.clear(); shortened = false; }
        messages.addAll(0, values);
        renderer.control().setData("codex.answer", messages.stream().filter(message -> "Codex".equals(message.role())).reduce((a, b) -> b).map(Message::text).orElse("")); refresh();
    }
    void begin(String prompt) {
        messages.add(new Message(tr("text024"), prompt)); reply = ""; updates = 0;
        renderer.control().setData("codex.streamingUpdates", updates); refresh();
    }
    void stream(String value) {
        reply = value; renderer.control().setData("codex.streamingUpdates", ++updates); refresh();
    }
    void finish(String value) { renderer.control().setData("codex.answer", value); reply = ""; messages.add(new Message("Codex", value)); refresh(); }
    void error(String value) { messages.add(new Message(tr("text025"), value)); reply = ""; refresh(); }
    private void refresh() {
        var source = new StringBuilder();
        int size = messages.stream().mapToInt(message -> message.text().length()).sum();
        while (size > 1024 * 1024 && messages.size() > 1) {
            size -= messages.remove(0).text().length(); shortened = true;
        }
        if (shortened) { source.append(tr("text049")); }
        for (var message : messages) { source.append("## ").append(message.role()).append("\n\n").append(message.text()).append("\n\n"); }
        if (!reply.isEmpty()) { source.append("## Codex\n\n").append(reply); }
        renderer.render(source.isEmpty() ? tr("text050") : source.toString());
    }
    @Override public void close() { renderer.close(); }
}
