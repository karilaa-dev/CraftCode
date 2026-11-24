package dev.craftcode.ui;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, dependency-free Markdown parser for chat messages. Produces blocks (paragraphs, headings, list items,
 * quotes, fenced code, rules, table rows) of inline spans (bold, italic, strike, code, links). Pure data so it can be
 * unit tested without Minecraft; {@link MessageFormatter} turns it into styled text. Only http(s) links are kept as
 * links; anything else renders as plain text.
 */
final class Markdown {
    private Markdown() {}

    static final int BOLD = 1, ITALIC = 2, STRIKE = 4, CODE = 8;

    /** A run of text with uniform style. {@code url} is a validated http(s) URL or null. */
    record Span(String text, int flags, @Nullable String url) {
        Span {
            text = text == null ? "" : text;
        }

        boolean has(int flag) {
            return (this.flags & flag) != 0;
        }
    }

    enum Kind { PARAGRAPH, HEADING, BULLET, ORDERED, QUOTE, CODE, RULE, TABLE_ROW }

    /**
     * One block. {@code level} is the heading level (1–6) or the list/quote nesting depth (0-based); {@code marker} is
     * the list marker ("•", "3.", "☐"/"☑"); {@code code} holds the raw lines of a fenced block; {@code lang} its tag.
     */
    record Block(Kind kind, int level, String marker, List<Span> spans, List<String> code, String lang) {
        Block {
            marker = marker == null ? "" : marker;
            spans = spans == null ? List.of() : List.copyOf(spans);
            code = code == null ? List.of() : List.copyOf(code);
            lang = lang == null ? "" : lang;
        }

        static Block text(Kind kind, int level, String marker, List<Span> spans) {
            return new Block(kind, level, marker, spans, List.of(), "");
        }

