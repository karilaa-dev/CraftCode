package dev.t3mc.ui;

import dev.t3mc.L10n;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.t3mc.ui.model.ConnectionsView;
import dev.t3mc.ui.model.ThreadAction;
import dev.t3mc.ui.model.ThreadOrganization;
import dev.t3mc.ui.model.ThreadStatus;
import dev.t3mc.ui.model.ThreadSummary;
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
 * section (only when it has threads), the ordinary "Threads" section, then independently collapsible "Snoozed" and "Settled" sections with
 * compact rows. Each section is ordered by last activity, newest first. Full rows are two lines
 * (project badge + name + time, then title + status).
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
    private static final int COMPACT = 15;
    private static final int HEADER = 18;
    private static final int BUTTON_TOP = 1;
    private static final int BUTTON_SIZE = 14;
    private static final int BUTTON_STRIDE = BUTTON_SIZE + 1;
    private static final int TITLE_TOP = BUTTON_TOP + BUTTON_SIZE + 5;
    private static final int ROW = TITLE_TOP + 12;
    private static final int ICON_SIZE = 8;
    private static final Identifier SNOOZE_ICON = Identifier.fromNamespaceAndPath("t3mc", "thread/snooze");
    private static final Identifier SETTLE_ICON = Identifier.fromNamespaceAndPath("t3mc", "thread/settle");

    private enum Kind { SECTION, THREAD, SNOOZED_HEADER, SETTLED_HEADER, COMPACT_THREAD, MORE }

    private record Entry(WorkspaceProject project, ThreadSummary thread) {}

    private record Row(Kind kind, @Nullable Entry entry, String label, int y, int height) {
        Row(Kind kind, @Nullable Entry entry, int y, int height) {
            this(kind, entry, "", y, height);
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

        int y = 2;
        y = this.section(y, L10n.tr("chat.sidebar.in_progress", "In progress"), inProgress);
        y = this.section(y, L10n.tr("chat.sidebar.threads", "Threads"), normal);
        this.snoozedCount = snoozed.size();
        if (!snoozed.isEmpty()) {
            y += this.rows.isEmpty() ? 0 : 4;
            this.rows.add(new Row(Kind.SNOOZED_HEADER, null, y, HEADER));
            y += HEADER;
            if (snoozedExpanded) {
                snoozed.sort(Comparator.comparingLong(e -> e.thread().snoozedUntilMillis() > 0
                        ? e.thread().snoozedUntilMillis() : Long.MAX_VALUE));
                for (var entry : snoozed) {
                    this.rows.add(new Row(Kind.COMPACT_THREAD, entry, y, COMPACT));
                    y += COMPACT;
                }
            }
        }
        this.hiddenSettled = 0;
        if (!settled.isEmpty()) {
            y += this.rows.isEmpty() ? 0 : 4;
            this.rows.add(new Row(Kind.SETTLED_HEADER, null, y, HEADER));
            y += HEADER;
            if (settledExpanded) {
                int shown = Math.min(settled.size(), Math.max(settledLimit, SETTLED_PAGE));
                for (int i = 0; i < shown; i++) {
                    this.rows.add(new Row(Kind.COMPACT_THREAD, settled.get(i), y, COMPACT));
                    y += COMPACT;
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
        if (!this.rows.isEmpty()) y += 4;
        this.rows.add(new Row(Kind.SECTION, null, label + "  " + threads.size(), y, HEADER - 4));
        y += HEADER - 4;
        for (var thread : threads) {
            this.rows.add(new Row(Kind.THREAD, thread, y, ROW));
            y += ROW + 1;
        }
        return y;
    }

    @Override
    protected int contentHeight() {
        if (this.rows.isEmpty()) return 0;
        var last = this.rows.getLast();
        return last.y() + last.height() + 6;
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
                int hit = buttonAt(buttons, this.getEndX() - 6, mouseX, contentY - row.y());
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

    /** Draws a small tinted project badge and returns its width. */
    static int badge(SpruceGuiGraphics graphics, Font font, String projectKey, String projectName, int x, int y, int bg) {
        String initials = Theme.initials(projectName);
        int color = Theme.badgeColor(projectKey);
        int w = font.width(initials) + 4;
        graphics.fill(x, y - 1, x + w, y + font.lineHeight, Theme.mix(bg, color, Theme.isLight() ? 0.16f : 0.22f));
        graphics.text(font, initials, x + 2, y, color, false);
        return w;
    }

    private record Status(String label, String shortLabel, int color, boolean pulse) {}

    private static @Nullable Status status(ThreadSummary thread) {
        if (thread.hasPendingQuestion() || thread.status() == ThreadStatus.WAITING_FOR_INPUT) {
            return new Status(L10n.tr("chat.status.needs_input", "Needs input"), "?", Theme.AMBER, true);
        }
        return switch (thread.status()) {
            case RUNNING -> new Status(L10n.tr("chat.status.working", "Working"), "", Theme.ACCENT, true);
            case ERROR -> new Status(L10n.tr("chat.status.error", "Error"), "!", Theme.RED, false);
            case COMPLETED -> new Status(L10n.tr("chat.status.done", "Done"), "", Theme.GREEN, false);
            case IDLE, WAITING_FOR_INPUT -> null;
        };
    }

    @Override
    protected void drawContent(SpruceGuiGraphics graphics, int top, int mouseX, int mouseY) {
        var font = this.client.font;
        int left = this.getX() + 4;
        int right = this.getEndX() - 6;

        if (this.rows.isEmpty()) {
            if (this.emptyText.isEmpty()) return;
            int y = this.getY() + 8;
            var lines = font.split(MessageFormatter.colored(this.emptyText, Theme.FAINT), this.width - 20);
            int maxW = 0;
            for (var line : lines) maxW = Math.max(maxW, font.width(line));
            Theme.sheet(graphics, left + 2, y - 4, Math.min(right, left + 6 + maxW + 4), y + lines.size() * (font.lineHeight + 2) + 2,
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
                graphics.text(font, row.label(), left + 6, labelY, Theme.MUTED, false);
                continue;
            }
            boolean hovered = inside && mouseY >= y && mouseY < y + h && mouseX < this.getEndX() - 5;
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
            var buttons = hovered ? row.buttons() : List.<ThreadAction>of();
            int buttonsWidth = buttons.size() * BUTTON_STRIDE;

            switch (row.kind()) {
                case THREAD -> this.drawThread(graphics, font, row.entry(), left, right, y, hovered, buttonsWidth);
                case COMPACT_THREAD -> this.drawCompact(graphics, font, row.entry(), left, right, y, hovered, buttonsWidth);
                case SECTION -> { }
                case SNOOZED_HEADER, SETTLED_HEADER -> {
                    boolean snoozed = row.kind() == Kind.SNOOZED_HEADER;
                    if (hovered) graphics.fill(left, y, right, y + h, Theme.HOVER);
                    int textY = y + (h - font.lineHeight) / 2 + 1;
                    graphics.text(font, snoozed ? L10n.tr("chat.sidebar.snoozed", "Snoozed") : L10n.tr("chat.sidebar.settled", "Settled"), left + 6, textY, Theme.MUTED, false);
                    String chevron = (snoozed ? this.snoozedExpanded : this.settledExpanded) ? "▾" : "▸";
                    String count = Integer.toString(snoozed ? this.snoozedCount : this.settledCount);
                    graphics.text(font, chevron, right - 4 - font.width(chevron), textY, Theme.FAINT, false);
                    graphics.text(font, count, right - 10 - font.width(chevron) - font.width(count), textY, Theme.FAINT, false);
                }
                case MORE -> {
                    if (hovered) graphics.fill(left, y, right, y + h, Theme.HOVER);
                    int textY = y + (h - font.lineHeight) / 2 + 1;
                    String more = L10n.tr("chat.sidebar.show_more", "▾ Show %s more", Math.min(SETTLED_PAGE, this.hiddenSettled));
                    graphics.text(font, more, left + 6, textY, hovered ? Theme.TEXT : Theme.MUTED, false);
                }
            }
            if (!buttons.isEmpty()) drawButtons(graphics, font, buttons, right, y, mouseX, mouseY);
        }
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
        return right - 2 - (count - index) * BUTTON_STRIDE;
    }

    private static void drawButtons(SpruceGuiGraphics graphics, Font font, List<ThreadAction> buttons,
                                    int right, int y, int mouseX, int mouseY) {
        int hit = buttonAt(buttons, right, mouseX, mouseY - y);
        for (int i = 0; i < buttons.size(); i++) {
            var action = buttons.get(i);
            int x = buttonLeft(right, buttons.size(), i);
            int top = y + BUTTON_TOP;
            graphics.fill(x, top, x + BUTTON_SIZE, top + BUTTON_SIZE,
                    i == hit ? Theme.BUTTON_HOVER : Theme.BUTTON);
            int ink = i == hit ? Theme.TEXT : Theme.TEXT_SOFT;
            int iconX = x + (BUTTON_SIZE - ICON_SIZE) / 2;
            int iconY = top + (BUTTON_SIZE - ICON_SIZE) / 2;
            switch (action) {
                case SNOOZE, UNSNOOZE -> drawIcon(graphics, SNOOZE_ICON, iconX, iconY, action == ThreadAction.UNSNOOZE ? Theme.ACCENT : ink);
                case SETTLE -> drawIcon(graphics, SETTLE_ICON, iconX, iconY, ink);
                case UNSETTLE -> graphics.text(font, "↶", x + (BUTTON_SIZE - font.width("↶")) / 2, y + 4, ink, false);
            }
            if (i == hit) {
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
     * One continuous sheet per section (its label and rows) so text stays readable over the world without a band per
     * row. Rows themselves only paint opaque hover/selection fills on top.
     */
    private void drawSheets(SpruceGuiGraphics graphics, int top, int left, int right) {
        int start = -1;
        int end = -1;
        for (var row : this.rows) {
            boolean opens = row.kind() == Kind.SECTION || row.kind() == Kind.SNOOZED_HEADER || row.kind() == Kind.SETTLED_HEADER;
            if (opens && start >= 0) {
                Theme.sheet(graphics, left - 2, top + start - 1, right + 2, top + end + 1, Theme.SIDEBAR_SHEET);
                start = -1;
            }
            if (start < 0) start = row.y();
            end = row.y() + row.height();
        }
        if (start >= 0) Theme.sheet(graphics, left - 2, top + start - 1, right + 2, top + end + 1, Theme.SIDEBAR_SHEET);
    }

    private void drawThread(SpruceGuiGraphics graphics, Font font, Entry entry, int left, int right, int y,
                            boolean hovered, int buttonsWidth) {
        var thread = entry.thread();
        boolean selected = entry.project().source().matches(connections) && thread.id().equals(this.selectedThreadId);
        int bg = selected ? Theme.SELECTED : hovered ? Theme.HOVER : Theme.SIDEBAR;
        if (selected || hovered) graphics.fill(left, y, right, y + ROW, bg);

        // Line 1: project badge + name, relative time.
        int line1 = y + 4;
        String time = buttonsWidth == 0 ? Theme.relativeTime(thread.updatedAtMillis()) : "";
        int trailingWidth = buttonsWidth + font.width(time);
        int x = left + 6;
        String projectName = entry.project().name();
        if (!projectName.isEmpty()) {
            x += badge(graphics, font, thread.projectId(), projectName, x, line1, bg) + 4;
            String name = Theme.ellipsize(font, projectName, right - 10 - trailingWidth - x);
            graphics.text(font, name, x, line1, Theme.MUTED, false);
        }
        if (!time.isEmpty()) graphics.text(font, time, right - 5 - trailingWidth, line1, Theme.FAINT, false);

        // Line 2: environment icon, title, status.
        int line2 = y + TITLE_TOP;
        var status = status(thread);
        int titleRight = right - 5;
        if (status != null) {
            int available = right - left - 12;
            String label = font.width(status.label()) + 6 <= available / 2 ? status.label() : status.shortLabel();
            int labelW = label.isEmpty() ? 0 : font.width(label) + 2;
            int dotX = right - 5 - labelW - 4;
            Theme.dot(graphics, dotX, line2 + 2, status.color(), status.pulse());
            if (!label.isEmpty()) graphics.text(font, label, right - 5 - font.width(label), line2, status.color(), false);
            titleRight = dotX - 4;
        }
        int titleX = drawConnectionIcon(graphics, font, entry, left + 6, line2);
        String title = Theme.ellipsize(font, Theme.orDefault(thread.title(), L10n.tr("chat.thread.untitled", "New thread")), titleRight - titleX);
        graphics.text(font, title, titleX, line2, selected ? Theme.TEXT : Theme.TEXT_SOFT, false);
    }

    private static int drawConnectionIcon(SpruceGuiGraphics graphics, Font font, Entry entry, int x, int y) {
        String icon = entry.project().source().icon();
        if (icon.isEmpty()) return x;
        graphics.text(font, icon, x, y, Theme.ACCENT, false);
        return x + font.width(icon) + 3;
    }

    private void drawCompact(SpruceGuiGraphics graphics, Font font, Entry entry, int left, int right, int y,
                             boolean hovered, int buttonsWidth) {
        var thread = entry.thread();
        boolean selected = entry.project().source().matches(connections) && thread.id().equals(this.selectedThreadId);
        if (selected) graphics.fill(left, y, right, y + COMPACT, Theme.SELECTED);
        else if (hovered) graphics.fill(left, y, right, y + COMPACT, Theme.HOVER);
        int textY = y + (COMPACT - font.lineHeight) / 2 + 1;
        String initials = Theme.initials(entry.project().name());
        int x = drawConnectionIcon(graphics, font, entry, left + 6, textY);
        graphics.text(font, initials, x, textY, Theme.FAINT, false);
        x += font.width("WW") + 4;
        boolean snoozed = thread.organization() == ThreadOrganization.SNOOZED;
        String time = snoozed ? Theme.timeUntil(thread.snoozedUntilMillis(), System.currentTimeMillis())
                : Theme.relativeTime(thread.updatedAtMillis());
        int trailingWidth = buttonsWidth;
        if (buttonsWidth == 0) {
            int timeX = right - 5 - font.width(time);
            if (snoozed) drawIcon(graphics, SNOOZE_ICON, timeX - 10, textY, Theme.ACCENT);
            if (!time.isEmpty()) graphics.text(font, time, timeX, textY, snoozed ? Theme.ACCENT : Theme.FAINT, false);
            trailingWidth = (snoozed ? 10 : 0) + (time.isEmpty() ? 0 : font.width(time) + 4);
        }
        String title = Theme.ellipsize(font, Theme.orDefault(thread.title(), L10n.tr("chat.thread.untitled", "New thread")), right - 5 - trailingWidth - x);
        graphics.text(font, title, x, textY, selected ? Theme.TEXT : Theme.MUTED, false);
    }

    private static void drawIcon(SpruceGuiGraphics graphics, Identifier sprite, int x, int y, int color) {
        graphics.vanilla().blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, ICON_SIZE, ICON_SIZE, color);
    }
}
