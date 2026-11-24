package dev.craftcode.ui;

import dev.craftcode.L10n;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.craftcode.ui.model.ChatImage;
import dev.craftcode.ui.model.ChatMessage;
import dev.craftcode.ui.model.MessageRole;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.TreeMap;

/**
 * Conversation transcript laid out like the T3 Code desktop app: the user's message as a right-aligned bubble, the
 * agent's intermediate work folded behind a "Worked for …" disclosure (click to expand), and the final reply always
 * shown as formatted Markdown. Each turn's reply sits on one continuous translucent sheet so text stays readable at
 * any transparency without per-line bands.
 * <p>
 * Left-click a link to open it (after Minecraft's usual confirmation), a disclosure to expand it, or an image to view
 * it. Right-click a message to copy its text, or a link to copy its URL.
 */
final class TranscriptView extends ScrollableWidget {
    private static final int TURN_GAP = 12;
    private static final int MESSAGE_GAP = 7;
    private static final int BLOCK_GAP = 6;
    private static final int ITEM_GAP = 2;
    private static final int DISCLOSURE = 17;
    private static final int WORK_INDENT = 12;
    private static final int TOOL_MAX_LINES = 6;
    private static final int NOTE_MAX_LINES = 14;
    private static final int SHEET_PAD = 7;

    private enum Variant { FINAL, WORK, TOOL, NOTE, USER }

    private enum OpKind { TEXT, FILL, SHEET, BUBBLE, CODE_BLOCK, IMAGE, ATTACHMENT, DISCLOSURE, GROUP, CARET }

    /** One draw operation; coordinates are relative to the widget's left edge and the content top. */
    private record Op(OpKind kind, int x, int y, int w, int h, @Nullable FormattedCharSequence line, int color,
                      @Nullable Object ref) {
        Op moved(int dx, int dy) {
            return new Op(this.kind, this.x + dx, this.y + dy, this.w, this.h, this.line, this.color, this.ref);
        }
    }

    private record GroupKey(String turn, String first) {}
    private record GroupRow(GroupKey key, WorkGroup group, String label) {}

    private enum HitKind { TOGGLE, GROUP, IMAGE, COPY }

    private record Hit(HitKind kind, int x, int y, int w, int h, Object ref, String copyKey) {
        boolean contains(double px, double py) {
            return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
        }
    }

    /** Laid-out message, relative to its own top-left. Reused while the message, width and variant are unchanged. */
    private record Laid(ChatMessage message, int width, Variant variant, List<Op> ops, int contentWidth, int height) {}

    @FunctionalInterface
    interface WorkLoader {
        CompletableFuture<List<ChatMessage>> load(String threadId, TranscriptTurns.Turn turn, int after);
    }

    private static final class LiveWork {
        final String key;
        final TreeMap<Integer, ChatMessage> rows = new TreeMap<>();
        long nextPoll;
        boolean failed;

        LiveWork(String key) { this.key = key; }
    }

    /**
     * Expansion state owned by the screen so it survives widget rebuilds (a question card appearing, a theme switch).
     * It belongs to one thread: switching threads clears it.
     */
    static final class State {
        private @Nullable String threadId;
        private final Set<String> expanded = new HashSet<>();

        private final Map<String, CompletableFuture<List<ChatMessage>>> activity = new HashMap<>();
        private final Set<GroupKey> openGroups = new HashSet<>();
        private long revision;
        private LiveWork liveWork;

        void reset() {
            this.threadId = null;
            this.activity.clear();
            this.expanded.clear();
            this.openGroups.clear();
            this.liveWork = null;
        }
    }

    private final WorkLoader loadWork;
    private long laidRevision;
    private final ChatImagePreviews previews;
    private final State state;
    private final Map<String, Laid> layoutCache = new HashMap<>();
    private final List<Op> ops = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    /** Turns whose folded work is expanded, by turn key (shared with {@link #state}). */
    private final Set<String> expanded;
    private List<ChatMessage> messages = List.of();
    private List<TranscriptTurns.Turn> turns = List.of();
    private boolean running;
    private String activePreview = "";
    private List<String> emptyLines = List.of();
    private int contentHeight;
    private int laidWidth = -1;
    private @Nullable String copiedKey;
    private int copiedX, copiedY;
    private long copiedAt;

    TranscriptView(Position position, int width, int height, ChatImagePreviews previews, State state,
                   WorkLoader loadWork) {
        super(position, width, height);
        this.previews = Objects.requireNonNull(previews, "previews");
        this.state = Objects.requireNonNull(state, "state");
        this.expanded = state.expanded;
        this.loadWork = loadWork;
    }

    /** Shows a placeholder instead of messages. */
    void setEmpty(String... lines) {
        this.messages = List.of();
        this.turns = List.of();
        this.ops.clear();
        this.hits.clear();
        this.state.reset();
        this.emptyLines = List.of(lines);
        this.contentHeight = 0;
        this.scroll = 0;
        this.previews.retain(List.of());
    }

