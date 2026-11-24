package dev.craftcode.ui.model;

import org.jetbrains.annotations.Nullable;
import java.util.List;

public record ConnectionsView(List<ConnectionProfileView> profiles, String activeProfileId, String environmentId, long generation, int online, int total) {
    public ConnectionsView {
        profiles = List.copyOf(profiles);
    }
    public static ConnectionsView empty() { return new ConnectionsView(List.of(), "", "", 0, 0, 0); }

    public @Nullable ConnectionProfileView activeProfile() {
        for (var profile : profiles) if (profile.id().equals(activeProfileId)) return profile;
        return null;
    }

    public List<LinkedEnvironmentView> links() {
        var active = activeProfile();
        return active == null ? List.of() : active.links();
    }

    public boolean includeLinked() {
        var active = activeProfile();
        return active != null && active.includeLinked();
    }

    public boolean linksAvailable() {
        var active = activeProfile();
        return active != null && active.linksAvailable();
    }

    public String linkError() {
        var active = activeProfile();
        return active == null ? "" : active.linkError();
    }

    public boolean readOnly() {
        if (environmentId.isEmpty()) return false;
        return links().stream().noneMatch(link -> link.id().equals(environmentId) && link.reachable() && !link.readOnly());
    }
}
