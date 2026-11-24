package dev.craftcode.ui;

import dev.craftcode.L10n;
import dev.craftcode.ui.model.ChatMessage;
import dev.craftcode.ui.model.MessageRole;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Groups a flat message list into turns like the T3 Code desktop app: the user's message, the intermediate work
 * (assistant progress, tool output, notes) folded behind a "Worked for …" disclosure, then the final reply, which is
 * always shown. Pure logic, no rendering, so it can be unit tested.
 */
final class TranscriptTurns {
    private TranscriptTurns() {}

    /**
     * One turn. {@code key} is stable across refreshes of the same thread (the user message id, or the first message's
     * id for a turn without one); {@code work} is folded; {@code shown} is always visible (the final reply and any
     * trailing notes). {@code active} marks the turn still being produced.
     */
    record Turn(String key, @Nullable ChatMessage user, List<ChatMessage> work, List<ChatMessage> shown, boolean active,
                long startedAt, long endedAt, String runId, int beforePosition) {
        boolean canLoadWork() {
            return !runId.isEmpty() && beforePosition >= 0;
        }

        /** Keep the final response outside the disclosure and preserve previews already attached to messages. */
        Turn withActivity(List<ChatMessage> activity) {
            var rows = new TreeMap<Integer, ChatMessage>();
            for (var row : activity) rows.put(row.position(), row);
            for (var row : work) {
                if (row.position() >= 0 && (rows.get(row.position()) == null || !row.images().isEmpty()))
                    rows.put(row.position(), row);
            }
            for (var row : shown) rows.remove(row.position());
            return new Turn(key, user, List.copyOf(rows.values()), shown, active, startedAt, endedAt, runId, beforePosition);
        }

        boolean hasWork() {
            return !this.work.isEmpty() || canLoadWork();
        }

        /** Text copied for the folded work: every work message, separated by blank lines. */
        String workText() {
            var out = new StringBuilder();
            for (var message : this.work) {
                if (message.text().isBlank()) continue;
                if (!out.isEmpty()) out.append("\n\n");
                out.append(message.text());
            }
            return out.toString();
        }

        /** The latest work message with text, for the one-line preview while a turn is running. */
        @Nullable ChatMessage latestWork() {
            for (int i = this.work.size() - 1; i >= 0; i--) if (!this.work.get(i).text().isBlank()) return this.work.get(i);
            return null;
        }
    }

    static List<Turn> group(List<ChatMessage> messages, boolean threadRunning) {
        var turns = new ArrayList<Turn>();
        if (messages == null || messages.isEmpty()) return turns;

        // Split into raw turns at each user message (and where a different run starts without a user message).
        var raw = new ArrayList<List<ChatMessage>>();
        List<ChatMessage> current = null;
        String currentRun = "";
        for (var message : messages) {
            String run = runId(message);
            boolean newRun = current != null && !run.isEmpty() && !currentRun.isEmpty() && !run.equals(currentRun)
                    && current.stream().anyMatch(m -> m.role() != MessageRole.USER);
            if (current == null || message.role() == MessageRole.USER || newRun || current.getFirst().id().equals("craftcode-history")) {
                current = new ArrayList<>();
                raw.add(current);
                currentRun = "";
            }
            current.add(message);
            if (!run.isEmpty() && message.role() != MessageRole.USER) currentRun = run;
        }

        for (int t = 0; t < raw.size(); t++) {
            var list = raw.get(t);
            boolean last = t == raw.size() - 1;
            ChatMessage user = list.getFirst().role() == MessageRole.USER ? list.getFirst() : null;
            var responses = user == null ? list : list.subList(1, list.size());
            var timing = user == null ? null : user.runTiming();
            String run = user == null ? "" : user.runId();
            for (var response : responses) {
                if (!runId(response).isEmpty()) {
                    timing = response.runTiming();
                    run = response.runId();
                    break;
                }
            }
            boolean streaming = responses.stream().anyMatch(ChatMessage::streaming);
            boolean completed = timing != null && timing.completedAtMillis() > 0;
            boolean active = last && !completed && (threadRunning || streaming);

            int finalIndex = finalIndex(responses, active);
            var work = new ArrayList<ChatMessage>();
            var shown = new ArrayList<ChatMessage>();
            for (int i = 0; i < responses.size(); i++) {
                var message = responses.get(i);
                if (finalIndex < 0) {
                    // No final reply (yet): everything is work, except trailing notes of a finished turn.
                    if (!active && message.role() == MessageRole.SYSTEM && i == responses.size() - 1) shown.add(message);
                    else work.add(message);
                } else if (i < finalIndex || i > finalIndex && (message.role() == MessageRole.TOOL || isWorkPhase(phase(message)))) {
                    work.add(message);
                } else {
                    shown.add(message);
                }
            }

            long start = user != null ? user.createdAtMillis() : list.getFirst().createdAtMillis();
            if (timing != null && timing.startedAtMillis() > 0) start = timing.startedAtMillis();
            long end = completed ? timing.completedAtMillis() : list.getLast().createdAtMillis();
            String key = (user != null ? user : list.getFirst()).id();
            int before = -1;
            if (list.stream().anyMatch(m -> m.position() >= 0)) {
                before = last ? Integer.MAX_VALUE : raw.get(t + 1).getFirst().position();
            }
            turns.add(new Turn(key, user, List.copyOf(work), List.copyOf(shown), active, start, end, run, before));
        }
        return turns;
    }