    /**
     * Shows {@code messages} of thread {@code threadId}, keeping the view pinned to the bottom when it already was.
     * Switching threads forgets which turns were expanded; expansions of turns that no longer exist are dropped too.
     */
    void setConversation(String threadId, List<ChatMessage> messages, boolean running, boolean forceBottom) {
        boolean stick = forceBottom || this.atBottom();
        boolean sameThread = Objects.equals(threadId, this.state.threadId);
        if (!sameThread) {
            this.state.reset();
            this.layoutCache.clear();
            this.copiedKey = null;
            this.state.threadId = threadId;
        }
        this.emptyLines = messages.isEmpty() ? List.of(L10n.tr("chat.transcript.empty", "No messages yet."),
                L10n.tr("chat.transcript.empty_hint", "Send a message below to get started.")) : List.of();
        if (!sameThread || !messages.equals(this.messages) || running != this.running || this.laidWidth != this.width
                || (this.ops.isEmpty() && !messages.isEmpty())) {
            this.messages = List.copyOf(messages);
            this.running = running;
            this.turns = TranscriptTurns.group(this.messages, running);
            var keys = new HashSet<String>();
            for (var turn : this.turns) {
                keys.add(turn.key());
                if (turn.active()) this.state.activity.remove(turn.key());
            }
            var active = this.state.liveWork;
            if (active != null && this.turns.stream().noneMatch(t -> t.active() && t.key().equals(active.key))) this.state.liveWork = null;
            this.expanded.retainAll(keys);
            this.state.openGroups.removeIf(key -> !keys.contains(key.turn()));
            this.state.activity.keySet().retainAll(keys);
            this.relayout();
            var images = new ArrayList<ChatImage>();
            for (var message : this.messages) images.addAll(message.images());
            this.previews.retain(images);
        }
        if (stick) this.scrollToBottom();
    }

    // ------------------------------------------------------------------ layout

    private boolean narrow() {
        return this.width < 260;
    }

    private int pad() {
        return textInset(this.width);
    }

    private static int textInset(int width) {
        return width < 260 ? 6 : 12;
    }

    static int panelLeft(int width) {
        return Math.max(0, textInset(width) - SHEET_PAD);
    }

    static int panelWidth(int width) {
        return width - 2 * panelLeft(width) - 6;
    }

    /** Left edge of the content column, relative to the widget. */
    private int left() {
        return this.pad();
    }

    /** Width of the content column (leaves room for the scrollbar). */
    private int columnWidth() {
        return Math.max(60, this.width - this.pad() * 2 - 6);
    }

    private int thumbSize() {
        return this.narrow() ? 32 : 48;
    }

    private LiveWork activeWork(TranscriptTurns.Turn turn, int after) {
        if (this.state.liveWork == null || !this.state.liveWork.key.equals(turn.key())) this.state.liveWork = new LiveWork(turn.key());
        var live = this.state.liveWork;
        if (Util.getMillis() >= live.nextPoll) {
            // Re-read ongoing items and the newest item while streaming, plus everything added since then.
            if (!live.rows.isEmpty()) {
                int from = live.rows.values().stream().filter(ChatMessage::streaming).mapToInt(ChatMessage::position)
                        .min().orElse(live.rows.lastKey());
                after = Math.max(after, from - 1);
            }
            live.nextPoll = Long.MAX_VALUE;
            this.loadWork.load(this.state.threadId, turn, after).whenCompleteAsync((rows, error) -> {
                if (this.state.liveWork != live) return;
                live.failed = error != null;
                if (!live.failed) for (var row : rows) live.rows.put(row.position(), row);
                live.nextPoll = Util.getMillis() + (live.failed ? 15000 : 5000);
                this.state.revision++;
            }, this.client);
        }
        return live;
    }

