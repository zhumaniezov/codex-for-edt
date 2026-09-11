package io.github.zhumaniezov.codex.edt.ui.render;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
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
    private record RenderJob(java.util.function.Supplier<MarkdownDocument> parse, String fallback) { }
    private final AtomicReference<RenderJob> pending = new AtomicReference<>();
    private volatile long revision;
    private volatile boolean closed;
    private boolean scheduled;
    private MarkdownDocument document = new MarkdownDocument("", java.util.List.of());

    private final java.util.function.Consumer<String> openLink;
    private final io.github.zhumaniezov.codex.edt.ui.ThemePalette palette;
    public NativeMarkdownRenderer(Composite parent) { this(parent, link -> { }); }
    public NativeMarkdownRenderer(Composite parent, java.util.function.Consumer<String> openLink) {
        this(parent,openLink,new io.github.zhumaniezov.codex.edt.ui.ThemePalette(parent));
    }
    public NativeMarkdownRenderer(Composite parent,java.util.function.Consumer<String> openLink,io.github.zhumaniezov.codex.edt.ui.ThemePalette palette){
        this.openLink = openLink;this.palette=palette;
        display = parent.getDisplay();
        text = new StyledText(parent, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        text.setData("codex.role", "response");
        text.setMargins(8, 10, 8, 10);text.setLineSpacing(3);text.setAlwaysShowScrollBars(false);palette.apply(text,"panel","text");
        text.setFont(JFaceResources.getDefaultFont());
        text.addListener(SWT.MouseUp, event -> {
            if (event.button != 1 || text.getSelectionCount() != 0) { return; }
            int offset = text.getOffsetAtPoint(new Point(event.x, event.y));
            if (offset < 0) { return; }
            document.spans().stream().filter(span -> offset >= span.start() && offset < span.start() + span.length())
                .map(span -> span.style().link()).filter(value -> !value.isBlank()).findFirst().ifPresent(openLink);

        });
        palette.listen(text,()->{if(!closed){apply(document);}});
        var menu = new org.eclipse.swt.widgets.Menu(text); text.setMenu(menu);
        var copy = new org.eclipse.swt.widgets.MenuItem(menu, SWT.PUSH); copy.setText(tr("copy")); copy.addListener(SWT.Selection, event -> text.copy());
        var answer = new org.eclipse.swt.widgets.MenuItem(menu, SWT.PUSH); answer.setText(tr("copyAnswer"));
        answer.addListener(SWT.Selection, event -> copy(String.valueOf(text.getData("codex.answer"))));
        var code = new org.eclipse.swt.widgets.MenuItem(menu, SWT.PUSH); code.setText(tr("copyCode"));
        code.addListener(SWT.Selection, event -> document.spans().stream().filter(span -> span.style().code()
            && text.getCaretOffset() >= span.start() && text.getCaretOffset() <= span.start() + span.length()).findFirst()
            .ifPresent(span -> copy(document.text().substring(span.start(), span.start() + span.length()))));
        var open = new org.eclipse.swt.widgets.MenuItem(menu, SWT.PUSH); open.setText(tr("openLink"));
        open.addListener(SWT.Selection, event -> document.spans().stream().filter(span -> !span.style().link().isBlank()
            && text.getCaretOffset() >= span.start() && text.getCaretOffset() <= span.start() + span.length()).findFirst()
            .ifPresent(span -> openLink.accept(span.style().link())));
        text.addListener(SWT.MouseMove, event -> {
            int offset = text.getOffsetAtPoint(new Point(event.x, event.y));
            String link = document.spans().stream().filter(span -> offset >= span.start() && offset < span.start() + span.length())
                .map(span -> span.style().link()).filter(value -> !value.isBlank()).findFirst().orElse(null);
            text.setToolTipText(link);
            text.setCursor(link == null ? null : display.getSystemCursor(SWT.CURSOR_HAND));
        });
    }
    private void copy(String value) {
        var clipboard = new org.eclipse.swt.dnd.Clipboard(display);
        try { clipboard.setContents(new Object[] {value}, new org.eclipse.swt.dnd.Transfer[] {org.eclipse.swt.dnd.TextTransfer.getInstance()}); }
        finally { clipboard.dispose(); }
    }
    @Override public Control control() { return text; }
    @Override public void render(String markdown) {
        schedule(new RenderJob(()->MarkdownDocument.parse(markdown,link->MarkdownDocument.safeLink(link)||FileLinkTarget.candidate(link)),markdown));
    }
    @Override public void conversation(java.util.List<io.github.zhumaniezov.codex.edt.client.SessionData.Message> messages){
        var snapshot=java.util.List.copyOf(messages);schedule(new RenderJob(()->ConversationDocument.parse(snapshot),snapshot.stream().map(message->message.role()+"\n"+message.text()).collect(java.util.stream.Collectors.joining("\n\n"))));
    }
    private void schedule(RenderJob task) {
        if (closed) { return; }
        pending.set(task); revision++;
        if (scheduled) { return; }
        scheduled = true;
        display.timerExec(45, () -> {
            if (closed) { return; }
            scheduled = false;
            long current = revision;
            var source = pending.getAndSet(null);
            executor.execute(() -> {
                MarkdownDocument parsed;
                try { parsed = source.parse().get(); }
                catch (RuntimeException | StackOverflowError error) {
                    CodexPlugin.log(tr("text042"), error);
                    parsed = new MarkdownDocument(source.fallback(), java.util.List.of());
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
        boolean follow = text.getClientArea().height >= text.getLinePixel(text.getLineCount()) - 24;
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
                if (style.code()) { range.font = JFaceResources.getTextFont(); range.background = palette.color("footer"); }
                if (!style.link().isBlank()) { range.foreground = palette.color("text"); range.underline = true; }
                styles.add(range);
            }
            text.setStyleRanges(styles.toArray(StyleRange[]::new));
            for(var block:result.blocks()){
                int first=text.getLineAtOffset(block.start()),last=text.getLineAtOffset(Math.max(block.start(),block.start()+block.length()-1));
                if(block.user()){text.setLineBackground(first,last-first+1,palette.color("hover"));text.setLineIndent(first,last-first+1,8);}
            }
            for(var span:result.spans()){
                if(span.style().code()&&result.text().substring(span.start(),span.start()+span.length()).contains("\n")){
                    int first=text.getLineAtOffset(span.start()),last=text.getLineAtOffset(span.start()+span.length()-1);
                    text.setLineBackground(first,last-first+1,palette.color("footer"));text.setLineIndent(first,last-first+1,10);
                }
            }
            text.setSelection(Math.min(selection.x, text.getCharCount()), Math.min(selection.y, text.getCharCount()));
            if (follow && selection.x == selection.y) { text.setTopPixel(Math.max(0,text.getTopPixel()+text.getLinePixel(text.getLineCount())-text.getClientArea().height+text.getBottomMargin())); }
            else { text.setTopPixel(top); }
        } finally { text.setRedraw(true); }
    }
    @Override public void close() { closed = true; executor.shutdownNow(); }
}
