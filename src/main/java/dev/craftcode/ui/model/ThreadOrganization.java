package dev.craftcode.ui.model;

/** A thread's place in the workspace, independently of its latest run's outcome. */
public enum ThreadOrganization {
    IN_PROGRESS, NORMAL, SNOOZED, SETTLED;

    public static ThreadOrganization from(boolean settled, ThreadStatus status) {
        if (settled) return SETTLED;
        return status == ThreadStatus.RUNNING ? IN_PROGRESS : NORMAL;
    }
}
