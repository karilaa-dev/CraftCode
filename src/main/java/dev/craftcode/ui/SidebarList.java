package dev.craftcode.ui;

import dev.craftcode.L10n;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.craftcode.ui.model.ConnectionsView;
import dev.craftcode.ui.model.ThreadAction;
import dev.craftcode.ui.model.ThreadOrganization;
import dev.craftcode.ui.model.ThreadStatus;
import dev.craftcode.ui.model.ThreadSummary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Thread list across all projects, grouped like the T3 Code app by {@link ThreadOrganization}: an "In progress"
 * section (only when it has threads), the ordinary "Threads" section, then independently collapsible "Snoozed" and
 * "Settled" sections with compact rows. Each section is ordered by last activity, newest first.
 */
final class SidebarList extends ScrollableWidget {
    interface Actions {
        void selectThread(WorkspaceProject project, String threadId);

        void organizeThread(WorkspaceProject project, String threadId, ThreadAction action);

        void toggleSnoozed();

        void toggleSettled();

        void showMoreSettled();
    }

    static final int SETTLED_PAGE = 25;
    private static final int COMPACT = 18;
    private static final int HEADER = 20;
    private static final int BUTTON_TOP = 2;
    private static final int BUTTON_SIZE = 15;
    private static final int BUTTON_STRIDE = BUTTON_SIZE + 2;
    private static final int TITLE_TOP = BUTTON_TOP + BUTTON_SIZE + 4;
    private static final int ROW = TITLE_TOP + 13;
    private static final int ICON_SIZE = 7;
    private static final Identifier SNOOZE_ICON = Identifier.fromNamespaceAndPath("craftcode", "thread/snooze");
    private static final Identifier SETTLE_ICON = Identifier.fromNamespaceAndPath("craftcode", "thread/settle");

    private enum Kind { SECTION, THREAD, SNOOZED_HEADER, SETTLED_HEADER, COMPACT_THREAD, MORE }

    private record Entry(WorkspaceProject project, ThreadSummary thread) {}

    private record Row(Kind kind, @Nullable Entry entry, String label, int count, int y, int height) {
        Row(Kind kind, @Nullable Entry entry, int y, int height) {
            this(kind, entry, "", 0, y, height);
        }

        List<ThreadAction> buttons() {
            return entry != null && entry.project().source().canOrganizeThreads()
                    ? ThreadAction.available(entry.thread().organization()) : List.of();
        }
    }

    private final Actions actions;
    private final List<Row> rows = new ArrayList<>();
    private ConnectionsView connections;
    private String emptyText = "";
    private @Nullable String selectedThreadId;
    private boolean snoozedExpanded;
    private int snoozedCount;
    private boolean settledExpanded;
    private int settledCount;
    private int hiddenSettled;

    SidebarList(Position position, int width, int height, Actions actions) {
        super(position, width, height);
        this.actions = actions;
    }

    /** Most recent first; ties broken by title so the order is stable. */
    static final Comparator<ThreadSummary> BY_ACTIVITY = Comparator.comparingLong(ThreadSummary::updatedAtMillis).reversed()
            .thenComparing(ThreadSummary::title, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ThreadSummary::id);

