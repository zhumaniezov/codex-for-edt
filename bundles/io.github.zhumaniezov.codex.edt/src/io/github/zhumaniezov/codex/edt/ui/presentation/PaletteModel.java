package io.github.zhumaniezov.codex.edt.ui.presentation;

import org.eclipse.swt.graphics.RGB;

public record PaletteModel(RGB panel, RGB input, RGB footer, RGB border, RGB text, RGB muted, RGB hover, RGB selected,
        RGB accent, RGB onAccent) {
    public static PaletteModel from(RGB base, RGB foreground, RGB light, RGB dark) {
        RGB text = contrast(base, foreground) >= 4.5 ? foreground
                : contrast(base, light) > contrast(base, dark) ? light : dark;
        boolean night = luminance(base) < 0.3;
        RGB input = luminance(base) > .92 ? mix(base, dark, .04) : mix(base, light, night ? 0.065 : 0.82);
        return new PaletteModel(base, input, mix(input, base, .55), mix(input, text, .3), text, mix(input, text, .72),
                mix(base, text, .075), mix(base, text, .15), text, input);
    }

    public static RGB mix(RGB a, RGB b, double ratio) {
        double k = Math.max(0, Math.min(1, ratio));
        return new RGB((int) Math.round(a.red * (1 - k) + b.red * k), (int) Math.round(a.green * (1 - k) + b.green * k),
                (int) Math.round(a.blue * (1 - k) + b.blue * k));
    }

    private static double channel(int value) {
        double v = value / 255.0;
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }

    public static double luminance(RGB color) {
        return .2126 * channel(color.red) + .7152 * channel(color.green) + .0722 * channel(color.blue);
    }

    public static double contrast(RGB a, RGB b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
}
