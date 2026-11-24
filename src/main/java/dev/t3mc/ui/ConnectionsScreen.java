package dev.t3mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.lambdaurora.spruceui.tooltip.TooltipData;
import dev.t3mc.ClientController;
import dev.t3mc.L10n;
import dev.t3mc.ui.model.ConnectionProfileView;
import dev.t3mc.ui.model.ConnectionState;
import dev.t3mc.ui.model.EnvironmentDisplay;
import dev.t3mc.ui.model.ConnectionsView;
import dev.t3mc.ui.model.LinkedEnvironmentView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Compact environment browser: saved MCP servers with their linked machines nested below. The cursor row decides what
 * the primary button opens (its label names it); the accent bar and the "Now open" line show the current destination.
 */
final class ConnectionsScreen extends SpruceScreen {
    private static final int MAX_W = 396;
    private static final int HEAD = 40;   // title + destination line
    private static final int FOOT = 72;   // hint line + footer buttons
    private static final int EMPTY_H = 44;

    private final @Nullable Screen workspace;
    private final ClientController controller;
    private ConnectionsView view;
    private @Nullable ServerList list;
    private @Nullable FlatButton primary, forget, refresh;
    private FlatButton visibility, edit, disconnect;
    private @Nullable ConnectionIconButton icon;
    private static final List<String> ICONS = List.of("", "★", "◆", "●", "▲", "■", "♥", "☀");
    private @Nullable GuiEventListener nextFocus;
    private @Nullable String cursorKey;
    private double listScroll;
    private String confirmRemove = "";
    private int x, w, top, bottom, listTop, listBottom;

    ConnectionsScreen(@Nullable Screen workspace, ClientController controller) {
        super(Component.literal(L10n.tr("connections.title", "MCP servers")));
        this.workspace = workspace;
        this.controller = controller;
        this.view = controller.snapshot().settings().connections();
    }

    static String activeName(ConnectionsView view) {
        var root = view.activeProfile();
        if (root == null) return "T3 Code";
        var link = activeLink(view, root);
        return link == null ? ServerList.title(root) : ServerList.title(root) + " / " + link.label();
    }

    private static @Nullable LinkedEnvironmentView activeLink(ConnectionsView view, ConnectionProfileView root) {
        if (view.environmentId().isEmpty()) return null;
        for (var link : root.links()) if (link.id().equals(view.environmentId())) return link;
        return null;
    }