    private void relayout() {
        this.ops.clear();
        this.hits.clear();
        this.activePreview = "";
        this.laidWidth = this.width;
        this.laidRevision = this.state.revision;
        var live = new HashMap<String, Laid>();
        int left = this.left();
        int column = this.columnWidth();
        int y = this.pad();

        for (var turn : this.turns) {
            boolean open = this.expanded.contains(turn.key());
            String workStatus = "";
            int after = turn.user() == null ? -1 : turn.user().position();
            if (open && turn.canLoadWork() && turn.active()) {
                var current = this.activeWork(turn, after);
                turn = turn.withActivity(List.copyOf(current.rows.values()));
                if (current.failed) workStatus = L10n.tr("chat.work.live_retry", "Activity unavailable. Reconnecting…");
            } else if (open && turn.canLoadWork()) {
                var result = this.state.activity.get(turn.key());
                if (result == null) {
                    result = this.loadWork.load(this.state.threadId, turn, after);
                    this.state.activity.put(turn.key(), result);
                    result.whenCompleteAsync((unused, error) -> this.state.revision++, this.client);
                }
                if (!result.isDone()) workStatus = L10n.tr("chat.work.loading", "Loading activity…");
                else if (result.isCompletedExceptionally())
                    workStatus = L10n.tr("chat.work.load_failed", "Couldn’t load activity. Close and reopen to retry.");
                else {
                    turn = turn.withActivity(result.join());
                    if (turn.work().isEmpty()) workStatus = L10n.tr("chat.work.empty", "No activity recorded.");
                }
            }
            if (turn.user() != null) y = this.placeUser(turn.user(), left, column, y, live) + MESSAGE_GAP;

            int groupTop = y;
            int groupBottom = y;
            int sheetIndex = this.ops.size();
            boolean hasGroup = false;
            boolean showDisclosure = turn.hasWork() || turn.active();
            if (showDisclosure) {
                hasGroup = true;
                int height = DISCLOSURE;
                if (turn.active() && !open && turn.latestWork() != null) {
                    var groups = WorkGroup.group(turn.work());
                    var latest = groups.getLast();
                    this.activePreview = WorkGroup.folds(latest.items().getFirst()) ? latest.label() : firstLine(turn.latestWork().text());
                    if (!this.activePreview.isEmpty()) height += this.lineHeight();
                }
                this.ops.add(new Op(OpKind.DISCLOSURE, left, y, column, height, null, 0, turn));
                if (turn.hasWork()) this.hits.add(new Hit(HitKind.TOGGLE, left, y, column, height, turn, "work:" + turn.key()));
                y += height;
                groupBottom = y;
                if (open) {
                    y += 3;
                    int top = y;
                    int workX = left + WORK_INDENT;
                    int workW = column - WORK_INDENT;
                    for (var group : WorkGroup.group(turn.work())) {
                        var first = group.items().getFirst();
                        boolean folded = WorkGroup.folds(first);
                        if (folded) {
                            var key = new GroupKey(turn.key(), first.id());
                            var row = new GroupRow(key, group, group.label());
                            this.ops.add(new Op(OpKind.GROUP, workX, y, workW, this.lineHeight(), null, 0, row));
                            this.hits.add(new Hit(HitKind.GROUP, workX, y, workW, this.lineHeight(), row, first.id()));
                            y += this.lineHeight() + 4;
                            groupBottom = y - 4;
                            if (!this.state.openGroups.contains(key)) continue;
                        }
                        int indent = folded ? 9 : 0;
                        for (var message : group.items()) {
                            var variant = message.role() == MessageRole.ASSISTANT ? Variant.WORK
                                    : message.role() == MessageRole.SYSTEM ? Variant.NOTE : Variant.TOOL;
                            y = this.placeMessage(message, variant, workX + indent, workW - indent, y, live);
                            groupBottom = y;
                            y += 4;
                        }
                    }
                    if (!workStatus.isEmpty()) {
                        for (var line : this.client.font.split(Component.literal(workStatus), column - WORK_INDENT)) {
                            this.ops.add(new Op(OpKind.TEXT, left + WORK_INDENT, y, column - WORK_INDENT,
                                    this.client.font.lineHeight, line, Theme.FAINT, null));
                            y += this.lineHeight();
                        }
                        groupBottom = y;
                    }
                    // Guide rail down the left of the expanded work log.
                    this.ops.add(new Op(OpKind.FILL, left + 4, top, 2, Math.max(1, y - 4 - top), null, Theme.RULE, null));
                }
                y += 4;
            }
            for (var message : turn.shown()) {
                hasGroup = true;
                var variant = message.role() == MessageRole.ASSISTANT ? Variant.FINAL
                        : message.role() == MessageRole.TOOL ? Variant.TOOL : Variant.NOTE;
                y = this.placeMessage(message, variant, left, column, y, live);
                groupBottom = y;
                y += MESSAGE_GAP;
            }
            if (hasGroup) {
                int bottom = groupBottom + SHEET_PAD - 1;
                // One continuous rounded sheet per turn's reply group, inserted under its content.
                this.ops.add(sheetIndex, new Op(OpKind.SHEET, panelLeft(this.width), groupTop - SHEET_PAD + 1,
                        panelWidth(this.width), bottom - (groupTop - SHEET_PAD + 1), null, Theme.SHEET, null));
                y = bottom;
            } else {
                y = groupTop - MESSAGE_GAP;
            }
            y += TURN_GAP;
        }
        this.layoutCache.clear();
        this.layoutCache.putAll(live);
        this.contentHeight = y - TURN_GAP + this.pad();
    }

    private int lineHeight() {
        return this.client.font.lineHeight + 2;
    }

    /** Places a user bubble, right-aligned, and returns the y below it. */
    private int placeUser(ChatMessage message, int left, int column, int y, Map<String, Laid> live) {
        int maxBubble = this.narrow() ? column - 12 : Math.min(column - 24, Math.max(160, column * 4 / 5));
        var laid = this.laid(message, Variant.USER, maxBubble - 20, live);
        int w = Math.min(maxBubble, Math.max(laid.contentWidth() + 20, 28));
        int h = laid.height() + 14;
        int x = left + column - w;
        this.ops.add(new Op(OpKind.BUBBLE, x, y, w, h, null, Theme.USER_BG, null));
        // Text is left-aligned inside the bubble, images too.
        for (var op : laid.ops()) this.ops.add(op.moved(x + 10, y + 7));
        this.addHits(laid, x + 10, y + 7);
        this.hits.add(new Hit(HitKind.COPY, x, y, w, h, message.text(), message.id()));
        return y + h;
    }

