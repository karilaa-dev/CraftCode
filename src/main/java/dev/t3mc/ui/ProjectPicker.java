package dev.t3mc.ui;

import dev.t3mc.L10n;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.t3mc.ui.model.ProjectView;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lists every project (including ones without threads) so a new thread is always started in an
 * explicitly chosen project. Shown in place of the thread list while open.
 */
final class ProjectPicker extends ScrollableWidget {
    private static final int TITLE = 20;
    private static final int ROW = 25;

    private final Consumer<WorkspaceProject> onPick;
    private List<WorkspaceProject> projects = List.of();
    private @Nullable WorkspaceProject.Key suggestedId;
    private boolean filtering;

    ProjectPicker(Position position, int width, int height, Consumer<WorkspaceProject> onPick) {
        super(position, width, height);
        this.onPick = onPick;
    }

    /** Projects with recent activity first, then the rest in their original order. */
    void setProjects(List<WorkspaceProject> projects, @Nullable WorkspaceProject.Key suggestedId, boolean filtering) {
        var sorted = new ArrayList<>(projects);
        sorted.sort((a, b) -> Long.compare(latest(b.project()), latest(a.project())));
        this.projects = sorted;
        this.filtering = filtering;
        this.suggestedId = suggestedId;
        this.setScroll(this.scroll);
    }

    private static long latest(ProjectView project) {
        long max = 0;
        for (var thread : project.threads()) max = Math.max(max, thread.updatedAtMillis());
        return max;
    }

    @Override
    protected int contentHeight() {
        return TITLE + Math.max(1, this.projects.size() + (filtering ? 1 : 0)) * ROW + 8;
    }

    @Override
    protected boolean onContentClick(double mouseX, double contentY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || contentY < TITLE) return false;
        int index = (int) ((contentY - TITLE) / ROW);
        if (filtering) {
            if (index == 0) {
                this.playDownSound();
                this.onPick.accept(null);
                return true;
            }
            index--;
        }
        if (index < 0 || index >= this.projects.size()) return false;
        this.playDownSound();
        this.onPick.accept(this.projects.get(index));
        return true;
    }

    @Override
    protected void drawContent(SpruceGuiGraphics graphics, int top, int mouseX, int mouseY) {
        var font = this.client.font;
        int left = this.getX() + 6;
        int right = this.getEndX() - 7;
        int rowsEnd = top + TITLE + Math.max(1, this.projects.size() + (filtering ? 1 : 0)) * ROW;
        Theme.sheet(graphics, left - 2, top + 2, right + 2, rowsEnd + 4, Theme.SIDEBAR_SHEET);
        graphics.text(font, filtering ? L10n.tr("sidebar.filter", "Filter projects") : L10n.tr("chat.picker.title", "Start a thread in…"),
                left + 4, top + 7, Theme.MUTED, false);

        boolean inside = this.isMouseHovered();
        if (filtering) {
            int allY = top + TITLE;
            boolean allHovered = inside && mouseY >= allY && mouseY < allY + ROW && mouseX < this.getEndX() - 6;
            boolean allSelected = this.suggestedId == null;
            if (allSelected || allHovered) {
                UiShapes.fillRounded(graphics, left, allY, right - left, ROW - 1, UiShapes.R_CONTROL,
                        allSelected ? (allHovered ? Theme.SELECTED_HOVER : Theme.SELECTED) : Theme.HOVER);
                if (allSelected) UiShapes.accentRail(graphics, left, allY + 4, ROW - 9, Theme.ACCENT);
            }
            if (allHovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
            graphics.text(font, L10n.tr("sidebar.all", "All projects"), left + 7, allY + (ROW - font.lineHeight) / 2,
                    allSelected || allHovered ? Theme.TEXT : Theme.TEXT_SOFT, false);
            top += ROW;
        }
        if (this.projects.isEmpty()) {
            String none = Theme.ellipsize(font, L10n.tr("chat.picker.no_projects", "No projects. Add one in T3 Code."), right - left - 12);
            graphics.text(font, none, left + 7, top + TITLE + 7, Theme.FAINT, false);
            return;
        }

        for (int i = 0; i < this.projects.size(); i++) {
            var choice = this.projects.get(i);
            var project = choice.project();
            int y = top + TITLE + i * ROW;
            if (y + ROW < this.getY() || y > this.getEndY()) continue;
            boolean hovered = inside && mouseY >= y && mouseY < y + ROW && mouseX < this.getEndX() - 6;
            boolean suggested = choice.key().equals(this.suggestedId);
            int bg = suggested ? (hovered ? Theme.SELECTED_HOVER : Theme.SELECTED) : hovered ? Theme.HOVER : Theme.SIDEBAR;
            if (hovered || suggested) {
                UiShapes.fillRounded(graphics, left, y, right - left, ROW - 1, UiShapes.R_CONTROL, bg);
            }
            if (suggested) UiShapes.accentRail(graphics, left, y + 4, ROW - 9, Theme.ACCENT);
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);

            String name = Theme.orDefault(project.name(), project.path());
            int x = left + 7;
            int line1 = y + 4;
            x += SidebarList.badge(graphics, font, project.id(), name, x, line1, bg) + 5;
            int count = project.threads().size();
            String countText = count == 0 ? L10n.tr("chat.picker.empty", "empty") : Integer.toString(count);
            int countW = font.width(countText);
            int pillW = countW + 6;
            UiShapes.pill(graphics, right - 4 - pillW, line1 - 1, pillW, font.lineHeight + 2,
                    hovered ? Theme.BUTTON_HOVER : Theme.BUTTON, 0);
            graphics.text(font, countText, right - 4 - pillW + 3, line1, Theme.FAINT, false);
            graphics.text(font, Theme.ellipsize(font, name, right - 10 - pillW - x), x, line1,
                    hovered || suggested ? Theme.TEXT : Theme.TEXT_SOFT, false);
            graphics.text(font, Theme.ellipsize(font, choice.source().name(), right - 6 - (left + 7)), left + 7, y + 14,
                    Theme.FAINT, false);
        }
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        return Component.literal(L10n.tr("chat.picker.narration", "Choose a project for the new thread"));
    }
}
