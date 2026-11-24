package dev.craftcode.ui;

import dev.craftcode.ui.model.ChatMessage;
import dev.craftcode.ui.model.MessageRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptTurnsTest {
    private static ChatMessage msg(String id, MessageRole role, long at) {
        return new ChatMessage(id, role, id + " text", at, false);
    }

    private static ChatMessage streaming(String id, long at) {
        return new ChatMessage(id, MessageRole.ASSISTANT, id + " text", at, true);
    }

    private static List<String> ids(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::id).toList();
    }

    @Test
    void foldsIntermediateWorkAndKeepsFinal() {
        var turns = TranscriptTurns.group(List.of(
                msg("u1", MessageRole.USER, 1_000),
                msg("a1", MessageRole.ASSISTANT, 2_000),
                msg("t1", MessageRole.TOOL, 3_000),
                msg("a2", MessageRole.ASSISTANT, 214_000),
                msg("u2", MessageRole.USER, 300_000),
                msg("a3", MessageRole.ASSISTANT, 305_000)), false);
        assertEquals(2, turns.size());
        var first = turns.getFirst();
        assertEquals("u1", first.key());
        assertEquals("u1", first.user().id());
        assertEquals(List.of("a1", "t1"), ids(first.work()));
        assertEquals(List.of("a2"), ids(first.shown()));
        assertFalse(first.active());
        assertEquals("Worked for 3m 33s", TranscriptTurns.workLabel(first, 0));
        var second = turns.get(1);
        assertFalse(second.hasWork());
        assertEquals(List.of("a3"), ids(second.shown()));
    }

    @Test
    void userMessagesAreNeverFolded() {
        var turns = TranscriptTurns.group(List.of(msg("u1", MessageRole.USER, 1), msg("u2", MessageRole.USER, 2)), false);
        assertEquals(2, turns.size());
        for (var turn : turns) {
            assertNotNull(turn.user());
            assertTrue(turn.work().isEmpty());
        }
    }

    @Test
    void activeTurnFoldsProgressButShowsStreamingReply() {
        var turns = TranscriptTurns.group(List.of(
                msg("u1", MessageRole.USER, 1_000),
                msg("a1", MessageRole.ASSISTANT, 2_000),
                msg("t1", MessageRole.TOOL, 3_000),
                streaming("a2", 4_000)), true);
        var turn = turns.getFirst();
        assertTrue(turn.active());
        assertEquals(List.of("a1", "t1"), ids(turn.work()));
        assertEquals(List.of("a2"), ids(turn.shown()));
        assertEquals("Working for 11s", TranscriptTurns.workLabel(turn, 12_000));
    }

    @Test
    void runningTurnWithoutStreamingReplyFoldsEverything() {
        var turns = TranscriptTurns.group(List.of(
                msg("u1", MessageRole.USER, 1_000),
                msg("a1", MessageRole.ASSISTANT, 2_000),
                msg("t1", MessageRole.TOOL, 3_000)), true);
        var turn = turns.getFirst();
        assertTrue(turn.active());
        assertEquals(List.of("a1", "t1"), ids(turn.work()));
        assertTrue(turn.shown().isEmpty());
        assertEquals("t1", turn.latestWork().id());
    }

    @Test
    void onlyLastTurnIsActive() {
        var turns = TranscriptTurns.group(List.of(
                msg("u1", MessageRole.USER, 1), msg("a1", MessageRole.ASSISTANT, 2),
                msg("u2", MessageRole.USER, 3)), true);
        assertFalse(turns.getFirst().active());
        assertEquals(List.of("a1"), ids(turns.getFirst().shown()));
        assertTrue(turns.getLast().active());
    }

    @Test
    void finishedTurnEndingInToolHasNoFinal() {
        var turns = TranscriptTurns.group(List.of(
                msg("u1", MessageRole.USER, 1), msg("a1", MessageRole.ASSISTANT, 2), msg("t1", MessageRole.TOOL, 3)), false);
        assertEquals(List.of("a1", "t1"), ids(turns.getFirst().work()));
        assertTrue(turns.getFirst().shown().isEmpty());
    }

    @Test
    void trailingNoteStaysVisible() {
        var turns = TranscriptTurns.group(List.of(
                msg("u1", MessageRole.USER, 1), msg("a1", MessageRole.ASSISTANT, 2), msg("t1", MessageRole.TOOL, 3),
                msg("a2", MessageRole.ASSISTANT, 4), msg("s1", MessageRole.SYSTEM, 5)), false);
        assertEquals(List.of("a2", "s1"), ids(turns.getFirst().shown()));
    }

    @Test
    void phasesOverrideHeuristics() {
        var commentary = new ChatMessage("a1", MessageRole.ASSISTANT, "x", 2, false, "run", "commentary", List.of());
        var answer = new ChatMessage("a2", MessageRole.ASSISTANT, "y", 3, false, "run", "final_answer", List.of());
        var late = new ChatMessage("a3", MessageRole.ASSISTANT, "z", 4, false, "run", "commentary", List.of());
        var turns = TranscriptTurns.group(List.of(msg("u1", MessageRole.USER, 1), commentary, answer, late), false);
        assertEquals(List.of("a1", "a3"), ids(turns.getFirst().work()));
        assertEquals(List.of("a2"), ids(turns.getFirst().shown()));
    }

    @Test
    void differentRunWithoutUserStartsNewTurn() {
        var a = new ChatMessage("a1", MessageRole.ASSISTANT, "x", 2, false, "r1", "", List.of());
        var b = new ChatMessage("b1", MessageRole.ASSISTANT, "y", 3, false, "r2", "", List.of());
        var turns = TranscriptTurns.group(List.of(msg("u1", MessageRole.USER, 1), a, b), false);
        assertEquals(2, turns.size());
        assertNull(turns.getLast().user());
        assertEquals("b1", turns.getLast().key());
    }

    @Test
    void unknownTimesFallBackToStepCount() {
        var turns = TranscriptTurns.group(List.of(
                msg("u1", MessageRole.USER, 0), msg("t1", MessageRole.TOOL, 0), msg("a1", MessageRole.ASSISTANT, 0)), false);
        assertEquals("Worked · 1 step", TranscriptTurns.workLabel(turns.getFirst(), 0));
    }

    @Test
    void completedRunUsesServerDurationWithoutVisibleUserOrFinalMessageTimes() {
        var timing = new ChatMessage.RunTiming(1_000, 241_000);
        var tool = new ChatMessage("tool", MessageRole.TOOL, "Read", 150_000, false, "run", "", List.of(), timing);
        var reply = new ChatMessage("reply", MessageRole.ASSISTANT, "Done", 180_000, false, "run", "", List.of(), timing);
        // The thread can already have another running turn outside the current page.
        var turn = TranscriptTurns.group(List.of(tool, reply), true).getFirst();
        assertNull(turn.user());
        assertFalse(turn.active());
        assertEquals("Worked for 4m 0s", TranscriptTurns.workLabel(turn, 300_000));
    }

    @Test
    void activeRunStartsAtServerStartAndOlderRunFallsBackToItsOwnMessages() {
        var running = new ChatMessage("work", MessageRole.TOOL, "Testing", 95_000, false, "new", "", List.of(),
                new ChatMessage.RunTiming(60_000, 0));
        var turns = TranscriptTurns.group(List.of(
                msg("u1", MessageRole.USER, 10_000),
                new ChatMessage("old", MessageRole.TOOL, "Read", 25_000, false, "old", "", List.of()),
                msg("u2", MessageRole.USER, 30_000), running), true);
        assertEquals("Worked for 15s", TranscriptTurns.workLabel(turns.getFirst(), 120_000));
        assertEquals("Working for 1m 0s", TranscriptTurns.workLabel(turns.getLast(), 120_000));
    }

    @Test
    void finishedRunCanRevealWorkWhenOnlyItsFinalReplyMadeTheMessagesPage() {
        var history = msg("craftcode-history", MessageRole.SYSTEM, 0);
        var finalReply = new ChatMessage("reply", MessageRole.ASSISTANT, "Done", 10, false, "r", "", List.of(), null, 9);
        var nextUser = new ChatMessage("next", MessageRole.USER, "Next task", 11, false, "next-run", "", List.of(), null, 10);
        var turns = TranscriptTurns.group(List.of(history, finalReply, nextUser), false);
        var turn = turns.get(1);
        assertNull(turn.user());
        assertTrue(turn.canLoadWork());
        assertEquals(10, turn.beforePosition());
        assertTrue(turn.work().isEmpty());
        var first = new ChatMessage("first", MessageRole.ASSISTANT, "Checking", 1, false, "r", "reasoning", List.of(), null, 1);
        var last = new ChatMessage("last", MessageRole.TOOL, "Build", 8, false, "r", "", List.of(), null, 8);
        var expanded = turn.withActivity(List.of(first, last, finalReply));
        assertEquals(List.of("first", "last"), ids(expanded.work()));
        assertEquals(List.of("reply"), ids(expanded.shown()));
    }

    @Test
    void durations() {
        assertEquals("0s", TranscriptTurns.duration(999));
        assertEquals("59s", TranscriptTurns.duration(59_000));
        assertEquals("1m 0s", TranscriptTurns.duration(60_000));
        assertEquals("1h 1m", TranscriptTurns.duration(3_660_000));
    }
}
