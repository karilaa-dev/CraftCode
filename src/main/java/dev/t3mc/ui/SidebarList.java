package dev.t3mc.ui;

import dev.t3mc.L10n;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.t3mc.ui.model.ProjectView;
import dev.t3mc.ui.model.ThreadOrganization;
import dev.t3mc.ui.model.ThreadStatus;
import dev.t3mc.ui.model.ThreadSummary;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread list across all projects, grouped like the T3 Code app by {@link ThreadOrganization}: an "In progress"
 * section (only when it has threads), the ordinary "Threads" section, then a collapsible "Settled" section with
 * compact rows. Each section is ordered by last activity, newest first. Full rows are two lines
 * (project badge + name + time, then title + status).
 */
final class SidebarList extends ScrollableWidget {
    interface Actions {
        void selectThread(String threadId);

        void toggleSettled();

        void showMoreSettled();
    }

    static final int SETTLED_PAGE = 25;
    private static final int ROW = 27;
    private static final int COMPACT = 15;
    private static final int HEADER = 18;

    private enum Kind { SECTION, THREAD, SETTLED_HEADER, SETTLED_THREAD, MORE }

    private record Row(Kind kind, @Nullable ThreadSummary thread, String label, int y, int height) {
        Row(Kind kind, @Nullable ThreadSummary thread, int y, int height) {
            this(kind, thread, "", y, height);
        }
    }

    private final Actions actions;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, ProjectView> projectsById = new HashMap<>();
    private String emptyText = "";
    private @Nullable String selectedThreadId;
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

    /** All threads across projects, de-duplicated, newest activity first. */
    static List<ThreadSummary> allThreads(List<ProjectView> projects) {
        var byId = new LinkedHashMap<String, ThreadSummary>();
        for (var project : projects) for (var thread : project.threads()) byId.putIfAbsent(thread.id(), thread);
        var list = new ArrayList<>(byId.values());
        list.sort(BY_ACTIVITY);
        return list;
    }