    @Override
    protected void init() {
        this.view = controller.snapshot().settings().connections();
        if (!confirmRemove.isEmpty() && view.profiles().stream().noneMatch(p -> p.id().equals(confirmRemove)))
            confirmRemove = "";
        List<ServerList.Row> rows = ServerList.rowsFor(view);

        w = Math.min(width - 16, MAX_W);
        x = (width - w) / 2;
        int maxList = Math.max(ServerList.SERVER_H, height - 62 - HEAD - FOOT);
        int listH = rows.isEmpty() ? EMPTY_H : Math.min(ServerList.contentHeight(rows), maxList);
        int panelH = HEAD + listH + FOOT;
        top = 58;
        bottom = top + panelH;
        listTop = top + HEAD;
        listBottom = listTop + listH;
        int left = x + 10, right = x + w - 10;

        int segPad = 2;
        int segW = right - left - segPad * 2;
        int half = (segW - 4) / 2;
        button(left + segPad, 30, half, L10n.tr("chat.workspace.servers", "Servers"), FlatButton.Kind.SELECTED, () -> {});
        button(left + segPad + half + 4, 30, segW - half - 4, L10n.tr("settings.other", "Other settings"),
                FlatButton.Kind.SUBTLE, () -> minecraft.gui.setScreen(new SettingsScreen(workspace, controller)));
        visibility = edit = disconnect = null;
        icon = null;
        list = null;
        if (!rows.isEmpty()) {
            list = new ServerList(Position.of(x + 4, listTop), w - 8, listH, new ServerList.Actions() {
                @Override public void cursorMoved() { syncCursor(); }
                @Override public void open(ServerList.Row row) { openRow(row); }
                @Override public void toggle(ServerList.Server server) { toggleRow(server); }
            });
            list.setRows(rows, cursorKey);
            if (list.cursorRow() == null) list.placeCursor(currentKey(rows));
            if (list.cursorRow() == null) list.moveCursor(1);
            list.setScroll(listScroll);
            list.ensureVisible();
            this.addRenderableWidget(list);
        }

        int footY = bottom - 8 - 19;
        primary = forget = refresh = null;
        if (!confirmRemove.isEmpty()) {
            if (list != null) list.setActive(false);
            String cancelLabel = L10n.tr("connections.cancel", "Cancel");
            String forgetLabel = L10n.tr("connections.forget", "Forget");
            int cancelW = buttonWidth(cancelLabel, 60), forgetW = buttonWidth(forgetLabel, 60);
            var cancel = button(right - forgetW - 4 - cancelW, footY, cancelW, cancelLabel, FlatButton.Kind.NORMAL, () -> {
                confirmRemove = "";
                rebuildFromButton();
            });
            button(right - forgetW, footY, forgetW, forgetLabel, FlatButton.Kind.DANGER, () -> {
                controller.removeProfile(confirmRemove);
                confirmRemove = "";
                cursorKey = null;
                rebuildFromButton();
            });
            this.setFocused(cancel);
            return;
        }
        if (rows.isEmpty()) {
            String closeLabel = L10n.tr("connections.close", "Close");
            String addLabel = WorkspaceScreen.stripLeadingPlus(L10n.tr("connections.add_server", "+ Add server"));
            int addW = buttonWidth(addLabel, 94) + 10;
            button(left, footY, buttonWidth(closeLabel, 60), closeLabel, FlatButton.Kind.NORMAL, this::onClose);
            this.setFocused(button(right - addW, footY, addW, addLabel, FlatButton.Kind.PRIMARY, this::addServer)
                    .withIcon(FlatButton.Icon.PLUS));
            return;
        }
        // Header button sized for the longer of its two labels so it doesn't jump while refreshing; keeps the title clear.
        int refreshW = Math.min(Math.max(buttonWidth(L10n.tr("connections.refresh", "Refresh links"), 84) + 10,
                buttonWidth(L10n.tr("connections.refreshing", "Refreshing…"), 84) + 10),
                right - left - font.width(this.getTitle().copy().withStyle(st -> st.withBold(true))) - 10);
        refresh = this.addRenderableWidget(new FlatButton(Position.of(right - refreshW, top + 6), refreshW, 17,
                L10n.tr("connections.refresh", "Refresh links"), b -> refreshSelected()).withIcon(FlatButton.Icon.REFRESH));
        String addLabel = WorkspaceScreen.stripLeadingPlus(L10n.tr("connections.add", "+ Add"));
        String forgetLabel = L10n.tr("connections.forget", "Forget");
        int addW = buttonWidth(addLabel, 54) + 10, forgetW = buttonWidth(forgetLabel, 54);
        button(left, footY, addW, addLabel, FlatButton.Kind.NORMAL, this::addServer)
                .withIcon(FlatButton.Icon.PLUS)
                .setTooltip(Component.literal(L10n.tr("connections.add.tooltip", "Add another T3 Code server")));
        forget = button(left + addW + 4, footY, forgetW, forgetLabel, FlatButton.Kind.NORMAL, () -> {
            var owner = list == null ? null : list.ownerOf(list.cursorRow());
            if (owner == null) return;
            confirmRemove = owner.id();
            rebuildFromButton();
        });
        int primaryLeft = left + addW + 4 + forgetW + 6;
        primary = button(primaryLeft, footY, right - primaryLeft, "", FlatButton.Kind.PRIMARY, () -> {
            var row = list == null ? null : list.cursorRow();
            if (row == null) onClose();
            else openRow(row);
        });
        int optionY = footY - 23;
        int quarter = (right - left - 12) / 4;
        visibility = button(left, optionY, quarter, "", FlatButton.Kind.NORMAL, () -> changeDisplay(false));
        icon = this.addRenderableWidget(new ConnectionIconButton(Position.of(left + quarter + 4, optionY), quarter, 19,
                b -> changeDisplay(true)));
        edit = button(left + 2 * (quarter + 4), optionY, quarter, L10n.tr("settings.edit", "Edit server"), FlatButton.Kind.NORMAL, () -> {
            if (list.cursorRow() instanceof ServerList.Server s)
                minecraft.gui.setScreen(new SettingsScreen(this, controller, false, s.profile()));
        });
        disconnect = button(left + 3 * (quarter + 4), optionY, right - left - 3 * (quarter + 4),
                L10n.tr("settings.disconnect", "Disconnect"), FlatButton.Kind.NORMAL, () -> controller.disconnect());
        syncCursor();
        if (list != null) this.setFocused(list);
    }

