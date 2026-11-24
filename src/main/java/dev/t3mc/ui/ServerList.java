package dev.t3mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.t3mc.L10n;
import dev.t3mc.ui.model.ConnectionProfileView;
import dev.t3mc.ui.model.ConnectionState;
import dev.t3mc.ui.model.ConnectionsView;
import dev.t3mc.ui.model.LinkedEnvironmentView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Environment browser for the connections screen: one two-line row per saved server (name over its short host,
 * status on the right) with its linked machines nested underneath on tree connectors. A keyboard/mouse cursor picks
 * the row the primary action opens; a separate accent bar marks where the workspace points right now.
 */
final class ServerList extends ScrollableWidget {
    static final int SERVER_H = 26;
    static final int SERVER_SINGLE_H = 20;
    static final int MACHINE_H = 19;
    static final int NOTE_H = 15;
    private static final int PAD = 3;
    private static final int CHEVRON_W = 15;
    private static final int CHILD_INDENT = 15;

    sealed interface Row permits Server, Machine, Note {
        String key();
    }

    record Server(ConnectionProfileView profile, String title, String host, boolean current, boolean expandable,
                  boolean expanded) implements Row {
        public String key() { return "s:" + profile.id(); }
    }

    record Machine(ConnectionProfileView parent, String parentTitle, LinkedEnvironmentView link, boolean current,
                   boolean last) implements Row {
        public String key() { return "m:" + parent.id() + "\n" + link.id(); }

        boolean openable() { return link.reachable() && parent.state() == ConnectionState.CONNECTED; }
    }

    record Note(String parentId, String text, boolean error, boolean last) implements Row {
        public String key() { return "n:" + parentId; }
    }

    interface Actions {
        void cursorMoved();

        void open(Row row);

        void toggle(Server server);
    }

    private final Actions actions;
    private List<Row> rows = List.of();
    private final List<Integer> tops = new ArrayList<>();
    private final List<Integer> selectable = new ArrayList<>();
    private int contentHeight;
    private int statusWidth;
    private static final Style SERVER_NAME = Style.EMPTY.withBold(true);
    private int cursor = -1; // index into selectable
    private boolean doubleClick;

    ServerList(Position position, int width, int height, Actions actions) {
        super(position, width, height);
        this.actions = actions;
    }

    /** Builds the rows for {@code view}; nested machines are listed for every server whose model exposes them. */
    static List<Row> rowsFor(ConnectionsView view) {
        var rows = new ArrayList<Row>();
        for (var profile : view.profiles()) {
            boolean active = profile.id().equals(view.activeProfileId());
            String title = title(profile);
            String host = shortHost(profile.endpoint());
            if (host.equalsIgnoreCase(title)) host = "";
            boolean expandable = profile.linksAvailable();
            boolean expanded = expandable && profile.linksExpanded();
            rows.add(new Server(profile, title, host, active && view.environmentId().isEmpty(), expandable, expanded));
            if (!expanded) continue;
            var children = new ArrayList<Row>();
            for (var link : profile.links())
                children.add(new Machine(profile, title, link, active && link.id().equals(view.environmentId()), false));
            if (!profile.linkError().isBlank()) children.add(new Note(profile.id(), profile.linkError(), true, false));
            else if (profile.links().isEmpty()) children.add(new Note(profile.id(),
                    profile.state() != ConnectionState.CONNECTED ? L10n.tr("servers.links.connect", "Connect to list linked machines")
                            : profile.refreshingLinks() ? L10n.tr("servers.links.loading", "Looking for linked machines…")
                            : L10n.tr("servers.links.none", "No linked machines found"),
                    false, false));
            // Mark the final child so its connector ends in an elbow.
            int lastIndex = children.size() - 1;
            if (lastIndex >= 0) children.set(lastIndex, switch (children.get(lastIndex)) {
                case Machine m -> new Machine(m.parent(), m.parentTitle(), m.link(), m.current(), true);
                case Note n -> new Note(n.parentId(), n.text(), n.error(), true);
                case Server s -> s;
            });
            rows.addAll(children);
        }
        return rows;
    }

