package com.xplanet.ai.domain;

public enum AiTaskStatus {
    QUEUED,
    RUNNING,
    RETRYING,
    WAITING_REVIEW,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