    void setData(List<ProjectView> projects, @Nullable String selectedThreadId, boolean settledExpanded, int settledLimit,
                 String emptyText) {
        this.rows.clear();
        this.projectsById.clear();
        for (var project : projects) {
            this.projectsById.put(project.id(), project);
        }
        this.selectedThreadId = selectedThreadId;
        this.settledExpanded = settledExpanded;
        this.emptyText = emptyText;

        var inProgress = new ArrayList<ThreadSummary>();
        var normal = new ArrayList<ThreadSummary>();
        var settled = new ArrayList<ThreadSummary>();
        for (var thread : allThreads(projects)) {
            switch (thread.organization()) {
                case IN_PROGRESS -> inProgress.add(thread);
                case SETTLED -> settled.add(thread);
                case NORMAL -> normal.add(thread);
            }
        }
        this.settledCount = settled.size();

        int y = 2;
        y = this.section(y, L10n.tr("chat.sidebar.in_progress", "In progress"), inProgress);
        y = this.section(y, L10n.tr("chat.sidebar.threads", "Threads"), normal);
        this.hiddenSettled = 0;
        if (!settled.isEmpty()) {
            y += this.rows.isEmpty() ? 0 : 4;
            this.rows.add(new Row(Kind.SETTLED_HEADER, null, y, HEADER));
            y += HEADER;
            if (settledExpanded) {
                int shown = Math.min(settled.size(), Math.max(settledLimit, SETTLED_PAGE));
                for (int i = 0; i < shown; i++) {
                    this.rows.add(new Row(Kind.SETTLED_THREAD, settled.get(i), y, COMPACT));
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
    private int section(int y, String label, List<ThreadSummary> threads) {
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
            case THREAD, SETTLED_THREAD -> this.actions.selectThread(row.thread().id());
            case SETTLED_HEADER -> this.actions.toggleSettled();
            case SECTION -> { }
            case MORE -> this.actions.showMoreSettled();
        }
        return true;
    }

    private String projectName(ThreadSummary thread) {
        var project = this.projectsById.get(thread.projectId());
        if (project == null) return "";
        return Theme.orDefault(project.name(), lastSegment(project.path()));
    }

    private static String lastSegment(String path) {
        String trimmed = path.replaceAll("[/\\\\]+$", "");
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
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

            switch (row.kind()) {
                case THREAD -> this.drawThread(graphics, font, row.thread(), left, right, y, hovered);
                case SETTLED_THREAD -> this.drawSettled(graphics, font, row.thread(), left, right, y, hovered);
                case SECTION -> { }
                case SETTLED_HEADER -> {
                    if (hovered) graphics.fill(left, y, right, y + h, Theme.HOVER);
                    int textY = y + (h - font.lineHeight) / 2 + 1;
                    graphics.text(font, L10n.tr("chat.sidebar.settled", "Settled"), left + 6, textY, Theme.MUTED, false);
                    String chevron = this.settledExpanded ? "▾" : "▸";
                    String count = Integer.toString(this.settledCount);
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
            boolean opens = row.kind() == Kind.SECTION || row.kind() == Kind.SETTLED_HEADER;
            if (opens && start >= 0) {
                Theme.sheet(graphics, left - 2, top + start - 1, right + 2, top + end + 1, Theme.SIDEBAR_SHEET);
                start = -1;
            }
            if (start < 0) start = row.y();
            end = row.y() + row.height();
        }
        if (start >= 0) Theme.sheet(graphics, left - 2, top + start - 1, right + 2, top + end + 1, Theme.SIDEBAR_SHEET);
    }

    private void drawThread(SpruceGuiGraphics graphics, Font font, ThreadSummary thread, int left, int right, int y,
                            boolean hovered) {
        boolean selected = thread.id().equals(this.selectedThreadId);
        int bg = selected ? Theme.SELECTED : hovered ? Theme.HOVER : Theme.SIDEBAR;
        if (selected || hovered) graphics.fill(left, y, right, y + ROW, bg);

        // Line 1: project badge + name, relative time.
        int line1 = y + 4;
        String time = Theme.relativeTime(thread.updatedAtMillis());
        int timeW = time.isEmpty() ? 0 : font.width(time);
        int x = left + 6;
        String projectName = this.projectName(thread);
        if (!projectName.isEmpty()) {
            x += badge(graphics, font, thread.projectId(), projectName, x, line1, bg) + 4;
            String name = Theme.ellipsize(font, projectName, right - 6 - timeW - 4 - x);
            graphics.text(font, name, x, line1, Theme.MUTED, false);
        }
        if (timeW > 0) graphics.text(font, time, right - 5 - timeW, line1, Theme.FAINT, false);

        // Line 2: title, status.
        int line2 = y + 15;
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
        String title = Theme.ellipsize(font, Theme.orDefault(thread.title(), L10n.tr("chat.thread.untitled", "New thread")), titleRight - (left + 6));
        graphics.text(font, title, left + 6, line2, selected ? Theme.TEXT : Theme.TEXT_SOFT, false);
    }

    private void drawSettled(SpruceGuiGraphics graphics, Font font, ThreadSummary thread, int left, int right, int y,
                             boolean hovered) {
        boolean selected = thread.id().equals(this.selectedThreadId);
        if (selected) graphics.fill(left, y, right, y + COMPACT, Theme.SELECTED);
        else if (hovered) graphics.fill(left, y, right, y + COMPACT, Theme.HOVER);
        int textY = y + (COMPACT - font.lineHeight) / 2 + 1;
        String initials = Theme.initials(this.projectName(thread));
        int x = left + 6;
        graphics.text(font, initials, x, textY, Theme.FAINT, false);
        x += font.width("WW") + 4;
        String time = Theme.relativeTime(thread.updatedAtMillis());
        int timeW = time.isEmpty() ? 0 : font.width(time) + 4;
        String title = Theme.ellipsize(font, Theme.orDefault(thread.title(), L10n.tr("chat.thread.untitled", "New thread")), right - 5 - timeW - x);
        graphics.text(font, title, x, textY, selected ? Theme.TEXT : Theme.MUTED, false);
        if (!time.isEmpty()) graphics.text(font, time, right - 5 - font.width(time), textY, Theme.FAINT, false);
    }
}