    private int buttonWidth(String label, int min) {
        return Math.max(min, font.width(label) + 12);
    }

    private FlatButton button(int left, int y, int width, String label, FlatButton.Kind kind, Runnable action) {
        return this.addRenderableWidget(new FlatButton(Position.of(left, y), width, 19, label, kind, b -> action.run()));
    }

    /** Key of the row the workspace points at now, so the cursor starts there. */
    private String currentKey(List<ServerList.Row> rows) {
        for (var row : rows) {
            if (row instanceof ServerList.Server s && s.current()) return s.key();
            if (row instanceof ServerList.Machine m && m.current()) return m.key();
        }
        return "s:" + view.activeProfileId();
    }

    /** Relabels the footer for the cursor row so it always says what it will open. */
    private void syncCursor() {
        if (list == null) return;
        var row = list.cursorRow();
        cursorKey = row == null ? null : row.key();
        listScroll = list.scroll();
        if (visibility != null) {
            var owner = list.ownerOf(row);
            boolean selectable = row instanceof ServerList.Server || row instanceof ServerList.Machine;
            var display = owner == null ? EnvironmentDisplay.DEFAULT : owner.display(environment(row));
            visibility.setLabel(display.hidden() ? L10n.tr("settings.hidden", "Hidden") : L10n.tr("settings.shown", "Visible"));
            visibility.setKind(display.hidden() ? FlatButton.Kind.NORMAL : FlatButton.Kind.SELECTED);
            visibility.setActive(selectable);
            icon.setMark(display.icon());
            icon.setActive(selectable);
            edit.setActive(row instanceof ServerList.Server);
            disconnect.setActive(row instanceof ServerList.Server s && s.profile().id().equals(view.activeProfileId())
                    && s.profile().state() != ConnectionState.DISCONNECTED);
        }
        if (refresh != null) {
            var owner = list.ownerOf(row);
            boolean busy = owner != null && owner.refreshingLinks();
            refresh.setLabel(busy ? L10n.tr("connections.refreshing", "Refreshing…") : L10n.tr("connections.refresh", "Refresh links"));
            refresh.setActive(owner != null && owner.linksAvailable() && owner.includeLinked()
                    && owner.state() == ConnectionState.CONNECTED && !busy);
            if (owner != null) refresh.setTooltip(Component.literal(
                    L10n.tr("connections.refresh.tooltip", "Refresh linked environments on %s", ServerList.title(owner))));
        }
        if (primary != null) {
            String label;
            boolean active = true;
            String tip;
            switch (row) {
                case null -> { label = L10n.tr("connections.back", "Back to workspace"); tip = ""; }
                case ServerList.Server s -> {
                    var p = s.profile();
                    label = switch (p.state()) {
                        case ERROR -> L10n.tr("connections.reconnect", "Reconnect %s", s.title());
                        case DISCONNECTED -> L10n.tr("connections.connect", "Connect to %s", s.title());
                        default -> L10n.tr("connections.open", "Open %s", s.title());
                    };
                    tip = s.current() && p.state() == ConnectionState.CONNECTED
                            ? L10n.tr("connections.open.current.tooltip", "Return to this workspace.")
                            : L10n.tr("connections.open.server.tooltip", "Open this server's own workspace.");
                }
                case ServerList.Machine m -> {
                    if (m.openable()) {
                        label = L10n.tr("connections.open", "Open %s", m.link().label());
                        tip = L10n.tr("connections.open.machine.tooltip", "Open %1$s through %2$s.",
                                m.link().label(), m.parentTitle());
                    } else {
                        active = false;
                        label = m.parent().state() != ConnectionState.CONNECTED
                                ? L10n.tr("connections.connect_first", "Connect %s first", m.parentTitle())
                                : L10n.tr("connections.machine_status", "%1$s is %2$s", m.link().label(),
                                        ServerList.machineStatus(m.link()).toLowerCase(Locale.ROOT));
                        tip = "";
                    }
                }
                case ServerList.Note n -> { label = L10n.tr("connections.back", "Back to workspace"); tip = ""; }
            }
            primary.setLabel(label);
            primary.setActive(active);
            if (tip.isEmpty()) primary.setTooltip(TooltipData.EMPTY);
            else primary.setTooltip(Component.literal(tip));
        }
        if (forget != null) {
            var owner = list.ownerOf(row);
            forget.setActive(owner != null);
            if (owner == null) forget.setTooltip(TooltipData.EMPTY);
            else forget.setTooltip(Component.literal(L10n.tr("connections.forget.tooltip",
                    "Remove %s and its saved login from Minecraft", ServerList.title(owner))));
        }
    }