    private int placeMessage(ChatMessage message, Variant variant, int x, int width, int y, Map<String, Laid> live) {
        var laid = this.laid(message, variant, width, live);
        for (var op : laid.ops()) this.ops.add(op.moved(x, y));
        this.addHits(laid, x, y);
        this.hits.add(new Hit(HitKind.COPY, x, y, width, Math.max(1, laid.height()), message.text(), message.id()));
        return y + laid.height();
    }

    private void addHits(Laid laid, int dx, int dy) {
        for (var op : laid.ops()) {
            if (op.kind() == OpKind.IMAGE && op.ref() instanceof ChatImage image) {
                this.hits.add(new Hit(HitKind.IMAGE, op.x() + dx, op.y() + dy, op.w(), op.h(), image, ""));
            }
        }
    }

    private Laid laid(ChatMessage message, Variant variant, int width, Map<String, Laid> live) {
        String key = message.id() + "|" + variant;
        var cached = this.layoutCache.get(key);
        if (cached == null) cached = live.get(key);
        if (cached != null && cached.width() == width && cached.message().equals(message)) {
            live.put(key, cached);
            return cached;
        }
        var laid = this.layoutMessage(message, variant, width);
        live.put(key, laid);
        return laid;
    }

    private Laid layoutMessage(ChatMessage message, Variant variant, int width) {
        var font = this.client.font;
        var out = new ArrayList<Op>();
        int y = 0;
        int maxW = 0;

        var images = message.images();
        if (!images.isEmpty()) {
            int t = this.thumbSize();
            int x = 0;
            int rowHeight = 0;
            for (var image : images) {
                boolean unavailable = image.source() instanceof ChatImage.Unavailable;
                int w = unavailable ? Math.min(width, 23 + font.width(image.name())) : Math.min(width, t);
                int h = unavailable ? font.lineHeight + 8 : w;
                if (x > 0 && x + w > width) {
                    x = 0;
                    y += rowHeight + 3;
                    rowHeight = 0;
                }
                out.add(new Op(unavailable ? OpKind.ATTACHMENT : OpKind.IMAGE, x, y, w, h, null, 0, image));
                maxW = Math.max(maxW, x + w);
                rowHeight = Math.max(rowHeight, h);
                x += w + 3;
            }
            y += rowHeight;
            if (!message.text().isBlank()) y += 5;
        }

        String text = message.text();
        int lh = this.lineHeight();
        switch (variant) {
            case USER -> {
                if (!text.isBlank() || images.isEmpty()) {
                    var lines = font.split(MessageFormatter.colored(text.isBlank() ? " " : text.strip(), Theme.USER_TEXT), width);
                    for (var line : lines) {
                        out.add(new Op(OpKind.TEXT, 0, y, font.width(line), font.lineHeight, line, Theme.USER_TEXT, null));
                        maxW = Math.max(maxW, font.width(line));
                        y += lh;
                    }
                    y -= 2;
                }
            }
            case TOOL, NOTE -> {
                int color = variant == Variant.TOOL ? Theme.MUTED : Theme.FAINT;
                var styled = MessageFormatter.colored(text.strip(), color);
                if (variant == Variant.NOTE) styled.withStyle(s -> s.withItalic(true));
                var lines = new ArrayList<>(font.split(styled, width));
                int cap = variant == Variant.TOOL ? TOOL_MAX_LINES : NOTE_MAX_LINES;
                int hidden = lines.size() - cap;
                if (hidden > 0) lines.subList(cap, lines.size()).clear();
                for (var line : lines) {
                    out.add(new Op(OpKind.TEXT, 0, y, font.width(line), font.lineHeight, line, color, null));
                    y += lh;
                }
                if (hidden > 0) {
                    var more = MessageFormatter.colored(hidden == 1
                            ? L10n.tr("chat.transcript.more_line", "… %s more line · right-click to copy", hidden)
                            : L10n.tr("chat.transcript.more_lines", "… %s more lines · right-click to copy", hidden), Theme.FAINT).getVisualOrderText();
                    out.add(new Op(OpKind.TEXT, 0, y, font.width(more), font.lineHeight, more, Theme.FAINT, null));
                    y += lh;
                }
                y -= 2;
                maxW = width;
            }
            case FINAL, WORK -> {
                int base = variant == Variant.FINAL ? Theme.TEXT : Theme.TEXT_SOFT;
                String body = text.isBlank() && message.streaming() ? "" : text;
                y = this.layoutMarkdown(out, Markdown.parse(body), base, width, y);
                maxW = width;
                if (message.streaming() && variant == Variant.FINAL) {
                    // Blinking caret after the last line.
                    Op lastText = null;
                    for (var op : out) if (op.kind() == OpKind.TEXT) lastText = op;
                    int cx = lastText == null ? 0 : Math.min(width - 4, lastText.x() + lastText.w() + 2);
                    int cy = lastText == null ? y : lastText.y();
                    out.add(new Op(OpKind.CARET, cx, cy, 4, font.lineHeight, null, Theme.ACCENT, null));
                    if (lastText == null) y += font.lineHeight;
                }
            }
        }
        return new Laid(message, width, variant, List.copyOf(out), Math.min(maxW, width), Math.max(0, y));
    }

