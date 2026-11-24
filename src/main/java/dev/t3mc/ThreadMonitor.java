package dev.t3mc;

import static dev.t3mc.L10n.tr;

import dev.t3mc.api.T3Api;
import dev.t3mc.ui.model.ThreadStatus;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/** Follows top-level threads across projects, including while the workspace screen is closed. */
final class ThreadMonitor {
    private final T3Api api;
    private final BiConsumer<String,String> notify;
    private final boolean canReadQuestions;
    private boolean useEvents;
    private boolean baseline;
    private Map<String,T3Api.ThreadInfo> previous = Map.of();
    private final Map<String,Set<String>> pending = new HashMap<>();
    private final Set<String> pendingInput = new HashSet<>();
    private final LinkedHashSet<String> delivered = new LinkedHashSet<>();

    ThreadMonitor(T3Api api, BiConsumer<String,String> notify, boolean canReadQuestions, boolean useEvents) {
        this.api = api;
        this.notify = notify;
        this.canReadQuestions = canReadQuestions;
        this.useEvents = useEvents;
    }

    boolean hasQuestions(String threadId) { return !pending.getOrDefault(threadId, Set.of()).isEmpty(); }
    boolean hasPendingInput(String threadId) { return pendingInput.contains(threadId); }

    void update(List<T3Api.ThreadInfo> threads) throws IOException {
        Map<String,T3Api.ThreadInfo> latest = new HashMap<>();
        for (var thread : threads) latest.put(thread.id(), thread);
        boolean reconcile = !useEvents;
        Set<String> newInputs = new HashSet<>();
        if (useEvents) {
            try {
                var batch = api.events();
                for (var event : batch.entries()) {
                    var thread = latest.get(event.threadId());
                    // Thread creation may race the preceding thread list.
                    String title = thread == null ? tr("notice.thread", "T3 Code thread %s", event.threadId()) : thread.title();
                    if (event.type().equals("thread.needs_input")) {
                        newInputs.add(event.threadId());
                        emit("request:" + event.threadId() + "/" + event.requestId(), tr("notice.needs_input", "Agent needs your input"), title);
                    } else if (event.type().equals("thread.run_settled")) {
                        settled(event.threadId(), title, event.runId(), event.outcome(), event.occurredAtMillis());
                    }
                }
                reconcile = batch.truncated();
            } catch (IOException e) {
                useEvents = false;
                reconcile = true;
            }
        }
        for (var thread : threads) {
            var old = previous.get(thread.id());
            boolean changed = !thread.equals(old);
            boolean active = thread.status(false) == ThreadStatus.RUNNING;
            if (reconcile && baseline && changed && (old == null || !old.status().equals(thread.status()) || !old.runId().equals(thread.runId())))
                settled(thread.id(), thread.title(), thread.runId(), thread.status(), thread.updatedAtMillis());
            // A question or approval can arrive without changing the run's listed status.
            // Without the event feed, check each live thread once per poll. Only fetch IDs
            // when its read projection reports an outstanding request.
            if (hasPendingInput(thread.id()) || newInputs.contains(thread.id())
                    || active && (reconcile || changed) || reconcile && baseline && changed) {
                boolean waiting = api.hasPendingRequest(thread.id());
                if (waiting) pendingInput.add(thread.id());
                else pendingInput.remove(thread.id());
                Set<String> ids = waiting && canReadQuestions ? Set.copyOf(api.questionIds(thread.id())) : Set.of();
                if (reconcile && baseline) for (String id : ids) {
                    if (!pending.getOrDefault(thread.id(), Set.of()).contains(id))
                        emit("request:" + thread.id() + "/" + id, tr("notice.question", "Agent has a question"), thread.title());
                }
                if (ids.isEmpty()) pending.remove(thread.id());
                else pending.put(thread.id(), ids);
            }
            if (reconcile && !canReadQuestions && baseline && "waiting".equals(thread.status()) && changed
                    && (old == null || !old.status().equals("waiting"))) {
                notify.accept(tr("notice.waiting", "Agent is waiting"), thread.title());
            }
        }
        pending.keySet().retainAll(latest.keySet());
        pendingInput.retainAll(latest.keySet());
        previous = latest;
        baseline = true;
    }

    private void settled(String threadId, String title, String runId, String outcome, long time) {
        if (outcome.equals("failed") || outcome.equals("completed")) {
            String id = runId == null || runId.isBlank() ? Long.toString(time) : runId;
            emit("run:" + threadId + "/" + id + "/" + outcome,
                    outcome.equals("failed") ? tr("notice.agent_error", "Agent error") : tr("notice.finished", "Agent finished"), title);
        }
    }

    private void emit(String key, String title, String body) {
        if (delivered.add(key)) {
            if (delivered.size() > 2048) delivered.removeFirst();
            notify.accept(title, body);
        }
    }
}
