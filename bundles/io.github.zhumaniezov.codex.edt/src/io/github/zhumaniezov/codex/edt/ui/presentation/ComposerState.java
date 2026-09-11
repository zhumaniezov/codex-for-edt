package io.github.zhumaniezov.codex.edt.ui.presentation;

public record ComposerState(boolean ready, boolean busy, boolean running, boolean hasText) {
    public boolean canSend() {
        return !running && ready && !busy && hasText;
    }

    public boolean canStop() {
        return running;
    }

    public boolean canConfigure() {
        return ready && !busy && !running;
    }
}