    /** Lays out Markdown blocks starting at {@code y}; returns the y below the last line. */
    private int layoutMarkdown(List<Op> out, List<Markdown.Block> blocks, int base, int width, int y) {
        var font = this.client.font;
        int lh = this.lineHeight();
        Markdown.Kind previous = null;

        for (var block : blocks) {
            if (previous != null) {
                boolean listRun = isList(previous) && isList(block.kind());
                boolean tableRun = previous == Markdown.Kind.TABLE_ROW && block.kind() == Markdown.Kind.TABLE_ROW;
                y += listRun || tableRun ? ITEM_GAP : block.kind() == Markdown.Kind.HEADING ? BLOCK_GAP + 3 : BLOCK_GAP;
            }
            previous = block.kind();
            switch (block.kind()) {
                case PARAGRAPH, TABLE_ROW -> {
                    for (var line : font.split(MessageFormatter.inline(block.spans(), base, false), width)) {
                        out.add(new Op(OpKind.TEXT, 0, y, font.width(line), font.lineHeight, line, base, null));
                        y += lh;
                    }
                    y -= 2;
                }
                case HEADING -> {
                    int color = block.level() <= 2 ? Theme.TEXT : Theme.TEXT_SOFT;
                    for (var line : font.split(MessageFormatter.inline(block.spans(), color, true), width)) {
                        out.add(new Op(OpKind.TEXT, 0, y, font.width(line), font.lineHeight, line, color, null));
                        y += lh;
                    }
                    if (block.level() == 1 && width > 40) {
                        out.add(new Op(OpKind.FILL, 0, y - 1, width, 1, null, Theme.RULE, null));
                        y += 2;
                    }
                    y -= 2;
                }
                case BULLET, ORDERED -> {
                    int indent = Math.min(block.level() * 10, width / 3);
                    String marker = block.marker();
                    int markerW = Math.max(font.width(marker), block.kind() == Markdown.Kind.ORDERED ? font.width("0.") : 0) + 4;
                    int textW = Math.max(20, width - indent - markerW);
                    var split = font.split(MessageFormatter.inline(block.spans(), base, false), textW);
                    var markerSeq = MessageFormatter.colored(marker, Theme.MUTED).getVisualOrderText();
                    out.add(new Op(OpKind.TEXT, indent, y, font.width(markerSeq), font.lineHeight, markerSeq, Theme.MUTED, null));
                    if (split.isEmpty()) y += lh;
                    for (var line : split) {
                        out.add(new Op(OpKind.TEXT, indent + markerW, y, font.width(line), font.lineHeight, line, base, null));
                        y += lh;
                    }
                    y -= 2;
                }
                case QUOTE -> {
                    int top = y;
                    for (var line : font.split(MessageFormatter.inline(block.spans(), Theme.MUTED, false), width - 10)) {
                        out.add(new Op(OpKind.TEXT, 10, y, font.width(line), font.lineHeight, line, Theme.MUTED, null));
                        y += lh;
                    }
                    y -= 2;
                    out.add(new Op(OpKind.FILL, 1, top - 1, 2, Math.max(1, y - top + 2), null,
                            Theme.mix(Theme.RULE, Theme.ACCENT, 0.35f), null));
                }
                case CODE -> {
                    int top = y;
                    int fillIndex = out.size();
                    boolean hasLang = !block.lang().isEmpty() && width > 80;
                    int headerH = 0;
                    if (hasLang) {
                        headerH = font.lineHeight + 5;
                        var lang = MessageFormatter.colored(block.lang(), Theme.MUTED).getVisualOrderText();
                        out.add(new Op(OpKind.TEXT, 7, y + 3, font.width(lang), font.lineHeight, lang, Theme.MUTED, null));
                        y += headerH + 4;
                    } else {
                        y += 5;
                    }
                    int inner = Math.max(20, width - 14);
                    var code = block.code().isEmpty() ? List.of("") : block.code();
                    for (String raw : code) {
                        var split = font.split(MessageFormatter.colored(raw.isEmpty() ? " " : raw, Theme.CODE), inner);
                        for (var line : split) {
                            out.add(new Op(OpKind.TEXT, 7, y, font.width(line), font.lineHeight, line, Theme.CODE, null));
                            y += lh;
                        }
                    }
                    y += 3;
                    out.add(fillIndex, new Op(OpKind.CODE_BLOCK, 0, top, width, y - top, null, Theme.CODE_BG,
                            headerH > 0 ? headerH : null));
                }
                case RULE -> {
                    out.add(new Op(OpKind.FILL, 0, y + 3, width, 1, null, Theme.RULE, null));
                    y += 7;
                }
            }
        }
        return y;
    }

