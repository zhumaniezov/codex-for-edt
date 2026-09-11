package io.github.zhumaniezov.codex.edt.ui.render;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.PlatformUI;
import io.github.zhumaniezov.codex.edt.CodexPlugin;

public final class NativeMarkdownRenderer implements ResponseRenderer {
    private final StyledText text;
    private final org.eclipse.swt.widgets.Display display;
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "codex-edt-markdown"); thread.setDaemon(true); return thread;
    });
    private final AtomicReference<String> pending = new AtomicReference<>();
    private volatile long revision;
    private volatile boolean closed;
    private boolean scheduled;
    private MarkdownDocument document = new MarkdownDocument("", java.util.List.of());

    public NativeMarkdownRenderer(Composite parent) {
        display = parent.getDisplay();
        text = new StyledText(parent, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        text.setData("codex.role", "response");
        text.setMargins(8, 6, 8, 6);
        text.setFont(JFaceResources.getDefaultFont());
        text.addListener(SWT.MouseUp, event -> {
            if (event.button != 1 || text.getSelectionCount() != 0) { return; }
            int offset = text.getOffsetAtPoint(new Point(event.x, event.y));
            if (offset < 0) { return; }
            document.spans().stream().filter(span -> offset >= span.start() && offset < span.start() + span.length())
                .map(span -> span.style().link()).filter(MarkdownDocument::safeLink).findFirst().ifPresent(link -> {
                    try { PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(java.net.URI.create(link).toURL()); }
                    catch (Exception error) { CodexPlugin.log("Не удалось открыть ссылку", error); }
                });
        });
        text.addListener(SWT.MouseMove, event -> {
            int offset = text.getOffsetAtPoint(new Point(event.x, event.y));
            String link = document.spans().stream().filter(span -> offset >= span.start() && offset < span.start() + span.length())
                .map(span -> span.style().link()).filter(value -> !value.isBlank()).findFirst().orElse(null);
            text.setToolTipText(link);
            text.setCursor(link == null ? null : display.getSystemCursor(SWT.CURSOR_HAND));
        });
    }
    @Override public Control control() { return text; }
    @Override public void render(String markdown) {
        if (closed) { return; }
        pending.set(markdown); revision++;
        if (scheduled) { return; }
        scheduled = true;
        display.timerExec(45, () -> {
            if (closed) { return; }
            scheduled = false;
            long current = revision;
            String source = pending.getAndSet(null);
            executor.execute(() -> {
                MarkdownDocument parsed;
                try { parsed = MarkdownDocument.parse(source); }
                catch (RuntimeException | StackOverflowError error) {
                    CodexPlugin.log("Markdown показан без оформления", error);
                    parsed = new MarkdownDocument(source, java.util.List.of());
                }
                var result = parsed;
                if (closed || display.isDisposed()) { return; }
                try {
                    display.asyncExec(() -> { if (!closed && current == revision && !text.isDisposed()) { apply(result); } });
                } catch (org.eclipse.swt.SWTException error) { if (!display.isDisposed()) { throw error; } }
            });
        });
    }
    private void apply(MarkdownDocument result) {
        boolean follow = text.getTopPixel() + text.getClientArea().height >= text.getLineCount() * text.getLineHeight() - 40;
        int top = text.getTopPixel();
        var selection = text.getSelection();
        document = result;
        text.setRedraw(false);
        try {
            text.setText(result.text());
            var styles = new ArrayList<StyleRange>();
            for (var span : result.spans()) {
                var style = span.style();
                var range = new StyleRange(); range.start = span.start(); range.length = span.length();
                range.fontStyle = (style.bold() ? SWT.BOLD : SWT.NORMAL) | (style.italic() ? SWT.ITALIC : SWT.NORMAL);
                if (style.code()) { range.font = JFaceResources.getTextFont(); }
                if (!style.link().isBlank()) { range.foreground = display.getSystemColor(SWT.COLOR_LINK_FOREGROUND); range.underline = true; }
                styles.add(range);
            }
            text.setStyleRanges(styles.toArray(StyleRange[]::new));
            text.setSelection(Math.min(selection.x, text.getCharCount()), Math.min(selection.y, text.getCharCount()));
            if (follow && selection.x == selection.y) { text.setTopIndex(Math.max(0, text.getLineCount() - 1)); }
            else { text.setTopPixel(top); }
        } finally { text.setRedraw(true); }
    }
    @Override public void close() { closed = true; executor.shutdownNow(); }
}
