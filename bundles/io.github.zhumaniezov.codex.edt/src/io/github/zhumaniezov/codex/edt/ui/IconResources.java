package io.github.zhumaniezov.codex.edt.ui;

import org.eclipse.jface.resource.*;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.core.runtime.Platform;
import io.github.zhumaniezov.codex.edt.CodexPlugin;

public final class IconResources {
    private static final java.util.Map<String, ImageDescriptor> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private IconResources() { }
    public static ImageDescriptor descriptor(String name, boolean dark) {
        return CACHE.computeIfAbsent(name + ":" + dark, key -> new ImageDescriptor() {
            @Override public ImageData getImageData(int zoom) {
                int size = Math.round(16 * zoom / 100f);
                if (size != 16 && size != 24 && size != 32 && size != 48 && size != 64) { return null; }
                String path = "icons/" + (dark ? "dark/" : "light/") + name + "-" + size + ".png";
                var url = Platform.getBundle(CodexPlugin.ID).getEntry(path);
                if (url == null) { return null; }
                try (var stream = url.openStream()) { return new ImageData(stream); }
                catch (java.io.IOException error) { CodexPlugin.log("Icon resource: " + path, error); return null; }
            }
        });
    }
    public static void button(Button button, String icon, String tooltip) {
        var resources = new LocalResourceManager(JFaceResources.getResources(), button);
        Runnable update = () -> {
            String name = String.valueOf(button.getData("codex.icon"));
            var descriptor = descriptor(name, ThemeService.dark(button.getParent()));
            button.setImage(resources.createImage(descriptor));
        };
        button.setData("codex.icon", icon); button.setText(""); button.setToolTipText(tooltip);
        button.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
            @Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) { event.result = button.getToolTipText(); }
        });
        button.setData("codex.updateIcon", update); update.run(); new ThemeService(button, update);
    }
    public static void change(Button button, String name) {
        if (!name.equals(button.getData("codex.icon"))) { button.setData("codex.icon", name); ((Runnable) button.getData("codex.updateIcon")).run(); }
    }
}
