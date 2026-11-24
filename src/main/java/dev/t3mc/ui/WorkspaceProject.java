package dev.t3mc.ui;

import dev.t3mc.ui.model.ProjectView;
import dev.t3mc.ui.model.WorkspaceEnvironment;

record WorkspaceProject(WorkspaceEnvironment source, ProjectView project) {
    record Key(String profile, String environment, String project) {}
    Key key() { return new Key(source.profileId(), source.environmentId(), project.id()); }
    String name() { return Theme.orDefault(project.name(), project.path()); }
}
