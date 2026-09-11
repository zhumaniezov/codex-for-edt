package io.github.zhumaniezov.codex.edt.ui;

import java.util.*;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.*;
import io.github.zhumaniezov.codex.edt.ui.presentation.PaletteModel;

public final class ThemePalette {
    private final Control owner;
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<String, Color> colors = new HashMap<>();
    private PaletteModel model;

    public ThemePalette(Control owner) {
        this.owner = owner;
        reload();
        new ThemeService(owner, this::reload);
        owner.addDisposeListener(event -> {
            colors.values().forEach(Color::dispose);
            colors.clear();
            listeners.clear();
        });
    }

    private void reload() {
        if (owner.isDisposed()) {
            return;
        }
        Control source = owner.getParent() == null ? owner : owner.getParent();
        if (source.isDisposed()) { return; }
        var display = owner.getDisplay();
        model = PaletteModel.from(source.getBackground().getRGB(), source.getForeground().getRGB(),
                display.getSystemColor(SWT.COLOR_WHITE).getRGB(), display.getSystemColor(SWT.COLOR_BLACK).getRGB());
        var old = new ArrayList<>(colors.values());
        colors.clear();
        for (var name : List.of("panel", "input", "footer", "border", "text", "muted", "hover", "selected", "accent",
                "onAccent")) {
            var rgb = switch (name) {
            case "input" -> model.input();
            case "footer" -> model.footer();
            case "border" -> model.border();
            case "text" -> model.text();
            case "muted" -> model.muted();
            case "hover" -> model.hover();
            case "selected" -> model.selected();
            case "accent" -> model.accent();
            case "onAccent" -> model.onAccent();
            default -> model.panel();
            };
            colors.put(name, new Color(display, rgb));
        }
        listeners.forEach(Runnable::run);
        old.forEach(Color::dispose);
    }

    public Color color(String role) {
        return colors.get(role);
    }

    public void listen(Control control, Runnable update) {
        listeners.add(update);
        control.addDisposeListener(event -> listeners.remove(update));
        update.run();
    }

    public void apply(Control control, String surface, String foreground) {
        listen(control, () -> {
            if (!control.isDisposed()) {
                control.setBackground(color(surface));
                control.setForeground(color(foreground));
                control.redraw();
            }
        });
    }
}