    private static String environment(ServerList.Row row) {
        return row instanceof ServerList.Machine m ? m.link().id() : "";
    }

    private void changeDisplay(boolean cycleIcon) {
        var row = list.cursorRow();
        var owner = list.ownerOf(row);
        if (owner == null) return;
        String environment = environment(row);
        var display = owner.display(environment);
        var next = cycleIcon
                ? new EnvironmentDisplay(display.hidden(), ICONS.get((ICONS.indexOf(display.icon()) + 1) % ICONS.size()))
                : new EnvironmentDisplay(!display.hidden(), display.icon());
        controller.setDisplay(owner.id(), environment, next);
    }

    private void openRow(ServerList.Row row) {
        if (!confirmRemove.isEmpty()) return;
        switch (row) {
            case ServerList.Server s -> {
                var p = s.profile();
                // Already the destination: just go back (the workspace shows its connecting state).
                if (s.current() && (p.state() == ConnectionState.CONNECTED || p.state() == ConnectionState.CONNECTING)) {
                    onClose();
                    return;
                }
                boolean wasConnected = p.state() == ConnectionState.CONNECTED;
                if (!controller.selectProfile(p.id())) return;
                // Switching to a live server lands straight in it; otherwise stay to watch it connect.
                if (wasConnected) onClose();
            }
            case ServerList.Machine m -> {
                if (m.openable() && controller.selectEnvironment(m.parent().id(), m.link().id())) onClose();
            }
            case ServerList.Note n -> {}
        }
    }

    private void toggleRow(ServerList.Server server) {
        if (!confirmRemove.isEmpty()) return;
        cursorKey = server.key();
        controller.setLinksExpanded(server.profile().id(), !server.expanded());
    }

    private void refreshSelected() {
        var owner = list == null ? null : list.ownerOf(list.cursorRow());
        if (owner != null && refresh != null && refresh.isActive())
            controller.refreshLinkedEnvironments(owner.id());
    }

    private void addServer() {
        minecraft.gui.setScreen(new SettingsScreen(this, controller, true, null));
    }

    private void rebuildFromButton() {
        rebuildWidgets();
        // Spruce assigns mouse focus to the clicked widget after its action returns.
        nextFocus = getFocused();
    }

