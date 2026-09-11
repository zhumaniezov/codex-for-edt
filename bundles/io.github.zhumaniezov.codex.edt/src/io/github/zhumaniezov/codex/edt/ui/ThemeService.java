package io.github.zhumaniezov.codex.edt.ui;

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.PlatformUI;

/** Заимствует цвета controls после применения CSS и не владеет системными ресурсами. */
public final class ThemeService implements AutoCloseable {
    private final Control owner;
    private final org.eclipse.swt.widgets.Display display;
    private boolean closed;
    private final Runnable update;
    private final Listener settings = event -> refresh();
    private final IPropertyChangeListener theme = event -> refresh();
    public ThemeService(Control owner, Runnable update) {
        this.owner = owner; this.display = owner.getDisplay(); this.update = update;
        display.addListener(SWT.Settings, settings);
        PlatformUI.getWorkbench().getThemeManager().addPropertyChangeListener(theme);
        owner.addDisposeListener(event -> close());
    }
    private void refresh() {
        if (closed || owner.isDisposed() || display.isDisposed()) { return; }
        try { display.asyncExec(() -> { if (!closed && !owner.isDisposed()) { update.run(); } }); }
        catch (org.eclipse.swt.SWTException error) { if (!display.isDisposed()) { throw error; } }
    }
    public static boolean dark(Control control) {
        var rgb = control.getBackground().getRGB();
        return luminance(rgb.red, rgb.green, rgb.blue) < 128;
    }
    public static int luminance(int red, int green, int blue) { return (299 * red + 587 * green + 114 * blue) / 1000; }
    @Override public void close() {
        if (closed) { return; } closed = true;
        if (!display.isDisposed()) { display.removeListener(SWT.Settings, settings); }
        if (PlatformUI.isWorkbenchRunning()) { PlatformUI.getWorkbench().getThemeManager().removePropertyChangeListener(theme); }
    }
}
