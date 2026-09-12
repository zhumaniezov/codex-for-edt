package io.github.zhumaniezov.codex.edt.semantic;

import java.util.concurrent.CompletableFuture;

public final class SemanticApproval {
    private final String thread, turn, project;
    private final MetadataPlan plan;
    private final CompletableFuture<Boolean> decision = new CompletableFuture<>();

    public SemanticApproval(String thread, String turn, String project, MetadataPlan plan) {
        this.thread = thread;
        this.turn = turn;
        this.project = project;
        this.plan = plan;
    }

    public String thread() {
        return thread;
    }

    public String turn() {
        return turn;
    }

    public String project() {
        return project;
    }

    public MetadataPlan plan() {
        return plan;
    }

    public CompletableFuture<Boolean> decision() {
        return decision;
    }

    public void answer(boolean allow) {
        decision.complete(allow);
    }
}