    private void restoreButtonFocus() {
        if (nextFocus != null && children().contains(nextFocus) && !children().contains(getFocused()))
            setFocused(nextFocus);
        nextFocus = null;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        restoreButtonFocus();
        int key = event.key();
        if (!confirmRemove.isEmpty()) {
            if (event.isEscape()) {
                confirmRemove = "";
                rebuildWidgets();
                return true;
            }
            return super.keyPressed(event);
        }
        if (list == null) return super.keyPressed(event);
        boolean listFocused = this.getFocused() == list || this.getFocused() == null;
        switch (key) {
            case InputConstants.KEY_UP, InputConstants.KEY_DOWN -> {
                this.setFocused(list);
                list.moveCursor(key == InputConstants.KEY_UP ? -1 : 1);
                return true;
            }
            case InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN -> {
                list.page(key == InputConstants.KEY_PAGEUP ? -1 : 1);
                return true;
            }
            case InputConstants.KEY_HOME, InputConstants.KEY_END -> {
                list.cursorToEdge(key == InputConstants.KEY_END);
                return true;
            }
            default -> {}
        }
        if (!listFocused) return super.keyPressed(event);
        var row = list.cursorRow();
        switch (key) {
            case InputConstants.KEY_RIGHT -> {
                if (row instanceof ServerList.Server s && s.expandable() && !s.expanded()) toggleRow(s);
                else if (row instanceof ServerList.Server s && s.expanded()) list.moveCursor(1);
                return true;
            }
            case InputConstants.KEY_LEFT -> {
                if (row instanceof ServerList.Server s && s.expanded()) toggleRow(s);
                else if (row != null && !(row instanceof ServerList.Server)) {
                    var owner = list.ownerOf(row);
                    if (owner != null) { list.placeCursor("s:" + owner.id()); syncCursor(); }
                }
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_SPACE -> {
                if (row == null) onClose();
                else openRow(row);
                return true;
            }
            case InputConstants.KEY_R -> {
                refreshSelected();
                return true;
            }
            case InputConstants.KEY_DELETE, InputConstants.KEY_BACKSPACE -> {
                var owner = list.ownerOf(row);
                if (owner != null) { confirmRemove = owner.id(); rebuildWidgets(); }
                return true;
            }
            default -> { return super.keyPressed(event); }
        }
    }

    @Override
    public void tick() {
        super.tick();
        restoreButtonFocus();
        if (list != null) listScroll = list.scroll();
        var next = controller.snapshot().settings().connections();
        if (!view.equals(next)) {
            // A discovery reply can arrive while scrolling or tabbing through the footer.
            var focus = getFocused();
            boolean primaryFocused = primary != null && focus == primary;
            String footerLabel = focus instanceof FlatButton button ? button.getMessage().getString() : "";
            double scrollBefore = listScroll;
            rebuildWidgets();
            if (list != null) list.setScroll(scrollBefore);
            if (primaryFocused && primary != null && primary.isActive()) setFocused(primary);
            else if (!footerLabel.isEmpty()) {
                for (var child : children()) {
                    if (child instanceof FlatButton button && button.isActive() && footerLabel.equals(button.getMessage().getString())) {
                        setFocused(button);
                        break;
                    }
                }
            }
        }
    }

    @Override public boolean isPauseScreen() { return workspace != null && workspace.isPauseScreen(); }
    @Override public void onClose() { minecraft.gui.setScreen(workspace); }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (Theme.seeThrough()) Theme.seeThroughBackdrop(minecraft, g, width, height);
        else super.extractBackground(g, mx, my, delta);
        g.fill(0, 0, width, height, Theme.BG);
        // Segmented tab bar shell
        int left = x + 10, right = x + w - 10;
        UiShapes.panel(g, left, 28, right - left, 23, UiShapes.R_CONTROL, Theme.INPUT, Theme.BORDER, false);
        // Sculpted main card
        UiShapes.panel(g, x, top, w, bottom - top, UiShapes.R_PANEL, Theme.PANEL_SURFACE, Theme.BORDER_STRONG, true);
        g.fill(x + 2, listTop - 3, x + w - 2, listTop - 2, Theme.BORDER_SUBTLE);
        g.fill(x + 2, listBottom + 2, x + w - 2, listBottom + 3, Theme.BORDER_SUBTLE);
    }