    /**
     * Index of the reply that stays visible. A message tagged with a final phase wins. Otherwise it is the last
     * assistant message, provided nothing but notes follow it; while the turn is running only a streaming assistant
     * message at the very end counts (earlier ones are progress).
     */
    private static int finalIndex(List<ChatMessage> responses, boolean active) {
        for (int i = responses.size() - 1; i >= 0; i--) {
            if (responses.get(i).role() == MessageRole.ASSISTANT && isFinalPhase(phase(responses.get(i)))) return i;
        }
        for (int i = responses.size() - 1; i >= 0; i--) {
            var message = responses.get(i);
            if (message.role() == MessageRole.SYSTEM && !active) continue;
            if (message.role() != MessageRole.ASSISTANT) return -1;
            if (isWorkPhase(phase(message))) return -1;
            if (active && !message.streaming()) return -1;
            return i;
        }
        return -1;
    }

    private static boolean isFinalPhase(String phase) {
        String p = phase.toLowerCase(Locale.ROOT);
        return p.equals("final") || p.equals("final_answer") || p.equals("answer");
    }

    private static boolean isWorkPhase(String phase) {
        String p = phase.toLowerCase(Locale.ROOT);
        return p.equals("commentary") || p.equals("progress") || p.equals("reasoning") || p.equals("thinking") || p.equals("proposed_plan");
    }

    private static String runId(ChatMessage message) {
        String run = message.runId();
        return run == null ? "" : run;
    }

    private static String phase(ChatMessage message) {
        String phase = message.phase();
        return phase == null ? "" : phase;
    }

    /** "3m 33s", "45s", "1h 2m"; empty when unknown or negative. */
    static String duration(long millis) {
        if (millis < 0) return "";
        long seconds = millis / 1000;
        if (seconds < 60) return L10n.tr("chat.duration.s", "%ss", seconds);
        long minutes = seconds / 60;
        if (minutes < 60) return L10n.tr("chat.duration.m_s", "%1$sm %2$ss", minutes, seconds % 60);
        return L10n.tr("chat.duration.h_m", "%1$sh %2$sm", minutes / 60, minutes % 60);
    }

    /** Disclosure label: "Worked for 3m 33s" / "Working for 12s", or a step count when times are unknown. */
    static String workLabel(Turn turn, long now) {
        long end = turn.active() ? now : turn.endedAt();
        boolean known = turn.startedAt() > 0 && end >= turn.startedAt();
        String time = known ? duration(end - turn.startedAt()) : "";
        if (turn.active()) return time.isEmpty() ? L10n.tr("chat.work.active", "Working…")
                : L10n.tr("chat.work.active_for", "Working for %s", time);
        if (!time.isEmpty()) return L10n.tr("chat.work.done_for", "Worked for %s", time);
        int steps = turn.work().size();
        if (steps == 0) return L10n.tr("chat.work.done", "Worked");
        return steps == 1 ? L10n.tr("chat.work.done_step", "Worked · %s step", steps)
                : L10n.tr("chat.work.done_steps", "Worked · %s steps", steps);
    }
}