    void setData(List<WorkspaceProject> projects, ConnectionsView connections, String query,
                 @Nullable String selectedThreadId, boolean snoozedExpanded, boolean settledExpanded, int settledLimit, String emptyText) {
        this.rows.clear();
        this.connections = connections;
        this.selectedThreadId = selectedThreadId;
        this.snoozedExpanded = snoozedExpanded;
        this.settledExpanded = settledExpanded;
        this.emptyText = emptyText;

        var inProgress = new ArrayList<Entry>();
        var normal = new ArrayList<Entry>();
        var snoozed = new ArrayList<Entry>();
        var settled = new ArrayList<Entry>();
        var entries = new ArrayList<Entry>();
        for (var project : projects) {
            boolean matchesProject = matches(project.name(), query) || matches(project.source().name(), query);
            var seen = new java.util.HashSet<String>();
            for (var thread : project.project().threads()) {
                if (seen.add(thread.id()) && (matchesProject || matches(thread.title(), query)))
                    entries.add(new Entry(project, thread));
            }
        }
        entries.sort(Comparator.comparing(Entry::thread, BY_ACTIVITY));
        for (var entry : entries) {
            switch (entry.thread().organization()) {
                case IN_PROGRESS -> inProgress.add(entry);
                case SNOOZED -> snoozed.add(entry);
                case SETTLED -> settled.add(entry);
                case NORMAL -> normal.add(entry);
            }
        }
        this.settledCount = settled.size();

        int y = 3;
        y = this.section(y, L10n.tr("chat.sidebar.in_progress", "In progress"), inProgress);
        y = this.section(y, L10n.tr("chat.sidebar.threads", "Threads"), normal);
        this.snoozedCount = snoozed.size();
        if (!snoozed.isEmpty()) {
            y += this.rows.isEmpty() ? 0 : 6;
            this.rows.add(new Row(Kind.SNOOZED_HEADER, null, y, HEADER));
            y += HEADER + 1;
            if (snoozedExpanded) {
                snoozed.sort(Comparator.comparingLong(e -> e.thread().snoozedUntilMillis() > 0
                        ? e.thread().snoozedUntilMillis() : Long.MAX_VALUE));
                for (var entry : snoozed) {
                    this.rows.add(new Row(Kind.COMPACT_THREAD, entry, y, COMPACT));
                    y += COMPACT + 1;
                }
            }
        }
        this.hiddenSettled = 0;
        if (!settled.isEmpty()) {
            y += this.rows.isEmpty() ? 0 : 6;
            this.rows.add(new Row(Kind.SETTLED_HEADER, null, y, HEADER));
            y += HEADER + 1;
            if (settledExpanded) {
                int shown = Math.min(settled.size(), Math.max(settledLimit, SETTLED_PAGE));
                for (int i = 0; i < shown; i++) {
                    this.rows.add(new Row(Kind.COMPACT_THREAD, settled.get(i), y, COMPACT));
                    y += COMPACT + 1;
                }
                this.hiddenSettled = settled.size() - shown;
                if (this.hiddenSettled > 0) {
                    this.rows.add(new Row(Kind.MORE, null, y, COMPACT));
                }
            }
        }
        this.setScroll(this.scroll);
    }

    /** Adds a labelled section of full rows (nothing when empty) and returns the next y. */
    private int section(int y, String label, List<Entry> threads) {
        if (threads.isEmpty()) return y;
        if (!this.rows.isEmpty()) y += 6;
        this.rows.add(new Row(Kind.SECTION, null, label, threads.size(), y, HEADER - 4));
        y += HEADER - 2;
        for (var thread : threads) {
            this.rows.add(new Row(Kind.THREAD, thread, y, ROW));
            y += ROW + 2;
        }
        return y;
    }

    @Override
    protected int contentHeight() {
        if (this.rows.isEmpty()) return 0;
        var last = this.rows.getLast();
        return last.y() + last.height() + 8;
    }

    private @Nullable Row rowAt(double contentY) {
        for (var row : this.rows) if (contentY >= row.y() && contentY < row.y() + row.height()) return row;
        return null;
    }

    @Override
    protected boolean onContentClick(double mouseX, double contentY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
        var row = this.rowAt(contentY);
        if (row == null || row.kind() == Kind.SECTION) return false;
        this.playDownSound();
        switch (row.kind()) {
            case THREAD, COMPACT_THREAD -> {
                var buttons = row.buttons();
                int hit = buttonAt(buttons, this.getEndX() - 7, mouseX, contentY - row.y());
                var entry = row.entry();
                if (hit >= 0) this.actions.organizeThread(entry.project(), entry.thread().id(), buttons.get(hit));
                else this.actions.selectThread(entry.project(), entry.thread().id());
            }
            case SNOOZED_HEADER -> this.actions.toggleSnoozed();
            case SETTLED_HEADER -> this.actions.toggleSettled();
            case SECTION -> { }
            case MORE -> this.actions.showMoreSettled();
        }
        return true;
    }