        /** Plain text of the inline spans. */
        String plain() {
            var out = new StringBuilder();
            for (var span : this.spans) out.append(span.text());
            return out.toString();
        }
    }

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:\\s+(.*?))?\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])(?:\\s*+\\1){2,}+\\s*+$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*+\\|?\\s*+:?-{2,}+:?\\s*+(?:\\|\\s*+:?-{2,}+:?\\s*+)*+\\|?\\s*+$");

    static List<Block> parse(String raw) {
        var blocks = new ArrayList<Block>();
        if (raw == null || raw.isEmpty()) return blocks;
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1);
        var paragraph = new ArrayList<String>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                flushParagraph(blocks, paragraph);
                String marker = fence.group(1);
                String lang = fence.group(2) == null ? "" : fence.group(2);
                var code = new ArrayList<String>();
                i++;
                while (i < lines.length) {
                    String next = lines[i];
                    String stripped = next.strip();
                    if (stripped.startsWith(marker.substring(0, 3)) && stripped.chars().allMatch(c -> c == marker.charAt(0))
                            && stripped.length() >= marker.length()) {
                        i++;
                        break;
                    }
                    code.add(next);
                    i++;
                }
                // A streaming message may end inside an unclosed fence; that is fine, it is still code.
                blocks.add(new Block(Kind.CODE, 0, "", List.of(), code, lang));
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(blocks, paragraph);
                i++;
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(blocks, paragraph);
                String text = heading.group(2) == null ? "" : heading.group(2);
                blocks.add(Block.text(Kind.HEADING, heading.group(1).length(), "", inline(text)));
                i++;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                // "---" right under a paragraph line is a setext heading in CommonMark; agents mean a rule far more often,
                // but keep the heading reading when there is pending text.
                if (!paragraph.isEmpty() && line.strip().startsWith("-")) {
                    String text = joinParagraph(paragraph);
                    paragraph.clear();
                    blocks.add(Block.text(Kind.HEADING, 2, "", inline(text)));
                } else {
                    flushParagraph(blocks, paragraph);
                    blocks.add(Block.text(Kind.RULE, 0, "", List.of()));
                }
                i++;
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                flushParagraph(blocks, paragraph);
                int depth = depth(bullet.group(1));
                String body = bullet.group(2);
                String marker = depth % 2 == 0 ? "•" : "◦";
                Matcher task = TASK.matcher(body);
                if (task.matches()) {
                    marker = task.group(1).isBlank() ? "☐" : "☑";
                    body = task.group(2);
                }
                i = listItem(blocks, lines, i, Kind.BULLET, depth, marker, body);
                continue;
            }
            Matcher ordered = ORDERED.matcher(line);
            if (ordered.matches()) {
                flushParagraph(blocks, paragraph);
                int depth = depth(ordered.group(1));
                i = listItem(blocks, lines, i, Kind.ORDERED, depth, ordered.group(2) + ".", ordered.group(3));
                continue;
            }
            Matcher quote = QUOTE.matcher(line);
            if (quote.matches()) {
                flushParagraph(blocks, paragraph);
                var text = new StringBuilder(quote.group(1).strip());
                i++;
                while (i < lines.length) {
                    Matcher more = QUOTE.matcher(lines[i]);
                    if (!more.matches()) break;
                    String part = more.group(1).strip();
                    if (part.isEmpty()) {
                        blocks.add(Block.text(Kind.QUOTE, 0, "", inline(text.toString())));
                        text.setLength(0);
                    } else {
                        if (!text.isEmpty()) text.append(' ');
                        text.append(part);
                    }
                    i++;
                }
                if (!text.isEmpty()) blocks.add(Block.text(Kind.QUOTE, 0, "", inline(text.toString())));
                continue;
            }
            if (line.strip().startsWith("|")) {
                flushParagraph(blocks, paragraph);
                if (!TABLE_SEP.matcher(line).matches()) blocks.add(Block.text(Kind.TABLE_ROW, 0, "", inline(tableRow(line))));
                i++;
                continue;
            }
            paragraph.add(line);
            i++;
        }
        flushParagraph(blocks, paragraph);
        return blocks;
    }

    private static int listItem(List<Block> blocks, String[] lines, int i, Kind kind, int depth, String marker, String body) {
        var text = new StringBuilder(body.strip());
        int indent = leadingSpaces(lines[i]);
        i++;
        // Lazy continuation: indented, non-list, non-blank lines belong to the item.
        while (i < lines.length) {
            String next = lines[i];
            if (next.isBlank()) break;
            if (leadingSpaces(next) <= indent) break;
            if (BULLET.matcher(next).matches() || ORDERED.matcher(next).matches() || FENCE.matcher(next).matches()) break;
            text.append(' ').append(next.strip());
            i++;
        }
        blocks.add(Block.text(kind, depth, marker, inline(text.toString())));
        return i;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    private static int depth(String indent) {
        return Math.min(4, indent.length() / 2);
    }

    private static String tableRow(String line) {
        String s = line.strip();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        var cells = new ArrayList<String>();
        for (String cell : s.split("(?<!\\\\)\\|", -1)) cells.add(cell.strip());
        return String.join("  │  ", cells);
    }

    private static void flushParagraph(List<Block> blocks, List<String> paragraph) {
        if (paragraph.isEmpty()) return;
        String text = joinParagraph(paragraph);
        paragraph.clear();
        if (!text.isBlank()) blocks.add(Block.text(Kind.PARAGRAPH, 0, "", inline(text)));
    }

    /** Joins paragraph lines with spaces; two trailing spaces or a trailing backslash keeps a hard line break. */
    private static String joinParagraph(List<String> lines) {
        var out = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            String line = lines.get(k);
            boolean last = k == lines.size() - 1;
            boolean hard = line.endsWith("  ") || (line.stripTrailing().endsWith("\\") && !line.stripTrailing().endsWith("\\\\"));
            String body = line.strip();
            if (hard && body.endsWith("\\")) body = body.substring(0, body.length() - 1).stripTrailing();
            out.append(body);
            if (!last) out.append(hard ? "\n" : " ");
        }
        return out.toString().strip();
    }

    // ------------------------------------------------------------------ inline

    static List<Span> inline(String text) {
        var out = new ArrayList<Span>();
        parseInline(text == null ? "" : text, 0, out, 0);
        return merge(out);
    }

    private static final String ESCAPABLE = "\\`*_{}[]()#+-.!|~<>";

    private static void parseInline(String s, int flags, List<Span> out, int depth) {
        if (depth >= 24) {
            out.add(new Span(s, flags, null));
            return;
        }
        var buf = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && ESCAPABLE.indexOf(s.charAt(i + 1)) >= 0) {
                buf.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int run = runLength(s, i, '`');
                int close = s.indexOf("`".repeat(run), i + run);
                while (close >= 0 && runLength(s, close, '`') != run) close = s.indexOf("`".repeat(run), close + runLength(s, close, '`'));
                if (close > 0) {
                    flush(buf, flags, out);
                    String code = s.substring(i + run, close);
                    if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank()) {
                        code = code.substring(1, code.length() - 1);
                    }
                    out.add(new Span(code, flags | CODE, null));
                    i = close + run;
                    continue;
                }
                buf.append("`".repeat(run));
                i += run;
                continue;
            }
            if (c == '[') {
                int closeText = matching(s, i, '[', ']');
                if (closeText > 0 && closeText + 1 < n && s.charAt(closeText + 1) == '(') {
                    int closeUrl = matching(s, closeText + 1, '(', ')');
                    if (closeUrl > 0) {
                        String label = s.substring(i + 1, closeText);
                        String target = s.substring(closeText + 2, closeUrl).strip();
                        int space = target.indexOf(' ');
                        if (space > 0) target = target.substring(0, space); // drop a "title"
                        if (target.startsWith("<") && target.endsWith(">")) target = target.substring(1, target.length() - 1);
                        String url = safeUrl(target);
                        flush(buf, flags, out);
                        if (url != null) {
                            var inner = new ArrayList<Span>();
                            parseInline(label.isEmpty() ? url : label, flags, inner, depth + 1);
                            for (var span : inner) out.add(new Span(span.text(), span.flags(), url));
                        } else {
                            // Not a web link (relative path, file:, mailto:…): keep the label only.
                            parseInline(label, flags, out, depth + 1);
                        }
                        i = closeUrl + 1;
                        continue;
                    }
                }
            }
            if (c == '<') {
                int close = s.indexOf('>', i + 1);
                if (close > 0) {
                    String target = s.substring(i + 1, close);
                    String url = target.contains(" ") ? null : safeUrl(target);
                    if (url != null) {
                        flush(buf, flags, out);
                        out.add(new Span(target, flags, url));
                        i = close + 1;
                        continue;
                    }
                }
            }
            if ((c == 'h' || c == 'H') && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))
                    && s.regionMatches(true, i, "http", 0, 4)) {
                int end = autolinkEnd(s, i);
                if (end > i) {
                    String candidate = s.substring(i, end);
                    String url = safeUrl(candidate);
                    if (url != null) {
                        flush(buf, flags, out);
                        out.add(new Span(candidate, flags, url));
                        i = end;
                        continue;
                    }
                }
            }
            if (c == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                int close = s.indexOf("~~", i + 2);
                if (close > i + 2) {
                    flush(buf, flags, out);
                    parseInline(s.substring(i + 2, close), flags | STRIKE, out, depth + 1);
                    i = close + 2;
                    continue;
                }
            }
            if (c == '*' || c == '_') {
                int run = Math.min(3, runLength(s, i, c));
                boolean leftOk = i + run < n && !Character.isWhitespace(s.charAt(i + run));
                boolean wordBefore = i > 0 && Character.isLetterOrDigit(s.charAt(i - 1));
                if (leftOk && !(c == '_' && wordBefore)) {
                    int close = closingDelimiter(s, i + run, c, run);
                    if (close > 0) {
                        flush(buf, flags, out);
                        int add = run == 3 ? BOLD | ITALIC : run == 2 ? BOLD : ITALIC;
                        parseInline(s.substring(i + run, close), flags | add, out, depth + 1);
                        i = close + run;
                        continue;
                    }
                }
                buf.append(String.valueOf(c).repeat(runLength(s, i, c)));
                i += runLength(s, i, c);
                continue;
            }
            buf.append(c);
            i++;
        }
        flush(buf, flags, out);
    }

    private static int runLength(String s, int i, char c) {
        int n = 0;
        while (i + n < s.length() && s.charAt(i + n) == c) n++;
        return n;
    }

    /** Index of a closing run of exactly {@code run} {@code c}s that follows non-space text, or -1. */
    private static int closingDelimiter(String s, int from, char c, int run) {
        int i = from;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '`') {
                int r = runLength(s, i, '`');
                int close = s.indexOf("`".repeat(r), i + r);
                i = close > 0 ? close + r : i + r;
                continue;
            }
            if (ch == c) {
                int r = runLength(s, i, c);
                boolean afterText = i > from && !Character.isWhitespace(s.charAt(i - 1));
                boolean wordAfter = i + r < s.length() && Character.isLetterOrDigit(s.charAt(i + r));
                if (r >= run && afterText && !(c == '_' && wordAfter)) {
                    return r == run ? i : i + (r - run);
                }
                i += r;
                continue;
            }
            i++;
        }
        return -1;
    }

    /** Index of the bracket matching the one at {@code open}, honoring nesting and escapes, or -1. */
    private static int matching(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i++;
                continue;
            }
            if (ch == o) depth++;
            else if (ch == c && --depth == 0) return i;
        }
        return -1;
    }

    /** End of a bare URL starting at {@code start}: stops at whitespace/brackets and drops trailing punctuation. */
    static int autolinkEnd(String s, int start) {
        int end = start;
        while (end < s.length()) {
            char ch = s.charAt(end);
            if (Character.isWhitespace(ch) || ch == '<' || ch == '>' || ch == '"' || ch == '`') break;
            end++;
        }
        while (end > start) {
            char last = s.charAt(end - 1);
            if (".,;:!?'*_~".indexOf(last) >= 0) {
                end--;
            } else if (last == ')' && count(s, start, end, '(') < count(s, start, end, ')')) {
                end--;
            } else if (last == ']' && count(s, start, end, '[') < count(s, start, end, ']')) {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    private static int count(String s, int from, int to, char c) {
        int n = 0;
        for (int i = from; i < to; i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /** Returns the URL when it is an absolute http(s) URL with a host; null otherwise. */
    static @Nullable String safeUrl(String candidate) {
        if (candidate == null) return null;
        String url = candidate.strip();
        if (url.isEmpty() || url.length() > 2048) return null;
        for (int i = 0; i < url.length(); i++) if (Character.isISOControl(url.charAt(i)) || url.charAt(i) == ' ') return null;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    private static void flush(StringBuilder buf, int flags, List<Span> out) {
        if (buf.isEmpty()) return;
        out.add(new Span(buf.toString(), flags, null));
        buf.setLength(0);
    }

    private static List<Span> merge(List<Span> spans) {
        var out = new ArrayList<Span>();
        for (var span : spans) {
            if (span.text().isEmpty()) continue;
            if (!out.isEmpty()) {
                var last = out.getLast();
                if (last.flags() == span.flags() && java.util.Objects.equals(last.url(), span.url())) {
                    out.set(out.size() - 1, new Span(last.text() + span.text(), span.flags(), span.url()));
                    continue;
                }
            }
            out.add(span);
        }
        return out;
    }
}
