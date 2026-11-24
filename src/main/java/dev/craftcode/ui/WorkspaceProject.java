package dev.craftcode.ui;

import dev.craftcode.ui.model.ProjectView;
import dev.craftcode.ui.model.WorkspaceEnvironment;

record WorkspaceProject(WorkspaceEnvironment source, ProjectView project) {
    record Key(String profile, String environment, String project) {}
    Key key() { return new Key(source.profileId(), source.environmentId(), project.id()); }
    String name() { return Theme.orDefault(project.name(), project.path()); }
}