    @Override
    public void extractRenderState(SpruceGuiGraphics g, int mx, int my, float delta) {
        int left = x + 10, right = x + w - 10;
        var heading = Component.literal(L10n.tr("settings.title", "T3 Code Settings")).withStyle(style -> style.withBold(true));
        Theme.plate(g, left, 10, font.width(heading), font.lineHeight, Theme.TEXT_PLATE);
        g.text(font, heading, left, 10, Theme.TEXT, false);
        g.text(font, this.getTitle().copy().withStyle(s -> s.withBold(true)), left, top + 10, Theme.TEXT, false);
        drawDestination(g, left, right, top + 24);

        if (list == null) {
            g.text(font, Theme.ellipsize(font, L10n.tr("connections.empty", "No servers yet"), right - left),
                    left, listTop + 8, Theme.TEXT, false);
            g.text(font, Theme.ellipsize(font, L10n.tr("connections.empty.help",
                            "Add a T3 Code server to open its workspace here."), right - left),
                    left, listTop + 21, Theme.MUTED, false);
        }

        int hintY = listBottom + 6;
        String hint;
        int hintColor = Theme.FAINT;
        if (!confirmRemove.isEmpty()) {
            String name = view.profiles().stream().filter(p -> p.id().equals(confirmRemove)).findFirst()
                    .map(ServerList::title).orElse(null);
            hint = name == null ? L10n.tr("connections.forget.confirm.unknown", "Forget this server and its saved login on this device?")
                    : L10n.tr("connections.forget.confirm", "Forget %s and its saved login on this device?", name);
            hintColor = Theme.TEXT;
        } else hint = hintFor(list == null ? null : list.cursorRow());
        if (!hint.isEmpty()) g.text(font, Theme.ellipsize(font, hint, right - left), left, hintY, hintColor, false);

        super.extractRenderState(g, mx, my, delta);
    }

    /** "Now open  Cedar Workshop › Signal Studio  [Read-only]", so the current destination stays visible. */
    private void drawDestination(SpruceGuiGraphics g, int left, int right, int y) {
        var root = view.activeProfile();
        if (root == null || root.state() == ConnectionState.DISCONNECTED) {
            if (root != null) g.text(font, L10n.tr("connections.destination.none", "No workspace open"), left, y, Theme.MUTED, false);
            return;
        }
        String lead = switch (root.state()) {
            case ERROR -> L10n.tr("connections.destination.error", "Connection error");
            case CONNECTING -> L10n.tr("connections.destination.connecting", "Connecting to");
            default -> L10n.tr("connections.destination.open", "Now open");
        } + "   ";
        g.text(font, lead, left, y, Theme.MUTED, false);
        int x0 = left + font.width(lead);
        var link = activeLink(view, root);
        boolean ro = view.readOnly();
        String chip = ro ? L10n.tr("servers.access.read_only", "Read-only")
                : link != null && "approval-required".equals(link.access()) ? L10n.tr("servers.access.asks_first", "Asks first") : "";
        int chipW = chip.isEmpty() ? 0 : font.width(chip) + 6;
        int room = right - x0 - (chipW == 0 ? 0 : chipW + 6);
        String rootName = ServerList.title(root);
        int cx;
        if (link == null && !view.environmentId().isEmpty()) {
            String text = Theme.ellipsize(font, rootName + " › " + L10n.tr("connections.destination.linked", "linked machine"), room);
            g.text(font, text, x0, y, Theme.TEXT, false);
            cx = x0 + font.width(text);
        } else if (link == null) {
            String text = Theme.ellipsize(font, rootName, room);
            g.text(font, text, x0, y, Theme.TEXT, false);
            cx = x0 + font.width(text);
        } else {
            // Keep the machine name readable; shorten the server name first.
            String sep = " › ";
            int peerW = Math.min(font.width(link.label()), room * 3 / 5);
            String parent = Theme.ellipsize(font, rootName, room - peerW - font.width(sep));
            g.text(font, parent, x0, y, Theme.MUTED, false);
            int px = x0 + font.width(parent);
            g.text(font, sep, px, y, Theme.FAINT, false);
            px += font.width(sep);
            String peer = Theme.ellipsize(font, link.label(), right - px - (chipW == 0 ? 0 : chipW + 6));
            g.text(font, peer, px, y, Theme.TEXT, false);
            cx = px + font.width(peer);
        }
        if (chipW > 0) {
            int ax = cx + 6;
            UiShapes.pill(g, ax, y - 2, chipW, 12, ro ? Theme.AMBER_BG : Theme.BUTTON, ro ? Theme.AMBER_BORDER : Theme.BORDER);
            g.text(font, chip, ax + 3, y, ro ? Theme.AMBER : Theme.MUTED, false);
        }
    }

