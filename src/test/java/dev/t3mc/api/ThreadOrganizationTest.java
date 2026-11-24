package dev.t3mc.api;

import dev.t3mc.ui.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static dev.t3mc.ui.model.ThreadOrganization.*;
import static org.junit.jupiter.api.Assertions.*;

class ThreadOrganizationTest {
    @Test void groupsWorkingThreadsSeparatelyFromThoseReadyForTheUser() {
        for (var status : List.of("preparing", "queued", "starting", "running", "waiting")) {
            var thread = thread(status, false, false, false);
            assertEquals(IN_PROGRESS, thread.organization(false));
            assertEquals(NORMAL, thread.organization(true));
            assertEquals(ThreadStatus.WAITING_FOR_INPUT, thread.status(true));
        }
        for (var status : List.of("idle", "completed", "interrupted", "cancelled", "rolled_back", "failed"))
            assertEquals(NORMAL, thread(status, false, false, false).organization(false));
        assertEquals(NORMAL, thread("running", false, true, false).organization(false));
        assertEquals(SNOOZED, thread("running", false, false, true).organization(false));
        assertEquals(SNOOZED, thread("completed", false, true, true).organization(true));
        assertEquals(SETTLED, thread("idle", true, false, true).organization(false));
    }

    @Test void explicitSettlementSurvivesRunStatusAndModelChanges() {
        var thread = thread("completed", true, false, false);
        assertEquals(SETTLED, thread.organization(false));
        assertEquals(SETTLED, thread("running", true, false, false).organization(true));
        var detail = new ThreadDetail(thread.id(), thread.projectId(), thread.title(), thread.status(false),
                List.of(), List.of(), ModelSelection.empty(), thread.organization(false));
        assertEquals(SETTLED, detail.withModelSelection(new ModelSelection("p", "m", Map.of())).organization());
    }

    private static T3Api.ThreadInfo thread(String status, boolean settled, boolean pinned, boolean snoozed) {
        return new T3Api.ThreadInfo("one", "project", "Title", status, "run", 123, settled, "p", "m", pinned, snoozed, 0);
    }
}
