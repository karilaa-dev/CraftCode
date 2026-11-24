package dev.craftcode.ui;

import dev.craftcode.L10n;
import dev.craftcode.ui.model.ChatMessage;
import dev.craftcode.ui.model.MessageRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/** A contiguous stretch of activity between progress messages. */
record WorkGroup(List<ChatMessage> items) {
    static boolean folds(ChatMessage item) {
        return item.role() == MessageRole.TOOL || item.role() == MessageRole.ASSISTANT
                && (item.phase().equals("reasoning") || item.phase().equals("thinking"));
    }

    static List<WorkGroup> group(List<ChatMessage> work) {
        var groups = new ArrayList<WorkGroup>();
        int start = 0;
        while (start < work.size()) {
            int end = start + 1;
            if (folds(work.get(start))) {
                while (end < work.size() && folds(work.get(end))) end++;
            }
            groups.add(new WorkGroup(work.subList(start, end)));
            start = end;
        }
        return groups;
    }

    String details() {
        return items.stream().map(ChatMessage::text).filter(s -> !s.isBlank()).collect(Collectors.joining("\n\n"));
    }

    String label() {
        var counts = new LinkedHashMap<String, Integer>();
        for (var item : items) {
            if (item.role() != MessageRole.TOOL) continue;
            String kind = switch (item.phase()) {
                case "command_execution" -> "command";
                case "web_search" -> "web";
                case "file_search" -> "search";
                case "file_change" -> "edit";
                default -> "other";
            };
            counts.merge(kind, 1, Integer::sum);
        }
        if (counts.isEmpty()) return items.size() == 1 ? L10n.tr("chat.work.thought", "Thought")
                : L10n.tr("chat.work.thoughts", "Thought (×%s)", items.size());
        var parts = new ArrayList<String>();
        counts.forEach((kind, count) -> {
            String phrase = switch (kind) {
                case "command" -> count == 1 ? L10n.tr("chat.work.command", "ran 1 command") : L10n.tr("chat.work.commands", "ran %s commands", count);
                case "web" -> count == 1 ? L10n.tr("chat.work.web", "searched the web 1 time") : L10n.tr("chat.work.web_many", "searched the web %s times", count);
                case "search" -> count == 1 ? L10n.tr("chat.work.search", "searched files once") : L10n.tr("chat.work.search_many", "searched files %s times", count);
                case "edit" -> count == 1 ? L10n.tr("chat.work.edit", "made 1 file change") : L10n.tr("chat.work.edits", "made %s file changes", count);
                default -> count == 1 ? L10n.tr("chat.work.tool", "used 1 tool") : L10n.tr("chat.work.tools", "made %s tool calls", count);
            };
            parts.add(phrase);
        });
        String last = parts.removeLast();
        String label = parts.isEmpty() ? last : L10n.tr("chat.work.join", "%1$s and %2$s", String.join(", ", parts), last);
        return label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }
}