    /** The server's own name, or its short host when it was saved without one (or with just its URL). */
    static String title(ConnectionProfileView profile) {
        String name = profile.name() == null ? "" : profile.name().strip();
        if (name.isEmpty() || name.contains("://") || name.equalsIgnoreCase(profile.endpoint())) return shortHost(profile.endpoint());
        return name;
    }

    /** {@code https://cedar.lan:3773/mcp} → {@code cedar.lan:3773}. */
    static String shortHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return "";
        try {
            var uri = URI.create(endpoint.strip());
            if (uri.getHost() != null) return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (IllegalArgumentException ignored) {
        }
        String s = endpoint.strip().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : s;
    }

    static String machineStatus(LinkedEnvironmentView link) {
        if (link.reachable()) return L10n.tr("servers.status.online", "Online");
        String s = link.status() == null ? "" : link.status().strip().replace('-', ' ').replace('_', ' ');
        if (s.isEmpty() || s.equals("unreachable") || s.equals("offline") || s.equals("disconnected"))
            return L10n.tr("servers.status.offline", "Offline");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static int machineColor(LinkedEnvironmentView link) {
        if (link.reachable()) return Theme.GREEN;
        String s = link.status() == null ? "" : link.status().toLowerCase(Locale.ROOT);
        if (s.contains("error") || s.contains("fail")) return Theme.RED;
        if (s.contains("expir") || s.contains("pending") || s.contains("approv")) return Theme.AMBER;
        return Theme.FAINT;
    }

    /** Human label for a peer's access, or empty when it has full access. */
    static String accessLabel(LinkedEnvironmentView link) {
        if (link.readOnly()) return L10n.tr("servers.access.read_only", "Read-only");
        if ("approval-required".equals(link.access())) return L10n.tr("servers.access.asks_first", "Asks first");
        return "";
    }

    static String serverStatus(ConnectionProfileView profile) {
        return switch (profile.state()) {
            case CONNECTED -> L10n.tr("servers.status.connected", "Connected");
            case CONNECTING -> L10n.tr("servers.status.connecting", "Connecting");
            case ERROR -> L10n.tr("servers.status.error", "Can't connect");
            case DISCONNECTED -> profile.hasSavedSession() ? L10n.tr("servers.status.disconnected", "Not connected")
                    : L10n.tr("servers.status.signed_out", "Signed out");
        };
    }

    void setRows(List<Row> rows, @Nullable String cursorKey) {
        this.rows = List.copyOf(rows);
        this.tops.clear();
        this.selectable.clear();
        this.statusWidth = 0;
        int y = PAD;
        for (int i = 0; i < this.rows.size(); i++) {
            var row = this.rows.get(i);
            this.tops.add(y);
            if (!(row instanceof Note)) this.selectable.add(i);
            String status = switch (row) {
                case Server s -> serverStatus(s.profile());
                case Machine m -> machineStatus(m.link());
                case Note n -> "";
            };
            this.statusWidth = Math.max(this.statusWidth, Math.min(90, this.client.font.width(status)));
            y += height(row);
        }
        this.contentHeight = y + PAD;
        this.cursor = -1;
        if (cursorKey != null) {
            for (int s = 0; s < this.selectable.size(); s++)
                if (this.rows.get(this.selectable.get(s)).key().equals(cursorKey)) { this.cursor = s; break; }
        }
        this.setScroll(this.scroll);
    }

    static int height(Row row) {
        return switch (row) {
            case Server s -> s.host().isEmpty() ? SERVER_SINGLE_H : SERVER_H;
            case Machine m -> MACHINE_H;
            case Note n -> NOTE_H;
        };
    }

    static int contentHeight(List<Row> rows) {
        int h = PAD * 2;
        for (var row : rows) h += height(row);
        return h;
    }

    @Nullable Row cursorRow() {
        if (this.cursor < 0 || this.cursor >= this.selectable.size()) return null;
        return this.rows.get(this.selectable.get(this.cursor));
    }

    /** The server a row belongs to (itself for a server row). */
    @Nullable ConnectionProfileView ownerOf(@Nullable Row row) {
        return switch (row) {
            case null -> null;
            case Server s -> s.profile();
            case Machine m -> m.parent();
            case Note n -> null;
        };
    }

    void placeCursor(String key) {
        for (int s = 0; s < this.selectable.size(); s++)
            if (this.rows.get(this.selectable.get(s)).key().equals(key)) { this.cursor = s; this.ensureVisible(); return; }
    }

    void moveCursor(int delta) {
        if (this.selectable.isEmpty()) return;
        if (this.cursor < 0) this.cursor = delta > 0 ? 0 : this.selectable.size() - 1;
        else this.cursor = Math.clamp(this.cursor + delta, 0, this.selectable.size() - 1);
        this.ensureVisible();
        this.actions.cursorMoved();
    }

    void cursorToEdge(boolean end) {
        if (this.selectable.isEmpty()) return;
        this.cursor = end ? this.selectable.size() - 1 : 0;
        this.ensureVisible();
        this.actions.cursorMoved();
    }

    void page(int direction) {
        this.moveCursor(direction * Math.max(1, this.height / MACHINE_H - 1));
    }

    void ensureVisible() {
        if (this.cursor < 0) return;
        int index = this.selectable.get(this.cursor);
        int top = this.tops.get(index);
        int bottom = top + height(this.rows.get(index));
        // Keep a following note (an error or empty message) in view with its row.
        if (index + 1 < this.rows.size() && this.rows.get(index + 1) instanceof Note) bottom += NOTE_H;
        if (top - PAD < this.scroll) this.setScroll(top - PAD);
        else if (bottom + PAD > this.scroll + this.height) this.setScroll(bottom + PAD - this.height);
    }

    @Override
    protected int contentHeight() {
        return this.contentHeight;
    }

    @Override
    protected boolean onMouseClick(MouseButtonEvent event, boolean doubleClick) {
        this.doubleClick = doubleClick;
        return super.onMouseClick(event, doubleClick);
    }

    @Override
    protected boolean onContentClick(double mouseX, double contentY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
        for (int s = 0; s < this.selectable.size(); s++) {
            int index = this.selectable.get(s);
            int top = this.tops.get(index);
            var row = this.rows.get(index);
            if (contentY < top || contentY >= top + height(row)) continue;
            this.cursor = s;
            this.playDownSound();
            this.actions.cursorMoved();
            if (row instanceof Server server && server.expandable() && mouseX < this.getX() + PAD + CHEVRON_W + 2)
                this.actions.toggle(server);
            else if (this.doubleClick) this.actions.open(row);
            return true;
        }
        return false;
    }

    @Override
    protected void drawContent(SpruceGuiGraphics graphics, int top, int mouseX, int mouseY) {
        int left = this.getX() + PAD;
        int right = this.getEndX() - (this.maxScroll() > 0 ? 7 : PAD);
        boolean inside = this.isMouseHovered();
        Row cursorRow = this.cursorRow();
        for (int i = 0; i < this.rows.size(); i++) {
            var row = this.rows.get(i);
            int y = top + this.tops.get(i);
            int h = height(row);
            if (y + h < this.getY() || y > this.getEndY()) continue;
            boolean hovered = !(row instanceof Note) && inside && mouseY >= y && mouseY < y + h && mouseX >= left && mouseX < right;
            boolean selected = row == cursorRow;
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
            switch (row) {
                case Server s -> this.drawServer(graphics, s, left, right, y, h, hovered, selected, mouseX, mouseY);
                case Machine m -> this.drawMachine(graphics, m, left, right, y, h, hovered, selected, mouseX, mouseY);
                case Note n -> {
                    this.drawNote(graphics, n, left, right, y, h);
                    if (n.error() && inside && mouseY >= y && mouseY < y + h)
                        tooltip(graphics, n.text(), mouseX, mouseY);
                }
            }
        }
    }

    /** Row backdrop: cursor → selected rounded fill, hover → hover fill; rounded focus ring when focused. */
    private void backdrop(SpruceGuiGraphics graphics, int x0, int x1, int y, int h, boolean hovered, boolean selected) {
        int bg = selected ? (hovered ? Theme.SELECTED_HOVER : Theme.SELECTED) : hovered ? Theme.HOVER : 0;
        if (bg != 0) UiShapes.fillRounded(graphics, x0, y, x1 - x0, h - 1, UiShapes.R_CONTROL, bg);
        if (selected && this.isFocused()) UiShapes.outlineRounded(graphics, x0, y, x1 - x0, h - 1, UiShapes.R_CONTROL, Theme.ACCENT);
    }

    private void currentBar(SpruceGuiGraphics graphics, int x, int y, int h) {
        UiShapes.accentRail(graphics, x, y + 3, h - 7, Theme.ACCENT);
    }

    /** Draws a right-aligned "● Status" pair and returns its left edge; drops the words when room is tight. */
    private int status(SpruceGuiGraphics graphics, Font font, String label, int color, ConnectionState state, int right, int textY,
                       int room) {
        boolean words = room >= statusWidth + 72;
        int x = words ? right - statusWidth : right;
        if (words) graphics.text(font, Theme.ellipsize(font, label, statusWidth), x, textY,
                color == Theme.FAINT ? Theme.MUTED : Theme.TEXT_SOFT, false);
        UiIcons.connectionMark(graphics, x - 9, textY + 1, state, color);
        return x - 13;
    }

    private void drawServer(SpruceGuiGraphics graphics, Server s, int left, int right, int y, int h, boolean hovered,
                            boolean selected, int mouseX, int mouseY) {
        var font = this.client.font;
        this.backdrop(graphics, left, right, y, h, hovered, selected);
        if (s.current()) this.currentBar(graphics, left, y, h);
        int nameY = s.host().isEmpty() ? y + (h - 1 - font.lineHeight) / 2 + 1 : y + 4;
        if (s.expandable()) {
            boolean chevHot = hovered && mouseX < left + CHEVRON_W + 2;
            if (chevHot) UiShapes.pill(graphics, left + 3, nameY - 2, CHEVRON_W - 2, font.lineHeight + 3, Theme.BUTTON_HOVER, Theme.BORDER_SUBTLE);
            int cc = chevHot ? Theme.TEXT : Theme.MUTED;
            if (s.expanded()) UiIcons.caretDown(graphics, left + 7, nameY + 2, cc);
            else UiIcons.caretRight(graphics, left + 8, nameY + 1, cc);
        }
        int x = left + CHEVRON_W + 3;
        var p = s.profile();
        int statusLeft = this.status(graphics, font, serverStatus(p), Theme.connectionColor(p.state()),
                p.state(), right - 7, nameY, right - x);
        boolean dim = p.state() == ConnectionState.DISCONNECTED && !s.current();
        int available = statusLeft - x - 4;
        String shown = font.getSplitter().plainHeadByWidth(s.title(), Math.max(0, available), SERVER_NAME);
        if (!shown.equals(s.title()))
            shown = font.getSplitter().plainHeadByWidth(s.title(), Math.max(0, available - font.width(Component.literal("…").setStyle(SERVER_NAME))), SERVER_NAME) + "…";
        graphics.text(font, Component.literal(shown).setStyle(SERVER_NAME), x, nameY,
                dim ? Theme.TEXT_SOFT : Theme.TEXT, false);
        if (!s.host().isEmpty())
            graphics.text(font, Theme.ellipsize(font, s.host(), right - 7 - x), x, y + 15, Theme.MUTED, false);
        if (hovered && (!shown.equals(s.title()) || font.width(s.host()) > right - 7 - x))
            tooltip(graphics, s.title() + "\n" + p.endpoint(), mouseX, mouseY);
    }

    /** Tree connector: a vertical rule down from the parent's chevron and an elbow into this row. */
    private void connector(SpruceGuiGraphics graphics, int left, int y, int h, boolean last) {
        int cx = left + 9;
        int mid = y + h / 2;
        graphics.fill(cx, y, cx + 1, last ? mid + 1 : y + h, Theme.BORDER);
        graphics.fill(cx + 1, mid, cx + CHILD_INDENT - 2, mid + 1, Theme.BORDER);
    }

    private void drawMachine(SpruceGuiGraphics graphics, Machine m, int left, int right, int y, int h, boolean hovered,
                             boolean selected, int mouseX, int mouseY) {
        var font = this.client.font;
        int x0 = left + CHILD_INDENT + 4;
        this.connector(graphics, left, y, h, m.last());
        this.backdrop(graphics, x0, right, y, h, hovered, selected);
        if (m.current()) this.currentBar(graphics, x0, y, h);
        int textY = y + (h - 1 - font.lineHeight) / 2 + 1;
        int x = x0 + 7;
        var link = m.link();
        int mColor = machineColor(link);
        ConnectionState mState = link.reachable() ? ConnectionState.CONNECTED
                : mColor == Theme.RED ? ConnectionState.ERROR : ConnectionState.DISCONNECTED;
        int statusLeft = this.status(graphics, font, machineStatus(link), mColor, mState, right - 7, textY,
                right - x);
        String access = accessLabel(link);
        int nameRight = statusLeft - 5;
        if (!access.isEmpty() && nameRight - x > font.width(access) + 52) {
            int aw = font.width(access) + 8;
            int ax = nameRight - aw;
            boolean ro = link.readOnly();
            UiShapes.pill(graphics, ax, y + 3, aw, h - 7, ro ? Theme.AMBER_BG : Theme.BUTTON, ro ? Theme.AMBER_BORDER : Theme.BORDER);
            graphics.text(font, access, ax + 4, textY, ro ? Theme.AMBER : Theme.MUTED, false);
            nameRight = ax - 6;
        }
        int ink = !m.openable() ? Theme.MUTED : selected || hovered || m.current() ? Theme.TEXT : Theme.TEXT_SOFT;
        String shown = Theme.ellipsize(font, link.label(), nameRight - x);
        graphics.text(font, shown, x, textY, ink, false);
        if (hovered && !shown.equals(link.label())) tooltip(graphics, link.label(), mouseX, mouseY);
    }

    private void tooltip(SpruceGuiGraphics g, String text, int mouseX, int mouseY) {
        var font = this.client.font;
        g.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(text), Math.min(260, width - 12)), mouseX, mouseY);
    }

    private void drawNote(SpruceGuiGraphics graphics, Note n, int left, int right, int y, int h) {
        var font = this.client.font;
        this.connector(graphics, left, y, h, n.last());
        int x = left + CHILD_INDENT + 10;
        graphics.text(font, Theme.ellipsize(font, n.text(), right - x - 4), x, y + (h - font.lineHeight) / 2 + 1,
                n.error() ? Theme.RED : Theme.FAINT, false);
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        return switch (this.cursorRow()) {
            case null -> Component.literal(L10n.tr("servers.narration", "Servers"));
            case Server s -> Component.literal(L10n.tr("servers.narration.server", "Server %1$s, %2$s",
                    s.title(), serverStatus(s.profile()))
                    + (s.current() ? L10n.tr("servers.narration.current", ", current") : ""));
            case Machine m -> Component.literal(L10n.tr("servers.narration.machine", "Linked machine %1$s through %2$s, %3$s",
                    m.link().label(), m.parentTitle(), machineStatus(m.link()))
                    + (accessLabel(m.link()).isEmpty() ? "" : ", " + accessLabel(m.link()))
                    + (m.current() ? L10n.tr("servers.narration.current", ", current") : ""));
            case Note n -> Component.literal(n.text());
        };
    }
}
