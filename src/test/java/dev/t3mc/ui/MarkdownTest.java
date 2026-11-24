package dev.t3mc.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownTest {
    @Test
    void longRulesTablesAndNestedLabelsFitTheHistoryResponseLimit() {
        assertEquals(Markdown.Kind.RULE, Markdown.parse("-".repeat(20_000)).getFirst().kind());
        assertTrue(Markdown.parse("|---".repeat(4_000) + "|").isEmpty());
        assertFalse(Markdown.inline("[".repeat(1_500) + "caption" + "](x)".repeat(1_500)).isEmpty());
    }

    private static Markdown.Span only(List<Markdown.Span> spans, String text) {
        return spans.stream().filter(s -> s.text().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no span '" + text + "' in " + spans));
    }

    @Test
    void parsesBlocks() {
        var blocks = Markdown.parse("""
                # Title
                Some *text* here
                continues.

                - one
                - two
                  wrapped
                    - nested
                1. first
                2) second
                > quoted
                ---
                ```java
                int x = 1;

                ```
                | a | b |
                |---|---|
                | 1 | 2 |
                """);
        var kinds = blocks.stream().map(Markdown.Block::kind).toList();
        assertEquals(List.of(Markdown.Kind.HEADING, Markdown.Kind.PARAGRAPH, Markdown.Kind.BULLET, Markdown.Kind.BULLET,
                Markdown.Kind.BULLET, Markdown.Kind.ORDERED, Markdown.Kind.ORDERED, Markdown.Kind.QUOTE, Markdown.Kind.RULE,
                Markdown.Kind.CODE, Markdown.Kind.TABLE_ROW, Markdown.Kind.TABLE_ROW), kinds);
        assertEquals(1, blocks.get(0).level());
        assertEquals("Some text here continues.", blocks.get(1).plain());
        assertEquals("two wrapped", blocks.get(3).plain());
        assertEquals(2, blocks.get(4).level());
        assertEquals("2.", blocks.get(6).marker());
        assertEquals("java", blocks.get(9).lang());
        assertEquals(List.of("int x = 1;", ""), blocks.get(9).code());
        assertEquals("a  │  b", blocks.get(10).plain());
    }

    @Test
    void unclosedFenceStaysCode() {
        var blocks = Markdown.parse("Start\n```\npartial **not bold**");
        assertEquals(Markdown.Kind.CODE, blocks.getLast().kind());
        assertEquals(List.of("partial **not bold**"), blocks.getLast().code());
    }

    @Test
    void hardBreakKeepsNewline() {
        assertEquals("a\nb c", Markdown.parse("a  \nb\nc").getFirst().plain());
    }

    @Test
    void inlineEmphasisAndCode() {
        var spans = Markdown.inline("**bold** and *it* and ***both*** and ~~gone~~ and `a*b*c`");
        assertTrue(only(spans, "bold").has(Markdown.BOLD));
        assertTrue(only(spans, "it").has(Markdown.ITALIC));
        assertTrue(only(spans, "both").has(Markdown.BOLD));
        assertTrue(only(spans, "both").has(Markdown.ITALIC));
        assertTrue(only(spans, "gone").has(Markdown.STRIKE));
        assertTrue(only(spans, "a*b*c").has(Markdown.CODE));
    }

    @Test
    void snakeCaseAndLoneStarsStayLiteral() {
        assertEquals("my_var_name and 2 * 3 * 4", String.join("", Markdown.inline("my_var_name and 2 * 3 * 4").stream()
                .map(Markdown.Span::text).toList()));
        assertEquals("**unclosed", Markdown.inline("**unclosed").getFirst().text());
        assertEquals("*a*", String.join("", Markdown.inline("\\*a\\*").stream().map(Markdown.Span::text).toList()));
    }

    @Test
    void markdownLinks() {
        var spans = Markdown.inline("See [the docs](https://example.com/a_(b)) now");
        var link = only(spans, "the docs");
        assertEquals("https://example.com/a_(b)", link.url());
        assertNull(only(spans, "See ").url());
    }

    @Test
    void linkWithFormattedLabel() {
        var spans = Markdown.inline("[**PR #12**](https://github.com/x/y/pull/12)");
        var bold = only(spans, "PR #12");
        assertTrue(bold.has(Markdown.BOLD));
        assertEquals("https://github.com/x/y/pull/12", bold.url());
    }

    @Test
    void nonWebLinksKeepLabelOnly() {
        var spans = Markdown.inline("open [file.ts](src/file.ts:42) or [x](javascript:alert(1)) or [m](file:///etc/passwd)");
        for (var span : spans) assertNull(span.url(), span.toString());
        assertEquals("open file.ts or x or m", String.join("", spans.stream().map(Markdown.Span::text).toList()));
    }

    @Test
    void autolinks() {
        var spans = Markdown.inline("Visit https://example.com/path?q=1. Or (http://foo.org/x) and <https://bar.dev>.");
        assertEquals("https://example.com/path?q=1", only(spans, "https://example.com/path?q=1").url());
        assertEquals("http://foo.org/x", only(spans, "http://foo.org/x").url());
        assertEquals("https://bar.dev", only(spans, "https://bar.dev").url());
    }

    @Test
    void noAutolinkInsideCodeOrWords() {
        var spans = Markdown.inline("`https://example.com` xhttps://nope.com");
        for (var span : spans) assertNull(span.url(), span.toString());
    }

    @Test
    void safeUrlRejectsUnsafe() {
        assertNull(Markdown.safeUrl("ftp://example.com"));
        assertNull(Markdown.safeUrl("https://"));
        assertNull(Markdown.safeUrl("https://exa mple.com"));
        assertNull(Markdown.safeUrl("/relative"));
        assertEquals("HTTPS://Example.com", Markdown.safeUrl("HTTPS://Example.com"));
    }

    @Test
    void taskListMarkers() {
        var blocks = Markdown.parse("- [ ] todo\n- [x] done");
        assertEquals("☐", blocks.get(0).marker());
        assertEquals("☑", blocks.get(1).marker());
        assertEquals("done", blocks.get(1).plain());
    }

    @Test
    void emptyAndNull() {
        assertTrue(Markdown.parse("").isEmpty());
        assertTrue(Markdown.parse(null).isEmpty());
        assertTrue(Markdown.parse("\n\n  \n").isEmpty());
    }
}
