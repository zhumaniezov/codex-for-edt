package io.github.zhumaniezov.codex.edt.client;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;

public enum PermissionMode {
    READ_ONLY("read-only", "readOnly", "never", "user", ":read-only"),
    ASK("workspace-write", "workspaceWrite", "on-request", "user", ":workspace"),
    STRICT("workspace-write", "workspaceWrite", "untrusted", "user", ":workspace"),
    AUTO("workspace-write", "workspaceWrite", "on-request", "auto_review", ":workspace"),
    FULL("danger-full-access", "dangerFullAccess", "never", "user", ":danger-full-access");

    public final String sandbox, type, approval, reviewer, profile;

    PermissionMode(String sandbox, String type, String approval, String reviewer, String profile) {
        this.sandbox = sandbox;
        this.type = type;
        this.approval = approval;
        this.reviewer = reviewer;
        this.profile = profile;
    }

    public boolean writes() {
        return this != READ_ONLY;
    }

    public String label() {
        return tr("mode" + name());
    }

    public String description() {
        return tr("mode" + name() + "Hint");
    }
}
