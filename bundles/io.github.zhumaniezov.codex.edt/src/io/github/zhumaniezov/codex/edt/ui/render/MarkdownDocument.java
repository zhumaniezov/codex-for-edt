package io.github.zhumaniezov.codex.edt.ui.render;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/** Преобразует AST в текст и непересекающиеся диапазоны оформления без HTML. */
public record MarkdownDocument(String text, List<Span> spans) {
    public record Style(boolean bold, boolean italic, boolean code, String link) {
        static final Style PLAIN = new Style(false, false, false, "");
    }
    public record Span(int start, int length, Style style) { }
    public static boolean safeLink(String destination) {
        try {
            URI uri = URI.create(destination);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException error) { return false; }
    }
    public static MarkdownDocument parse(String source) {
        return parse(source, MarkdownDocument::safeLink);
    }
    public static MarkdownDocument parse(String source, java.util.function.Predicate<String> links) {
        var builder = new Builder(links);
        builder.walk(Parser.builder().build().parse(source), Style.PLAIN, 0);
        return new MarkdownDocument(builder.text.toString(), List.copyOf(builder.spans));
    }
    private static final class Builder {
        final java.util.function.Predicate<String> links;
        Builder(java.util.function.Predicate<String> links) { this.links = links; }
        final StringBuilder text = new StringBuilder();
        final List<Span> spans = new ArrayList<>();
        void append(String value, Style style) {
            if (value.isEmpty()) { return; }
            spans.add(new Span(text.length(), value.length(), style)); text.append(value);
        }
        void newline() { if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') { append("\n", Style.PLAIN); } }
        void walk(Node node, Style style, int depth) {
            if (depth > 128) { append(tr("text043"), Style.PLAIN); return; }
            if (node instanceof Text value) { append(value.getLiteral(), style); return; }
            if (node instanceof Code value) { append(value.getLiteral(), new Style(style.bold(), style.italic(), true, style.link())); return; }
            if (node instanceof FencedCodeBlock value) { newline(); append(value.getLiteral(), new Style(false, false, true, "")); newline(); return; }
            if (node instanceof IndentedCodeBlock value) { newline(); append(value.getLiteral(), new Style(false, false, true, "")); newline(); return; }
            if (node instanceof HtmlInline value) { append(value.getLiteral(), style); return; }
            if (node instanceof HtmlBlock value) { newline(); append(value.getLiteral(), style); newline(); return; }
            if (node instanceof SoftLineBreak || node instanceof HardLineBreak) { append("\n", style); return; }
            if (node instanceof ThematicBreak) { newline(); append("────────\n", style); return; }
            if (node instanceof StrongEmphasis || node instanceof Heading) { style = new Style(true, style.italic(), style.code(), style.link()); }
            if (node instanceof Emphasis) { style = new Style(style.bold(), true, style.code(), style.link()); }
            if (node instanceof Link link && links.test(link.getDestination())) { style = new Style(style.bold(), style.italic(), style.code(), link.getDestination()); }
            if (node instanceof ListItem) {
                newline();
                int number = 1;
                for (Node previous = node.getPrevious(); previous != null; previous = previous.getPrevious()) { number++; }
                if (node.getParent() instanceof OrderedList list) { append((list.getMarkerStartNumber() + number - 1) + ". ", style); }
                else { append("• ", style); }
            }
            if (node instanceof BlockQuote) { newline(); append("│ ", style); }
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) { walk(child, style, depth + 1); }
            if (node instanceof Paragraph || node instanceof Heading) {
                newline();
                if (!(node.getParent() instanceof ListItem)) { append("\n", Style.PLAIN); }
            }
            if (node instanceof ListBlock || node instanceof BlockQuote) { newline(); }
        }
    }
}
