package dev.craftcode.ui;

import dev.craftcode.ui.model.ChatMessage;
import dev.craftcode.ui.model.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkGroupTest {
    private ChatMessage row(String id, MessageRole role, String type) {
        return new ChatMessage(id, role, id, 0, false, "r", type, List.of());
    }

    @Test void twoThoughtsThenCommentaryThenMixedToolsStayInOrder() {
        var first = row("first", MessageRole.ASSISTANT, "reasoning");
        var second = row("second", MessageRole.ASSISTANT, "reasoning");
        var comment = row("Checking the listing", MessageRole.ASSISTANT, "commentary");
        var command = row("$ whoami", MessageRole.TOOL, "command_execution");
        var web = row("Search results", MessageRole.TOOL, "web_search");
        var groups = WorkGroup.group(List.of(first, second, comment, command, web));
        assertEquals(3, groups.size());
        assertEquals("Thought (×2)", groups.getFirst().label());
        assertEquals(List.of(comment), groups.get(1).items());
        assertEquals("Ran 1 command and searched the web 1 time", groups.getLast().label());
        assertEquals("$ whoami\n\nSearch results", groups.getLast().details());
    }

    @Test void repeatedGenericToolsCountEvenWithoutOutput() {
        var tool = row("tool", MessageRole.TOOL, "dynamic_tool");
        assertEquals("Made 2 tool calls", WorkGroup.group(List.of(tool, tool)).getFirst().label());
    }
}
