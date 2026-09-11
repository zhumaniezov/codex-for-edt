package io.github.zhumaniezov.codex.edt.ui.presentation;

/** UI foundation без разрешающих решений и без подключения approval RPC. */
public record ApprovalPresenter(String description) {
    public static ApprovalPresenter policy() {
        return new ApprovalPresenter("");
    }

    public boolean hasRequest() {
        return !description.isBlank();
    }

    public boolean canApprove() {
        return false;
    }

    public boolean canAutoApprove() {
        return false;
    }

    public boolean canReject() {
        return false;
    }
}