    static boolean matches(String value, String query) {
        return value.toLowerCase(java.util.Locale.ROOT).contains(query.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /** Draws a crisp rounded tinted project badge and returns its width. */
    static int badge(SpruceGuiGraphics graphics, Font font, String projectKey, String projectName, int x, int y, int bg) {
        String initials = Theme.initials(projectName);
        int color = Theme.badgeColor(projectKey);
        int w = font.width(initials) + 6;
        int fill = Theme.mix(bg, color, Theme.isLight() ? 0.14f : 0.20f);
        int border = Theme.mix(bg, color, Theme.isLight() ? 0.34f : 0.38f);
        UiShapes.pill(graphics, x, y - 1, w, font.lineHeight + 2, fill, border);
        graphics.text(font, initials, x + 3, y, color, false);
        return w;
    }

    private record Status(ThreadStatus kind, String label, String shortLabel, int color) {}

    private static @Nullable Status status(ThreadSummary thread) {
        if (thread.hasPendingQuestion() || thread.status() == ThreadStatus.WAITING_FOR_INPUT) {
            return new Status(ThreadStatus.WAITING_FOR_INPUT, L10n.tr("chat.status.needs_input", "Needs input"), "?", Theme.AMBER);
        }
        return switch (thread.status()) {
            case RUNNING -> new Status(ThreadStatus.RUNNING, L10n.tr("chat.status.working", "Working"), "", Theme.ACCENT);
            case ERROR -> new Status(ThreadStatus.ERROR, L10n.tr("chat.status.error", "Error"), "!", Theme.RED);
            case COMPLETED -> new Status(ThreadStatus.COMPLETED, L10n.tr("chat.status.done", "Done"), "", Theme.GREEN);
            case IDLE, WAITING_FOR_INPUT -> null;
        };
    }

    @Override
    protected void drawContent(SpruceGuiGraphics graphics, int top, int mouseX, int mouseY) {
        var font = this.client.font;
        int left = this.getX() + 6;
        int right = this.getEndX() - 7;

        if (this.rows.isEmpty()) {
            if (this.emptyText.isEmpty()) return;
            int y = this.getY() + 10;
            var lines = font.split(MessageFormatter.colored(this.emptyText, Theme.FAINT), this.width - 24);
            int maxW = 0;
            for (var line : lines) maxW = Math.max(maxW, font.width(line));
            Theme.sheet(graphics, left, y - 5, Math.min(right, left + 8 + maxW + 6), y + lines.size() * (font.lineHeight + 2) + 4,
                    Theme.SIDEBAR_SHEET);
            for (var line : lines) {
                graphics.text(font, line, left + 6, y, Theme.FAINT, false);
                y += font.lineHeight + 2;
            }
            return;
        }

        this.drawSheets(graphics, top, left, right);
        boolean inside = this.isMouseHovered();
        for (var row : this.rows) {
            int y = top + row.y();
            int h = row.height();
            if (y + h < this.getY() || y > this.getEndY()) continue;
            if (row.kind() == Kind.SECTION) {
                int labelY = y + (h - font.lineHeight) / 2 + 1;
                graphics.text(font, row.label(), left + 4, labelY, Theme.MUTED, false);
                String cnt = Integer.toString(row.count());
                int cw = font.width(cnt) + 6;
                UiShapes.pill(graphics, right - 2 - cw, y + (h - 11) / 2, cw, 11, Theme.BUTTON, 0);
                graphics.text(font, cnt, right - 2 - cw + 3, labelY, Theme.FAINT, false);
                continue;
            }
            boolean hovered = inside && mouseY >= y && mouseY < y + h && mouseX < this.getEndX() - 6;
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
            var buttons = hovered ? row.buttons() : List.<ThreadAction>of();
            int buttonsWidth = buttons.size() * BUTTON_STRIDE;

            switch (row.kind()) {
                case THREAD -> this.drawThread(graphics, font, row.entry(), left, right, y, hovered, buttonsWidth);
                case COMPACT_THREAD -> this.drawCompact(graphics, font, row.entry(), left, right, y, hovered, buttonsWidth);
                case SECTION -> { }
                case SNOOZED_HEADER, SETTLED_HEADER -> {
                    boolean snoozed = row.kind() == Kind.SNOOZED_HEADER;
                    boolean expanded = snoozed ? this.snoozedExpanded : this.settledExpanded;
                    if (hovered) UiShapes.fillRounded(graphics, left, y, right - left, h, UiShapes.R_CONTROL, Theme.HOVER);
                    int textY = y + (h - font.lineHeight) / 2 + 1;
                    if (expanded) UiIcons.caretDown(graphics, left + 5, y + (h - 3) / 2, Theme.MUTED);
                    else UiIcons.caretRight(graphics, left + 6, y + (h - 5) / 2, Theme.MUTED);
                    graphics.text(font, snoozed ? L10n.tr("chat.sidebar.snoozed", "Snoozed") : L10n.tr("chat.sidebar.settled", "Settled"),
                            left + 14, textY, hovered ? Theme.TEXT : Theme.MUTED, false);
                    String count = Integer.toString(snoozed ? this.snoozedCount : this.settledCount);
                    int cw = font.width(count) + 6;
                    UiShapes.pill(graphics, right - 3 - cw, y + (h - 12) / 2, cw, 12,
                            hovered ? Theme.BUTTON_HOVER : Theme.BUTTON, Theme.BORDER_SUBTLE);
                    graphics.text(font, count, right - 3 - cw + 3, textY, Theme.FAINT, false);
                }
                case MORE -> {
                    if (hovered) UiShapes.fillRounded(graphics, left, y, right - left, h, UiShapes.R_CONTROL, Theme.HOVER);
                    int textY = y + (h - font.lineHeight) / 2 + 1;
                    String more = stripLeadingCaret(L10n.tr("chat.sidebar.show_more", "▾ Show %s more", Math.min(SETTLED_PAGE, this.hiddenSettled)));
                    UiIcons.caretDown(graphics, left + 5, y + (h - 3) / 2, hovered ? Theme.TEXT : Theme.MUTED);
                    graphics.text(font, more, left + 14, textY, hovered ? Theme.TEXT : Theme.MUTED, false);
                }
            }
            if (!buttons.isEmpty()) drawButtons(graphics, font, buttons, right, y, mouseX, mouseY);
        }
    }

    private static String stripLeadingCaret(String text) {
        String s = text.stripLeading();
        if (s.startsWith("▾")) return s.substring(1).stripLeading();
        return text;
    }

    private static int buttonAt(List<ThreadAction> buttons, int right, double x, double dy) {
        if (dy < BUTTON_TOP || dy >= BUTTON_TOP + BUTTON_SIZE) return -1;
        for (int i = 0; i < buttons.size(); i++) {
            int left = buttonLeft(right, buttons.size(), i);
            if (x >= left && x < left + BUTTON_SIZE) return i;
        }
        return -1;
    }

    private static int buttonLeft(int right, int count, int index) {
        return right - 3 - (count - index) * BUTTON_STRIDE;
    }

    private static void drawButtons(SpruceGuiGraphics graphics, Font font, List<ThreadAction> buttons,
                                    int right, int y, int mouseX, int mouseY) {
        int hit = buttonAt(buttons, right, mouseX, mouseY - y);
        for (int i = 0; i < buttons.size(); i++) {
            var action = buttons.get(i);
            int x = buttonLeft(right, buttons.size(), i);
            int top = y + BUTTON_TOP;
            boolean hot = i == hit;
            UiShapes.pill(graphics, x, top, BUTTON_SIZE, BUTTON_SIZE,
                    hot ? Theme.BUTTON_HOVER : Theme.BUTTON,
                    hot ? Theme.BORDER_STRONG : Theme.BORDER);
            int ink = hot ? Theme.TEXT : Theme.TEXT_SOFT;
            int iconX = x + (BUTTON_SIZE - ICON_SIZE) / 2;
            int iconY = top + (BUTTON_SIZE - ICON_SIZE) / 2;
            switch (action) {
                case SNOOZE, UNSNOOZE -> drawIcon(graphics, SNOOZE_ICON, iconX, iconY, action == ThreadAction.UNSNOOZE ? Theme.ACCENT : ink);
                case SETTLE -> drawIcon(graphics, SETTLE_ICON, iconX, iconY, ink);
                case UNSETTLE -> UiIcons.undo(graphics, x + (BUTTON_SIZE - 7) / 2, top + (BUTTON_SIZE - 7) / 2, ink);
            }
            if (hot) {
                String label = switch (action) {
                    case SNOOZE -> L10n.tr("snooze.action", "Snooze…");
                    case UNSNOOZE -> L10n.tr("snooze.wake", "Unsnooze");
                    case SETTLE -> L10n.tr("chat.sidebar.settle", "Settle thread");
                    case UNSETTLE -> L10n.tr("chat.sidebar.unsettle", "Unsettle thread");
                };
                graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(label), 180), mouseX, mouseY);
            }
        }
    }

