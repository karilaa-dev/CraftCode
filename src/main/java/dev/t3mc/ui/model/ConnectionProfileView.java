package dev.t3mc.ui.model;

import java.util.List;
import java.util.Map;

/** A saved MCP endpoint. The session grant is private to the connection manager. */
public record ConnectionProfileView(String id, String name, String endpoint, boolean hasSavedSession, ConnectionState state,
                                    boolean includeLinked, boolean linksAvailable, List<LinkedEnvironmentView> links, String linkError,
                                    boolean refreshingLinks, boolean linksExpanded, Map<String, EnvironmentDisplay> displays) {
    public EnvironmentDisplay display(String id) { return displays.getOrDefault(id, EnvironmentDisplay.DEFAULT); }
    public ConnectionProfileView {
        links = List.copyOf(links);
        displays = Map.copyOf(displays);
    }
}
