package dev.t3mc.ui.model;

import java.util.List;

public record WorkspaceEnvironment(String profileId, String environmentId, String name, String icon,
                                   boolean writable, boolean canOrganizeThreads, List<ProjectView> projects) {
    public boolean matches(ConnectionsView connections) {
        return profileId.equals(connections.activeProfileId()) && environmentId.equals(connections.environmentId());
    }
}
