package dev.t3mc.ui.model;

import java.util.Objects;

public record ThreadSummary(String id, String projectId, String title, ThreadStatus status,
                            boolean hasPendingQuestion, long updatedAtMillis, ThreadOrganization organization) {
    public ThreadSummary(String id, String projectId, String title, ThreadStatus status,
                         boolean hasPendingQuestion, long updatedAtMillis, boolean settled) {
        this(id, projectId, title, status, hasPendingQuestion, updatedAtMillis,
                ThreadOrganization.from(settled, hasPendingQuestion ? ThreadStatus.WAITING_FOR_INPUT : status));
    }

    public boolean settled() { return organization == ThreadOrganization.SETTLED; }

    public ThreadSummary {
        id = Objects.requireNonNull(id, "id");
        projectId = Objects.requireNonNullElse(projectId, "");
        title = Objects.requireNonNullElse(title, "");
        status = Objects.requireNonNullElse(status, ThreadStatus.IDLE);
        organization = Objects.requireNonNull(organization, "organization");
    }
}