    /**
     * One continuous rounded sheet per section (its label and rows) so text stays readable over the world without a
     * band per row. Rows themselves only paint opaque hover/selection fills on top.
     */
    private void drawSheets(SpruceGuiGraphics graphics, int top, int left, int right) {
        int start = -1;
        int end = -1;
        for (var row : this.rows) {
            boolean opens = row.kind() == Kind.SECTION || row.kind() == Kind.SNOOZED_HEADER || row.kind() == Kind.SETTLED_HEADER;
            if (opens && start >= 0) {
                Theme.sheet(graphics, left - 2, top + start - 2, right + 2, top + end + 2, Theme.SIDEBAR_SHEET);
                start = -1;
            }
            if (start < 0) start = row.y();
            end = row.y() + row.height();
        }
        if (start >= 0) Theme.sheet(graphics, left - 2, top + start - 2, right + 2, top + end + 2, Theme.SIDEBAR_SHEET);
    }

    private void drawThread(SpruceGuiGraphics graphics, Font font, Entry entry, int left, int right, int y,
                            boolean hovered, int buttonsWidth) {
        var thread = entry.thread();
        boolean selected = entry.project().source().matches(connections) && thread.id().equals(this.selectedThreadId);
        int bg = selected ? (hovered ? Theme.SELECTED_HOVER : Theme.SELECTED) : hovered ? Theme.HOVER : Theme.SIDEBAR;
        if (selected || hovered) {
            UiShapes.fillRounded(graphics, left, y, right - left, ROW, UiShapes.R_CONTROL, bg);
            if (selected) {
                UiShapes.outlineRounded(graphics, left, y, right - left, ROW, UiShapes.R_CONTROL,
                        Theme.mix(Theme.SELECTED, Theme.ACCENT, 0.40f));
                UiShapes.accentRail(graphics, left, y + 4, ROW - 8, Theme.ACCENT);
            }
        }

        // Line 1: project badge + name, relative time.
        int line1 = y + 5;
        String time = buttonsWidth == 0 ? Theme.relativeTime(thread.updatedAtMillis()) : "";
        int trailingWidth = buttonsWidth + font.width(time);
        int x = left + 7;
        String projectName = entry.project().name();
        if (!projectName.isEmpty()) {
            x += badge(graphics, font, thread.projectId(), projectName, x, line1, bg) + 5;
            String name = Theme.ellipsize(font, projectName, right - 10 - trailingWidth - x);
            graphics.text(font, name, x, line1, Theme.MUTED, false);
        }
        if (!time.isEmpty()) graphics.text(font, time, right - 6 - trailingWidth, line1, Theme.FAINT, false);

        // Line 2: environment icon, title, status.
        int line2 = y + TITLE_TOP;
        var status = status(thread);
        int titleRight = right - 6;
        if (status != null) {
            int available = right - left - 14;
            String label = font.width(status.label()) + 8 <= available / 2 ? status.label() : status.shortLabel();
            int labelW = label.isEmpty() ? 0 : font.width(label) + 3;
            int iconX = right - 6 - labelW - 5;
            UiIcons.threadStatus(graphics, iconX, line2 + 1, status.kind());
            if (!label.isEmpty()) graphics.text(font, label, right - 6 - font.width(label), line2, status.color(), false);
            titleRight = iconX - 5;
        }
        int titleX = drawConnectionIcon(graphics, font, entry, left + 7, line2);
        String title = Theme.ellipsize(font, Theme.orDefault(thread.title(), L10n.tr("chat.thread.untitled", "New thread")), titleRight - titleX);
        graphics.text(font, title, titleX, line2, selected ? Theme.TEXT : Theme.TEXT_SOFT, false);
    }

