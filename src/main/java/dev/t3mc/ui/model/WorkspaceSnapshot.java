package dev.t3mc.ui.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Immutable view of everything the workspace screen renders.
 * The UI re-renders whenever {@link #version()} changes.
 */
public record WorkspaceSnapshot(long version, ConnectionStatus connection, List<ProjectView> projects,
                                @Nullable String selectedThreadId, @Nullable ThreadDetail selectedThread,
                                SettingsView settings, List<WorkspaceEnvironment> environments, boolean drafting) {
    public WorkspaceSnapshot {
        connection = Objects.requireNonNullElse(connection, ConnectionStatus.disconnected());
        projects = projects == null ? List.of() : List.copyOf(projects);
        environments = List.copyOf(environments);
        settings = Objects.requireNonNullElse(settings, SettingsView.defaults());
    }

    public static WorkspaceSnapshot empty() {
        return new WorkspaceSnapshot(0, ConnectionStatus.disconnected(), List.of(), null, null, SettingsView.defaults(), List.of(), false);
    }

    public @Nullable ProjectView project(String projectId) {
        for (var project : projects) if (project.id().equals(projectId)) return project;
        return null;
    }
}