    private static boolean isList(Markdown.Kind kind) {
        return kind == Markdown.Kind.BULLET || kind == Markdown.Kind.ORDERED;
    }

    @Override
    protected int contentHeight() {
        var active = this.state.liveWork;
        boolean poll = active != null && this.expanded.contains(active.key) && Util.getMillis() >= active.nextPoll;
        if ((poll || this.laidWidth != this.width || this.laidRevision != this.state.revision) && !this.messages.isEmpty()) this.relayout();
        return this.contentHeight;
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean onContentClick(double mouseX, double contentY, int button) {
        double x = mouseX - this.getX();
        boolean left = button == InputConstants.MOUSE_BUTTON_LEFT;
        boolean right = button == InputConstants.MOUSE_BUTTON_RIGHT;
        if (!left && !right) return false;

        var link = this.linkAt(x, contentY);
        if (link != null) {
            if (left) {
                var screen = this.client.gui.screen();
                if (screen != null) ConfirmLinkScreen.confirmLinkNow(screen, link);
            } else {
                this.copy(link.toString(), "link:" + link, (int) x, (int) contentY);
            }
            this.playDownSound();
            return true;
        }
        for (var hit : this.hits) {
            if (!hit.contains(x, contentY)) continue;
            if (left && hit.kind() == HitKind.IMAGE && hit.ref() instanceof ChatImage image) {
                var screen = this.client.gui.screen();
                if (screen != null) ChatImageViewer.open(screen, image);
                this.playDownSound();
                return true;
            }
            if (hit.kind() == HitKind.GROUP && hit.ref() instanceof GroupRow row) {
                if (right) this.copy(row.group().details(), hit.copyKey(), hit.x() + hit.w(), hit.y());
                else {
                    if (!this.state.openGroups.remove(row.key())) this.state.openGroups.add(row.key());
                    this.relayout();
                    this.setScroll(this.scroll);
                }
                this.playDownSound();
                return true;
            }
            if (left && hit.kind() == HitKind.TOGGLE && hit.ref() instanceof TranscriptTurns.Turn turn) {
                if (!this.expanded.remove(turn.key())) {
                    var previous = this.state.activity.get(turn.key());
                    if (previous != null && previous.isCompletedExceptionally()) this.state.activity.remove(turn.key());
                    this.expanded.add(turn.key());
                }
                this.relayout();
                this.setScroll(this.scroll);
                this.playDownSound();
                return true;
            }
            if (right && (hit.kind() == HitKind.COPY || hit.kind() == HitKind.TOGGLE)) {
                String text = hit.ref() instanceof TranscriptTurns.Turn turn ? turn.workText() : String.valueOf(hit.ref());
                this.copy(text, hit.copyKey(), hit.x() + hit.w(), hit.y());
                this.playDownSound();
                return true;
            }
        }
        return false;
    }

    private void copy(String text, String key, int x, int y) {
        this.client.keyboardHandler.setClipboard(text);
        this.copiedKey = key;
        this.copiedX = x;
        this.copiedY = y;
        this.copiedAt = Util.getMillis();
    }

    /** The http(s) link under a point (widget-relative x, content y), if any. */
    private @Nullable URI linkAt(double x, double contentY) {
        var font = this.client.font;
        for (var op : this.ops) {
            if (op.kind() != OpKind.TEXT || op.line() == null) continue;
            if (contentY < op.y() - 1 || contentY >= op.y() + op.h() + 1 || x < op.x() || x >= op.x() + op.w()) continue;
            var uri = MessageFormatter.link(styleAt(font, op.line(), (int) (x - op.x())));
            if (uri != null) return uri;
        }
        return null;
    }

    static @Nullable Style styleAt(Font font, FormattedCharSequence line, int x) {
        float[] advance = {0};
        Style[] found = {null};
        line.accept((index, style, codepoint) -> {
            float w = font.getSplitter().stringWidth(FormattedCharSequence.codepoint(codepoint, style));
            if (x >= advance[0] && x < advance[0] + w) {
                found[0] = style;
                return false;
            }
            advance[0] += w;
            return true;
        });
        return found[0];
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void drawContent(SpruceGuiGraphics graphics, int top, int mouseX, int mouseY) {
        var font = this.client.font;
        var active = this.state.liveWork;
        boolean poll = active != null && this.expanded.contains(active.key) && Util.getMillis() >= active.nextPoll;
        if ((poll || this.laidWidth != this.width || this.laidRevision != this.state.revision) && !this.messages.isEmpty()) this.relayout();

        if (this.turns.isEmpty()) {
            this.drawEmpty(graphics, font);
            return;
        }

        int originX = this.getX();
        boolean inside = this.isMouseHovered() && mouseX < this.getEndX() - 6;
        double hoverX = mouseX - originX;
        double hoverY = mouseY - top;
        long now = Util.getMillis();
        boolean caretOn = now / 500 % 2 == 0;

        for (var op : this.ops) {
            int x = originX + op.x();
            int y = top + op.y();
            if (y + op.h() < this.getY() || y > this.getEndY()) continue;
            switch (op.kind()) {
                case SHEET -> UiShapes.fillRounded(graphics, x, y, op.w(), op.h(), UiShapes.R_PANEL, Theme.SHEET);
                case BUBBLE -> UiShapes.panel(graphics, x, y, op.w(), op.h(), UiShapes.R_CONTROL, Theme.USER_BG, Theme.USER_BORDER, true);
                case CODE_BLOCK -> {
                    UiShapes.panel(graphics, x, y, op.w(), op.h(), UiShapes.R_CONTROL, Theme.CODE_BG, Theme.CODE_BORDER, false);
                    if (op.ref() instanceof Integer headerH && headerH > 0 && op.h() > headerH + 2) {
                        graphics.fill(x + 2, y + 1, x + op.w() - 2, y + headerH, Theme.CODE_HEADER);
                        graphics.fill(x + 1, y + headerH, x + op.w() - 1, y + headerH + 1, Theme.CODE_BORDER);
                    }
                }
                case FILL -> graphics.fill(x, y, x + op.w(), y + op.h(), op.color());
                case TEXT -> {
                    if (op.line() != null) graphics.text(font, op.line(), x, y, op.color(), false);
                }
                case CARET -> {
                    if (caretOn) graphics.fill(x, y, x + 2, y + op.h(), op.color());
                }
                case ATTACHMENT -> {
                    if (op.ref() instanceof ChatImage file) {
                        UiShapes.pill(graphics, x, y, op.w(), op.h(), Theme.CODE_BG, Theme.BORDER);
                        graphics.vanilla().blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                                ComposerAction.PAPERCLIP, x + 5, y + (op.h() - 8) / 2, 8, 8, Theme.TEXT_SOFT);
                        String name = Theme.ellipsize(font, file.name(), Math.max(1, op.w() - 20));
                        graphics.text(font, name, x + 16, y + 4, Theme.TEXT_SOFT, false);
                        if (inside && hoverX >= op.x() && hoverX < op.x() + op.w()
                                && hoverY >= op.y() && hoverY < op.y() + op.h()) {
                            var tip = file.name() + "\n" + L10n.tr("chat.attachment.unavailable", "Attachment · preview unavailable");
                            graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(tip), 220), mouseX, mouseY);
                        }
                    }
                }
                case IMAGE -> {
                    if (op.ref() instanceof ChatImage image) {
                        boolean hovered = inside && hoverX >= op.x() && hoverX < op.x() + op.w()
                                && hoverY >= op.y() && hoverY < op.y() + op.h();
                        UiShapes.fillRounded(graphics, x, y, op.w(), op.h(), UiShapes.R_CONTROL, Theme.CODE_BG);
                        String status = this.previews.draw(graphics, image, x + 1, y + 1, op.w() - 2, op.h() - 2);
                        UiShapes.outlineRounded(graphics, x, y, op.w(), op.h(), UiShapes.R_CONTROL,
                                hovered ? Theme.ACCENT : Theme.BORDER);
                        if (hovered) {
                            graphics.requestCursor(CursorTypes.POINTING_HAND);
                            String tip = image.name() + (status.isEmpty() ? "" : "\n" + status) + "\n" + L10n.tr("chat.image.click_to_view", "Click to view");
                            graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(tip), 220), mouseX, mouseY);
                        }
                    }
                }
                case GROUP -> {
                    if (op.ref() instanceof GroupRow row) {
                        boolean hovered = inside && hoverY >= op.y() && hoverY < op.y() + op.h()
                                && hoverX >= op.x() && hoverX < op.x() + op.w();
                        int color = hovered ? Theme.TEXT : Theme.MUTED;
                        if (this.state.openGroups.contains(row.key())) UiIcons.caretDown(graphics, x, y + 3, color);
                        else UiIcons.caretRight(graphics, x + 1, y + 2, color);
                        String shown = Theme.ellipsize(font, row.label(), Math.max(1, op.w() - 10));
                        graphics.text(font, shown, x + 9, y + 1, color, false);
                        if (hovered) {
                            graphics.requestCursor(CursorTypes.POINTING_HAND);
                            if (!shown.equals(row.label())) graphics.vanilla().setTooltipForNextFrame(font,
                                    font.split(Component.literal(row.label()), 220), mouseX, mouseY);
                        }
                    }
                }
                case DISCLOSURE -> {
                    if (op.ref() instanceof TranscriptTurns.Turn turn) {
                        boolean hovered = inside && turn.hasWork() && hoverY >= op.y() && hoverY < op.y() + op.h()
                                && hoverX >= op.x() && hoverX < op.x() + op.w();
                        this.drawDisclosure(graphics, font, turn, x, y, op.w(), hovered, now);
                    }
                }
            }
        }

        if (inside) {
            var link = this.linkAt(hoverX, hoverY);
            if (link != null) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
                graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(link.toString()), 240), mouseX, mouseY);
            }
        }

        if (this.copiedKey != null && now - this.copiedAt < 1500) {
            String copied = L10n.tr("chat.transcript.copied", "Copied");
            int w = 7 + 4 + font.width(copied) + 10;
            int h = font.lineHeight + 6;
            int x = Math.max(originX + 4, Math.min(originX + this.copiedX - w, this.getEndX() - 10 - w));
            int y = top + this.copiedY + 2;
            UiShapes.dropShadow(graphics, x, y, w, h, UiShapes.R_CONTROL);
            UiShapes.panel(graphics, x, y, w, h, UiShapes.R_CONTROL, Theme.PANEL, Theme.BORDER_STRONG, true);
            UiIcons.check(graphics, x + 5, y + (h - 6) / 2, Theme.GREEN);
            graphics.text(font, copied, x + 15, y + (h - font.lineHeight) / 2 + 1, Theme.GREEN, false);
        }
    }

    private void drawDisclosure(SpruceGuiGraphics graphics, Font font, TranscriptTurns.Turn turn, int x, int y, int width,
                                boolean hovered, long now) {
        boolean open = this.expanded.contains(turn.key());
        String label = TranscriptTurns.workLabel(turn, System.currentTimeMillis());
        int leadW = (turn.active() ? 9 : 0) + (turn.hasWork() ? 8 : 0);
        int textMax = Math.max(16, width - 12 - leadW);
        String shown = Theme.ellipsize(font, label, textMax);
        int pillW = Math.min(width, 6 + leadW + font.width(shown) + 6);
        int pillH = DISCLOSURE - 1;
        int fill = hovered ? Theme.BUTTON_HOVER : open ? Theme.SELECTED : Theme.BUTTON;
        int border = hovered ? Theme.BORDER_STRONG : open ? Theme.mix(Theme.BORDER, Theme.ACCENT, 0.45f) : Theme.BORDER_SUBTLE;
        UiShapes.pill(graphics, x, y, pillW, pillH, fill, border);

        int cx = x + 6;
        int textY = y + (pillH - font.lineHeight) / 2 + 1;
        if (turn.active()) {
            UiIcons.spinner(graphics, cx, y + (pillH - 5) / 2, Theme.ACCENT);
            cx += 9;
        }
        if (turn.hasWork()) {
            int chevColor = hovered || open ? Theme.TEXT : Theme.MUTED;
            if (open) UiIcons.caretDown(graphics, cx, y + (pillH - 3) / 2, chevColor);
            else UiIcons.caretRight(graphics, cx + 1, y + (pillH - 5) / 2, chevColor);
            cx += 8;
        }
        int color = hovered || open ? Theme.TEXT : Theme.TEXT_SOFT;
        graphics.text(font, shown, cx, textY, color, false);
        if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
        if (turn.active() && !open && !this.activePreview.isEmpty()) {
            graphics.text(font, Theme.ellipsize(font, this.activePreview, width - 12), x + 12, y + DISCLOSURE + 1, Theme.FAINT, false);
        }
    }

    /** First non-blank line of {@code text} with the most common Markdown markers removed, for one-line previews. */
    static String firstLine(String text) {
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.strip().startsWith("```")) continue;
            var blocks = Markdown.parse(line);
            return blocks.isEmpty() ? line.strip() : blocks.getFirst().plain().strip();
        }
        return "";
    }

    private void drawEmpty(SpruceGuiGraphics graphics, Font font) {
        if (this.emptyLines.isEmpty()) return;
        int lh = font.lineHeight + 4;
        int maxW = 0;
        for (var line : this.emptyLines) maxW = Math.max(maxW, font.width(line));
        maxW = Math.min(maxW, this.width - 24);
        int blockH = this.emptyLines.size() * lh - 4;
        int y = this.getY() + this.height / 2 - blockH / 2;
        int cx = this.getX() + this.width / 2;
        UiShapes.fillRounded(graphics, cx - maxW / 2 - 12, y - 9, maxW + 24, blockH + 18, UiShapes.R_PANEL, Theme.SHEET);
        boolean first = true;
        for (var line : this.emptyLines) {
            String shown = Theme.ellipsize(font, line, maxW);
            if (first) {
                var bold = Component.literal(shown).withStyle(s -> s.withBold(true));
                graphics.text(font, bold, cx - font.width(bold) / 2, y, Theme.TEXT_SOFT, false);
            } else {
                graphics.text(font, shown, cx - font.width(shown) / 2, y, Theme.MUTED, false);
            }
            y += lh;
            first = false;
        }
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        return Component.literal(L10n.tr("chat.transcript.narration", "Conversation"));
    }
}
