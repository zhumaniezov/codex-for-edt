package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.jface.resource.JFaceResources;

final class HeaderComponent {
    final IconButton newChat, refresh, settings;
    final AccountComponent account;

    HeaderComponent(Composite parent, ThemePalette palette) {
        var root = new Composite(parent, SWT.NONE);
        var layout = new GridLayout(5, false);
        layout.marginWidth = 2;
        layout.marginHeight = 2;
        layout.horizontalSpacing = 2;
        root.setLayout(layout);
        root.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        palette.apply(root, "panel", "text");
        var title = new Label(root, SWT.NONE);
        title.setText("Codex");
        title.setFont(JFaceResources.getBannerFont());
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        palette.apply(title, "panel", "text");
        newChat = button(root, palette, "newChat", "newThread", tr("text005"));
        refresh = button(root, palette, "refresh", "refreshThreads", tr("refresh"));
        settings = button(root, palette, "settings", "settings", tr("settings"));
        account = new AccountComponent(root, palette);
    }

    private IconButton button(Composite root, ThemePalette palette, String icon, String role, String tip) {
        var button = new IconButton(root, palette, icon, tip, false);
        button.setData("codex.role", role);
        return button;
    }
}