    private String hintFor(@Nullable ServerList.Row row) {
        return switch (row) {
            case null -> "";
            case ServerList.Server s -> {
                var p = s.profile();
                if (s.expandable() && !s.expanded())
                    yield L10n.tr("connections.hint.expand", "%s  (→ shows linked machines)", p.endpoint());
                yield p.endpoint();
            }
            case ServerList.Machine m -> {
                if (m.link().readOnly())
                    yield L10n.tr("connections.hint.linked.read_only", "Linked through %s, read-only", m.parentTitle());
                if ("approval-required".equals(m.link().access()))
                    yield L10n.tr("connections.hint.linked.approval", "Linked through %s, actions ask for approval", m.parentTitle());
                yield L10n.tr("connections.hint.linked", "Linked through %s", m.parentTitle());
            }
            case ServerList.Note n -> n.text();
        };
    }

    /** Cycles the connection mark while rendering built-in marks as crisp pixel icons inline with the localized label. */
    private static final class ConnectionIconButton extends FlatButton {
        private String mark = "";

        ConnectionIconButton(Position position, int width, int height, PressAction action) {
            super(position, width, height, "", Kind.NORMAL, action);
        }

        void setMark(String mark) {
            this.mark = mark == null ? "" : mark;
            this.setLabel(L10n.tr("settings.icon", "Icon: %s", this.mark.isEmpty() ? "–" : this.mark));
        }

        @Override
        protected void extractText(SpruceGuiGraphics graphics, int color) {
            if (!UiIcons.isConnectionMark(this.mark)) {
                super.extractText(graphics, color);
                return;
            }
            String template = L10n.tr("settings.icon", "Icon: %s", "\u0000");
            int split = template.indexOf('\u0000');
            if (split < 0) {
                super.extractText(graphics, color);
                return;
            }
            String before = template.substring(0, split);
            String after = template.substring(split + 1);
            var font = this.client.font;
            int pressOffset = this.isPressedState() ? 1 : 0;
            int textInk = this.isActive() ? Theme.BUTTON_TEXT : Theme.TEXT_DISABLED;
            int markInk = this.isActive() ? Theme.ACCENT : Theme.TEXT_DISABLED;
            int maxText = Math.max(0, this.getWidth() - 8 - UiIcons.MARK_SIZE);
            String shownBefore = Theme.ellipsize(font, before, maxText);
            String shownAfter = after.isEmpty() ? "" : Theme.ellipsize(font, after, Math.max(0, maxText - font.width(shownBefore)));
            int totalW = font.width(shownBefore) + UiIcons.MARK_SIZE + font.width(shownAfter);
            int x = this.getX() + (this.getWidth() - totalW) / 2;
            int textY = this.getY() + (this.getHeight() - font.lineHeight) / 2 + 1 + pressOffset;
            int iconY = this.getY() + (this.getHeight() - UiIcons.MARK_SIZE) / 2 + pressOffset;
            if (!shownBefore.isEmpty()) {
                graphics.text(font, shownBefore, x, textY, textInk, false);
                x += font.width(shownBefore);
            }
            UiIcons.drawConnectionMark(graphics, this.mark, x, iconY, markInk);
            x += UiIcons.MARK_SIZE;
            if (!shownAfter.isEmpty()) {
                graphics.text(font, shownAfter, x, textY, textInk, false);
            }
        }
    }
}
