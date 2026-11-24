package dev.t3mc.ui.model;

import java.util.List;
import java.util.Objects;

/**
 * A question or approval request waiting on the user. Options render as buttons.
 * With {@code multiSelect}, the user may pick several options and submits them together
 * through {@code ClientController.answerQuestion(threadId, requestId, List<String>)}.
 */
public record PendingQuestion(String requestId, String prompt, List<String> options, boolean allowFreeText,
                              boolean multiSelect) {
    public PendingQuestion {
        requestId = Objects.requireNonNull(requestId, "requestId");
        prompt = Objects.requireNonNullElse(prompt, "");
        options = options == null ? List.of() : List.copyOf(options);
    }

    public PendingQuestion(String requestId, String prompt, List<String> options, boolean allowFreeText) {
        this(requestId, prompt, options, allowFreeText, false);
    }
}
