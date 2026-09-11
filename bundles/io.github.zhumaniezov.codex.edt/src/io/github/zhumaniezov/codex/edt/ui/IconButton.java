package io.github.zhumaniezov.codex.edt.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;

/**
 * Небольшая native-кнопка: мышь, клавиатура и доступное действие используют
 * один SWT.Selection.
 */
public final class IconButton extends Canvas {
    private String icon;
    private final boolean round;
    private boolean hovered, pressed;

    public IconButton(Composite parent, ThemePalette palette, String icon, String tooltip, boolean round) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.icon = icon;
        this.round = round;
        setToolTipText(tooltip);
        setData("codex.icon", icon);
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        palette.listen(this, () -> {
            setBackground(parent.getBackground());
            setForeground(palette.color("text"));
            redraw();
        });
        addPaintListener(event -> {
            var gc = event.gc;
            var area = getClientArea();
            int side = Math.min(area.width, area.height) - 2, x = (area.width - side) / 2, y = (area.height - side) / 2;
            gc.setAntialias(SWT.ON);
            gc.setBackground(palette.color(round && isEnabled() ? "accent"
                    : pressed ? "selected" : hovered ? "hover" : round ? "selected" : "panel"));
            if (round) {
                gc.fillOval(x, y, side, side);
            } else if (hovered || pressed) {
                gc.fillRoundRectangle(0, 0, area.width, area.height, 8, 8);
            }
            gc.setForeground(palette.color(round && isEnabled() ? "onAccent" : isEnabled() ? "text" : "muted"));
            gc.setLineWidth(2);
            gc.setLineCap(SWT.CAP_ROUND);
            int cx = area.width / 2, cy = area.height / 2;
            switch (this.icon) {
            case "send" -> {
                gc.drawLine(cx, cy + 6, cx, cy - 6);
                gc.drawLine(cx - 5, cy - 1, cx, cy - 6);
                gc.drawLine(cx + 5, cy - 1, cx, cy - 6);
            }
            case "stop" -> {
                gc.setBackground(gc.getForeground());
                gc.fillRoundRectangle(cx - 5, cy - 5, 10, 10, 2, 2);
            }
            case "plus" -> {
                gc.drawLine(cx - 6, cy, cx + 6, cy);
                gc.drawLine(cx, cy - 6, cx, cy + 6);
            }
            case "close" -> {
                gc.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                gc.drawLine(cx + 4, cy - 4, cx - 4, cy + 4);
            }
            case "newChat" -> {
                gc.drawRectangle(cx - 6, cy - 6, 10, 12);
                gc.drawLine(cx + 4, cy - 7, cx + 4, cy - 1);
                gc.drawLine(cx + 1, cy - 4, cx + 7, cy - 4);
            }
            case "refresh" -> {
                gc.drawArc(cx - 6, cy - 6, 12, 12, 35, 290);
                gc.drawLine(cx + 6, cy - 6, cx + 6, cy - 1);
                gc.drawLine(cx + 2, cy - 1, cx + 6, cy - 1);
            }
            case "settings" -> {
                for (int i = -4; i <= 4; i += 4) {
                    gc.drawLine(cx - 7, cy + i, cx + 7, cy + i);
                }
                gc.setLineWidth(3);
                gc.drawLine(cx - 3, cy - 6, cx - 3, cy - 2);
                gc.drawLine(cx + 3, cy - 2, cx + 3, cy + 2);
                gc.drawLine(cx - 1, cy + 2, cx - 1, cy + 6);
            }
            case "account" -> {
                gc.drawOval(cx - 3, cy - 7, 6, 6);
                gc.drawArc(cx - 6, cy, 12, 12, 0, 180);
            }
            case "history" -> {
                gc.drawOval(cx - 6, cy - 6, 12, 12);
                gc.drawLine(cx, cy - 4, cx, cy);
                gc.drawLine(cx, cy, cx + 3, cy + 2);
            }
            default -> {
            }
            }
            if (isFocusControl()) {
                gc.setForeground(palette.color("text"));
                gc.setLineStyle(SWT.LINE_DOT);
                if (round) {
                    gc.drawOval(x + 3, y + 3, side - 6, side - 6);
                } else {
                    gc.drawRoundRectangle(2, 2, area.width - 5, area.height - 5, 7, 7);
                }
            }
        });
        addListener(SWT.MouseEnter, e -> {
            hovered = true;
            redraw();
        });
        addListener(SWT.MouseExit, e -> {
            hovered = false;
            pressed = false;
            redraw();
        });
        addListener(SWT.MouseDown, e -> {
            if (e.button == 1 && isEnabled()) {
                pressed = true;
                setFocus();
                redraw();
            }
        });
        addListener(SWT.MouseUp, e -> {
            boolean activate = pressed && getClientArea().contains(e.x, e.y);
            pressed = false;
            redraw();
            if (activate) {
                activate();
            }
        });
        addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.SPACE || e.keyCode == SWT.CR) {
                e.doit = false;
                activate();
            }
        });
        addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS) {
                e.doit = true;
            }
        });
        addListener(SWT.FocusIn, e -> redraw());
        addListener(SWT.FocusOut, e -> {
            pressed = false;
            redraw();
        });
        getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override
            public void getName(AccessibleEvent e) {
                e.result = getToolTipText();
            }
        });
        getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            @Override
            public void getRole(AccessibleControlEvent e) {
                e.detail = ACC.ROLE_PUSHBUTTON;
            }

            @Override
            public void getState(AccessibleControlEvent e) {
                e.detail = ACC.STATE_FOCUSABLE | (isEnabled() ? 0 : ACC.STATE_DISABLED)
                        | (isFocusControl() ? ACC.STATE_FOCUSED : 0);
            }
        });
        getAccessible().addAccessibleActionListener(new AccessibleActionAdapter() {
            @Override
            public void getActionCount(AccessibleActionEvent e) {
                e.count = 1;
            }

            @Override
            public void getName(AccessibleActionEvent e) {
                e.result = getToolTipText();
            }

            @Override
            public void doAction(AccessibleActionEvent e) {
                activate();
                e.result = ACC.OK;
            }
        });
    }

    private void activate() {
        if (isEnabled()) {
            notifyListeners(SWT.Selection, new Event());
        }
    }

    public void icon(String name) {
        icon = name;
        setData("codex.icon", name);
        redraw();
    }

    @Override
    public Point computeSize(int width, int height, boolean changed) {
        int size = round ? 36 : 28;
        return new Point(width == SWT.DEFAULT ? size : width, height == SWT.DEFAULT ? size : height);
    }

    @Override
    public void setEnabled(boolean value) {
        super.setEnabled(value);
        redraw();
    }
}