    private static int drawConnectionIcon(SpruceGuiGraphics graphics, Font font, Entry entry, int x, int y) {
        String icon = entry.project().source().icon();
        if (icon.isEmpty()) return x;
        return x + UiIcons.drawConnectionGlyph(graphics, font, icon, x, y, Theme.ACCENT) + 3;
    }

    private void drawCompact(SpruceGuiGraphics graphics, Font font, Entry entry, int left, int right, int y,
                             boolean hovered, int buttonsWidth) {
        var thread = entry.thread();
        boolean selected = entry.project().source().matches(connections) && thread.id().equals(this.selectedThreadId);
        if (selected || hovered) {
            UiShapes.fillRounded(graphics, left, y, right - left, COMPACT, UiShapes.R_CONTROL,
                    selected ? (hovered ? Theme.SELECTED_HOVER : Theme.SELECTED) : Theme.HOVER);
            if (selected) UiShapes.accentRail(graphics, left, y + 3, COMPACT - 6, Theme.ACCENT);
        }
        int textY = y + (COMPACT - font.lineHeight) / 2 + 1;
        String initials = Theme.initials(entry.project().name());
        int x = drawConnectionIcon(graphics, font, entry, left + 7, textY);
        graphics.text(font, initials, x, textY, Theme.FAINT, false);
        x += font.width("WW") + 4;
        boolean snoozed = thread.organization() == ThreadOrganization.SNOOZED;
        String time = snoozed ? Theme.timeUntil(thread.snoozedUntilMillis(), System.currentTimeMillis())
                : Theme.relativeTime(thread.updatedAtMillis());
        int trailingWidth = buttonsWidth;
        if (buttonsWidth == 0) {
            int timeX = right - 6 - font.width(time);
            if (snoozed) drawIcon(graphics, SNOOZE_ICON, timeX - (ICON_SIZE + 2), textY, Theme.ACCENT);
            if (!time.isEmpty()) graphics.text(font, time, timeX, textY, snoozed ? Theme.ACCENT : Theme.FAINT, false);
            trailingWidth = (snoozed ? ICON_SIZE + 2 : 0) + (time.isEmpty() ? 0 : font.width(time) + 4);
        }
        String title = Theme.ellipsize(font, Theme.orDefault(thread.title(), L10n.tr("chat.thread.untitled", "New thread")), right - 6 - trailingWidth - x);
        graphics.text(font, title, x, textY, selected ? Theme.TEXT : Theme.MUTED, false);
    }

    private static void drawIcon(SpruceGuiGraphics graphics, Identifier sprite, int x, int y, int color) {
        graphics.vanilla().blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, ICON_SIZE, ICON_SIZE, color);
    }
}
