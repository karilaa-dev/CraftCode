package dev.craftcode.ui.model;

import java.util.List;

public enum ThreadAction {
    SNOOZE, UNSNOOZE, SETTLE, UNSETTLE;

    public static List<ThreadAction> available(ThreadOrganization organization) {
        return switch (organization) {
            case NORMAL, IN_PROGRESS -> List.of(SNOOZE, SETTLE);
            case SNOOZED -> List.of(UNSNOOZE, SETTLE);
            case SETTLED -> List.of(UNSETTLE);
        };
    }
}
