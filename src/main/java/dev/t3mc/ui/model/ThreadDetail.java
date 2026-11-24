package dev.t3mc.ui.model;

import java.util.List;
import java.util.Objects;

public record ThreadDetail(String id, String projectId, String title, ThreadStatus status,
                           List<ChatMessage> messages, List<PendingQuestion> pendingQuestions,
                           ModelSelection modelSelection, ThreadOrganization organization) {
    public ThreadDetail(String id, String projectId, String title, ThreadStatus status,
                        List<ChatMessage> messages, List<PendingQuestion> pendingQuestions, ModelSelection modelSelection) {
        this(id, projectId, title, status, messages, pendingQuestions, modelSelection,
                ThreadOrganization.from(false, status));
    }

    public ThreadDetail(String id, String projectId, String title, ThreadStatus status,
                        List<ChatMessage> messages, List<PendingQuestion> pendingQuestions) {
        this(id, projectId, title, status, messages, pendingQuestions, ModelSelection.empty());
    }

    public ThreadDetail withModelSelection(ModelSelection selection) {
        return new ThreadDetail(id, projectId, title, status, messages, pendingQuestions, selection, organization);
    }

    public ThreadDetail {
        id = Objects.requireNonNull(id, "id");
        projectId = Objects.requireNonNullElse(projectId, "");
        title = Objects.requireNonNullElse(title, "");
        status = Objects.requireNonNullElse(status, ThreadStatus.IDLE);
        messages = messages == null ? List.of() : List.copyOf(messages);
        pendingQuestions = pendingQuestions == null ? List.of() : List.copyOf(pendingQuestions);
        modelSelection = Objects.requireNonNullElseGet(modelSelection, ModelSelection::empty);
        organization = Objects.requireNonNull(organization, "organization");
    }
}
