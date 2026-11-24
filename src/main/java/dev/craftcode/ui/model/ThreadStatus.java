package dev.craftcode.ui.model;

public enum ThreadStatus {
    IDLE, RUNNING, WAITING_FOR_INPUT, COMPLETED, ERROR;

    public static ThreadStatus fromRunStatus(String value) {
        return switch (value) {
            case "preparing", "queued", "starting", "running", "waiting" -> RUNNING;
            case "completed" -> COMPLETED;
            case "failed" -> ERROR;
            default -> IDLE;
        };
    }
}
