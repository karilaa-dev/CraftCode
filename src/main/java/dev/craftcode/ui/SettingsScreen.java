package dev.craftcode.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.craftcode.ClientController;
import dev.craftcode.L10n;
import dev.craftcode.CraftCodeClient;
import dev.craftcode.auth.T3OAuth;
import dev.craftcode.ui.model.ColorTheme;
import dev.craftcode.ui.model.ConnectionProfileView;
import dev.craftcode.ui.model.ConnectionState;
import dev.craftcode.ui.model.NotificationMode;
import dev.craftcode.ui.model.SettingsUpdate;
import dev.craftcode.ui.model.SettingsView;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Connection, notification, theme and shortcut settings.
 *
 * <p>Theme and transparency apply and save immediately, independent of <i>Save &amp; connect</i>. <i>Customize…</i>
 * opens {@link ThemeEditorScreen}; the connection drafts (including the masked code) survive the round trip.
 *
 * <p>One connection form: a T3 URL (a T3 server or the MCP URL copied from T3 Code) and an optional connection code.
 * A blank code reuses the saved login for the same endpoint or signs in through the browser; a supplied code pairs
 * directly. The code is masked, never echoed back, and cleared once submitted or when the screen is left.
 */
public class SettingsScreen extends SpruceScreen {
    private record Note(int x, int y, String text, int color) {}

    private record Swatch(int x, int y, int color) {}

    private final @Nullable Screen parent;
    private final ClientController controller;
    private final SettingsView saved;

    private final boolean adding;
    private final boolean serverForm;
    private String profileName = "";
    private String url;
    private String connectionCode = "";
    private @Nullable String inviteEndpoint;
    private NotificationMode notificationMode;
    private String error = "";
    private boolean capturingKey;
    private boolean openingChild;

    private final List<Note> notes = new ArrayList<>();
    private final List<Note> validationNotes = new ArrayList<>();
    private final List<Swatch> swatches = new ArrayList<>();
    private @Nullable FlatButton keyButton;
    private @Nullable PercentSlider transparencySlider;
    private int panelX;
    private int panelW;
    private int panelBottom;

    public SettingsScreen(@Nullable Screen parent, ClientController controller) {
        this(parent, controller, false, null);
    }

    SettingsScreen(@Nullable Screen parent, ClientController controller, boolean adding, @Nullable ConnectionProfileView profile) {
        super(Component.literal(adding ? L10n.tr("settings.title.add", "Add MCP server")
                : profile != null ? L10n.tr("settings.edit", "Edit server") : L10n.tr("settings.title", "CraftCode Settings")));
        this.adding = adding;
        this.serverForm = adding || profile != null;
        if (profile != null) this.profileName = profile.name();
        this.parent = parent;
        this.controller = controller;
        this.saved = controller.snapshot().settings();
        this.url = profile == null ? "" : profile.endpoint();
        this.notificationMode = this.saved.notificationMode();
        Theme.sync(this.saved);
        Theme.setTransparency(this.saved.transparency());
    }

    @Override
    protected void init() {
        this.openingChild = false;
        this.notes.clear();
        this.validationNotes.clear();
        this.swatches.clear();
        this.panelW = Math.min(396, this.width - 16);
        this.panelX = (this.width - this.panelW) / 2;
        // Roomy layouts allow 3-line help and a 4-line error; below 360px those shrink to one/two lines, and below
        // 270px (e.g. 320×240) rows also tighten so every control and the action row stay on screen with an error.
        boolean roomy = this.height >= 360;
        boolean tight = this.height < 270;
        int rowGap = roomy ? 9 : tight ? 3 : 5;
        int sectionGap = tight ? 0 : 2;
        int noteLines = roomy ? 3 : 1;
        // Labels column fits the widest label in the current language (Russian runs longer than English).
        int labelW = 78;
        for (String l : new String[] {
                L10n.tr("settings.name", "Name"), L10n.tr("settings.url", "T3 URL"), L10n.tr("settings.code", "Code"),
                L10n.tr("settings.notify", "Notify via"), L10n.tr("settings.theme", "Theme"),
                L10n.tr("settings.transparency", "Transparency"), L10n.tr("settings.shortcut", "Shortcut")})
            labelW = Math.max(labelW, this.font.width(l) + 8);
        labelW = Math.min(labelW, (this.panelW - 24) / 3);
        int x = this.panelX + 10;
        int w = this.panelW - 20;
        int fx = x + labelW;
        int fw = w - labelW;
        int y = 30;

        if (this.serverForm) {
            buildServerForm(y + 4, rowGap, sectionGap, roomy, fx, fw, x, w, noteLines);
            return;
        }
        int segPad = 2;
        int segW = w - segPad * 2;
        int half = (segW - 4) / 2;
        this.addRenderableWidget(new FlatButton(Position.of(x + segPad, y), half, 19, L10n.tr("chat.workspace.servers", "Servers"),
                FlatButton.Kind.SUBTLE, b -> minecraft.gui.setScreen(new ConnectionsScreen(parent, controller))));
        this.addRenderableWidget(new FlatButton(Position.of(x + segPad + half + 4, y), segW - half - 4, 19,
                L10n.tr("settings.other", "Other settings"), FlatButton.Kind.SELECTED, b -> {}));
        y = 66;

        // Notifications
        this.labelRow(y, L10n.tr("settings.notify", "Notify via"));
        int third = (fw - 8) / 3;
        int bx = fx;
        for (var mode : NotificationMode.values()) {
            String label = switch (mode) {
                case CHAT -> L10n.tr("settings.notify.chat", "Chat");
                case POPUP -> L10n.tr("settings.notify.popup", "Popup");
                case OFF -> L10n.tr("settings.notify.off", "Off");
            };
            int bw = mode == NotificationMode.OFF ? fw - (third + 4) * 2 : third;
            this.addRenderableWidget(new FlatButton(Position.of(bx, y), bw, 19, label,
                    this.notificationMode == mode ? FlatButton.Kind.SELECTED : FlatButton.Kind.NORMAL, b -> {
                if (!controller.setNotifications(mode)) {
                    fail(L10n.tr("settings.write_error", "Could not save settings."));
                    return;
                }
                this.notificationMode = mode;
                this.rebuildKeepingFocus();
            }));
            bx += bw + 4;
        }
        y += 19 + 4;
        String notifyHelp = switch (this.notificationMode) {
            case CHAT -> L10n.tr("settings.notify.chat.help", "Agent updates appear as [CraftCode] lines in chat.");
            case POPUP -> L10n.tr("settings.notify.popup.help", "Agent updates appear as toasts.");
            case OFF -> L10n.tr("settings.notify.off.help", "No in-game notifications.");
        };
        if (roomy) y = this.wrapNote(y, fx, notifyHelp, Theme.FAINT, fw, noteLines);
        y += rowGap - 1;

        // Theme: ‹ [name + swatches] › [Customize…] — the arrows cycle the presets (and Custom once one is saved),
        // applying and saving right away; Customize opens the color editor on the current palette.
        this.labelRow(y, L10n.tr("settings.theme", "Theme"));
        var settings = this.controller.snapshot().settings();
        var theme = settings.theme();
        var themes = this.quickThemes(settings);
        int arrowW = 19;
        int midW = fw - (arrowW + 4) * 2;
        String customizeLabel = L10n.tr("settings.theme.customize", "Customize…");
        int customizeW = Math.min(this.buttonWidth(customizeLabel, 66), fw / 3);
        int themeMidW = midW - customizeW - 4;
        int index = Math.max(0, themes.indexOf(theme));
        var prev = new FlatButton(Position.of(fx, y), arrowW, 19, "", b -> this.cycleTheme(-1))
                .withIcon(FlatButton.Icon.CHEVRON_LEFT);
        prev.setTooltip(Component.literal(themes.get(Math.floorMod(index - 1, themes.size())).label()));
        this.addRenderableWidget(prev);
        String label = L10n.tr("settings.theme.position", "%1$s  %2$s/%3$s", theme.label(), index + 1, themes.size());
        if (this.font.width(label) + 10 > themeMidW) label = theme.label();
        var current = new FlatButton(Position.of(fx + arrowW + 4, y), themeMidW, 19, label, FlatButton.Kind.SELECTED,
                b -> this.cycleTheme(1));
        current.setTooltip(Component.literal(L10n.tr("settings.theme.tooltip", "Click or use ‹ › to switch themes.")));
        this.addRenderableWidget(current);
        var next = new FlatButton(Position.of(fx + arrowW + 4 + themeMidW + 4, y), arrowW, 19, "", b -> this.cycleTheme(1))
                .withIcon(FlatButton.Icon.CHEVRON_RIGHT);
        next.setTooltip(Component.literal(themes.get(Math.floorMod(index + 1, themes.size())).label()));
        this.addRenderableWidget(next);
        var customize = new FlatButton(Position.of(fx + fw - customizeW, y), customizeW, 19, customizeLabel,
                b -> this.openThemeEditor());
        customize.setTooltip(Component.literal(L10n.tr("settings.theme.customize.tooltip",
                "Edit every theme color, starting from the current one, and save it as your Custom theme.")));
        this.addRenderableWidget(customize);
        var palette = settings.effectivePalette();
        int sx = fx + arrowW + 4 + 6;
        if (themeMidW >= this.font.width(label) + 74) for (int color : new int[] {palette.canvas(), palette.accent(), palette.userBubble()}) {
            this.swatches.add(new Swatch(sx, y + 5, 0xFF000000 | color));
            sx += 11;
        }
        y += 19 + rowGap + sectionGap;

        // Transparency: − [slider] +, 5% steps. Previews live (the screen behind follows it) and saves right away.
        this.labelRow(y, L10n.tr("settings.transparency", "Transparency"));
        var less = new FlatButton(Position.of(fx, y), arrowW, 20, "", b -> this.stepTransparency(-Theme.TRANSPARENCY_STEP))
                .withIcon(FlatButton.Icon.MINUS);
        less.setTooltip(Component.literal(L10n.tr("settings.transparency.less", "More solid (−%s)",
                Theme.TRANSPARENCY_STEP + "%")));
        this.addRenderableWidget(less);
        this.transparencySlider = this.addRenderableWidget(new PercentSlider(Position.of(fx + arrowW + 4, y), midW, 20,
                Theme.transparency(), Theme.TRANSPARENCY_STEP, this::applyTransparency));
        this.transparencySlider.setTooltip(Component.literal(L10n.tr("settings.transparency.tooltip",
                "How much of the game shows through CraftCode. %1$s is solid.\nClick or drag; ←/→ change it by %2$s.",
                "0%", Theme.TRANSPARENCY_STEP + "%")));
        var more = new FlatButton(Position.of(fx + fw - arrowW, y), arrowW, 20, "", b -> this.stepTransparency(Theme.TRANSPARENCY_STEP))
                .withIcon(FlatButton.Icon.PLUS);
        more.setTooltip(Component.literal(L10n.tr("settings.transparency.more", "More see-through (+%s)",
                Theme.TRANSPARENCY_STEP + "%")));
        this.addRenderableWidget(more);
        y += 20 + rowGap + sectionGap;

        // Workspace shortcut: the same vanilla key mapping shown in Options → Controls → Key Binds.
        this.labelRow(y, L10n.tr("settings.shortcut", "Shortcut"));
        String controlsLabel = L10n.tr("settings.shortcut.controls", "Controls…");
        int controlsW = Math.min(this.buttonWidth(controlsLabel, 60), fw / 2);
        int keyW = fw - controlsW - 4;
        this.keyButton = this.addRenderableWidget(new FlatButton(Position.of(fx, y), keyW, 19, this.keyLabel(),
                this.capturingKey ? FlatButton.Kind.PRIMARY : FlatButton.Kind.NORMAL, b -> this.toggleKeyCapture()));
        this.keyButton.setTooltip(Component.literal(this.capturingKey
                ? L10n.tr("settings.shortcut.capturing.tooltip", "Press a key to open CraftCode. Esc cancels.")
                : L10n.tr("settings.shortcut.tooltip", "Click, then press a key to open CraftCode.")));
        var controls = new FlatButton(Position.of(fx + keyW + 4, y), controlsW, 19, controlsLabel, b -> this.openKeyBinds());
        controls.setTooltip(Component.literal(L10n.tr("settings.shortcut.controls.tooltip", "Open Minecraft's key binds")));
        this.addRenderableWidget(controls);
        y += 19 + rowGap + sectionGap;

        if (!this.error.isEmpty())
            y = this.wrapNote(this.validationNotes, y, x, this.error, Theme.RED, w, roomy ? 4 : 2) + sectionGap;

        // Actions
        String done = L10n.tr("settings.done", "Done");
        int doneW = buttonWidth(done, 68);
        this.addRenderableWidget(new FlatButton(Position.of(x + w - doneW, y), doneW, 20, done, FlatButton.Kind.PRIMARY, thisButton -> onClose()));
        this.panelBottom = Math.min(y + 30, height);
    }

    private void buildServerForm(int y, int rowGap, int sectionGap, boolean roomy, int fx, int fw, int x, int w, int noteLines) {
        this.labelRow(y, L10n.tr("settings.name", "Name"));
        var name = new InputField(Position.of(fx, y), fw, L10n.tr("settings.name.narration", "Server name"),
                L10n.tr("settings.name.placeholder", "Optional server name"));
        name.setText(this.profileName);
        name.setChangedListener(text -> this.profileName = text);
        this.addRenderableWidget(name);
        y += 19 + rowGap;

        // Connection: one T3 URL plus an optional, masked connection code.
        this.labelRow(y, L10n.tr("settings.url", "T3 URL"));
        var url = new InputField(Position.of(fx, y), fw, L10n.tr("settings.url", "T3 URL"),
                L10n.tr("settings.url.placeholder", "T3 connection link or server URL"));
        url.setText(this.url);
        url.onEnter(this::save);
        InputField first = this.addRenderableWidget(url);
        y += 19 + rowGap;

        this.labelRow(y, L10n.tr("settings.code", "Code"));
        var code = new InputField(Position.of(fx, y), fw, L10n.tr("settings.code.narration", "Connection code"),
                L10n.tr("settings.code.placeholder", "Optional connection code")).masked();
        code.setText(this.connectionCode);
        code.setChangedListener(text -> {
            this.connectionCode = text;
            this.inviteEndpoint = null;
            this.clearValidation();
        });
        url.setChangedListener(text -> {
            this.url = text;
            if (this.inviteEndpoint != null && !this.inviteEndpoint.equals(text)) code.setText("");
            this.clearValidation();
        });
        // Move a pasted invite's secret out of the visible URL before the next frame.
        url.afterPaste(() -> {
            try {
                var invite = T3OAuth.connectionAddress(this.url);
                if (!invite.code().isEmpty()) {
                    url.setText(invite.endpoint());
                    code.setText(invite.code());
                    this.inviteEndpoint = invite.endpoint();
                }
            } catch (IOException ignored) { /* The player can finish editing before Save validates it. */ }
        });
        code.onEnter(this::save);
        this.addRenderableWidget(code);
        y += 19 + 3;
        String help = !this.adding && this.saved.hasSavedSession()
                ? L10n.tr("settings.code.help.saved",
                        "Leave the code blank to keep the saved login, or to sign in with your browser for a new URL.")
                : L10n.tr("settings.code.help",
                        "Leave the code blank to sign in with your browser, or paste a connection code from T3 Code.");
        if (!roomy) code.setTooltip(Component.literal(help));
        y = this.wrapNote(y, fx, help, Theme.FAINT, fw, noteLines);
        y += rowGap + sectionGap;

        if (!this.error.isEmpty()) y = this.wrapNote(this.validationNotes, y, x, this.error, Theme.RED, w, 2) + rowGap;
        String connect = L10n.tr("settings.connect", "Connect");
        String cancel = L10n.tr("settings.cancel", "Cancel");
        int connectW = this.buttonWidth(connect, 100);
        int cancelW = this.buttonWidth(cancel, 56);
        this.addRenderableWidget(new FlatButton(Position.of(x + w - connectW, y), connectW, 20, connect, FlatButton.Kind.PRIMARY, b -> this.save()));
        this.addRenderableWidget(new FlatButton(Position.of(x + w - connectW - 4 - cancelW, y), cancelW, 20, cancel, b -> this.onClose()));
        this.panelBottom = y + 30;
        this.setFocused(first);
    }

    /** Width for a button label: at least {@code min}, wider when the translated label needs it. */
    private int buttonWidth(String label, int min) {
        return Math.max(min, this.font.width(label) + 12);
    }

    private void labelRow(int fieldY, String text) {
        this.notes.add(new Note(this.panelX + 10, fieldY + 5, text, Theme.MUTED));
    }

    private int wrapNote(int y, int x, String text, int color, int width, int maxLines) {
        return this.wrapNote(this.notes, y, x, text, color, width, maxLines);
    }

    private int wrapNote(List<Note> target, int y, int x, String text, int color, int width, int maxLines) {
        var lines = this.font.splitIgnoringLanguage(Component.literal(text), width);
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            String line = lines.get(i).getString();
            if (i == maxLines - 1 && lines.size() > maxLines) {
                line = Theme.ellipsize(this.font, line + " " + lines.get(i + 1).getString(), width);
            }
            target.add(new Note(x, y, line, color));
            y += this.font.lineHeight + 2;
        }
        return y;
    }

    // ---------------------------------------------------------------- shortcut

    private static @Nullable KeyMapping workspaceKey() {
        return CraftCodeClient.workspaceKey();
    }

    private String keyLabel() {
        if (this.capturingKey) return L10n.tr("settings.shortcut.capturing", "> Press a key <");
        var key = workspaceKey();
        if (key == null) return L10n.tr("settings.shortcut.unavailable", "Unavailable");
        return key.isUnbound() ? L10n.tr("settings.shortcut.unbound", "Not bound") : key.getTranslatedKeyMessage().getString();
    }

    private void toggleKeyCapture() {
        if (workspaceKey() == null) return;
        this.capturingKey = !this.capturingKey;
        this.rebuildWidgets();
        if (this.keyButton != null) this.setFocused(this.keyButton);
    }

    private void bindKey(InputConstants.Key input) {
        var key = workspaceKey();
        this.capturingKey = false;
        if (key != null) {
            key.setKey(input);
            KeyMapping.resetMapping();
            this.minecraft.options.save();
        }
        this.rebuildWidgets();
    }

    private void openKeyBinds() {
        this.capturingKey = false;
        this.openingChild = true; // keep drafts (including an unsent code) while the vanilla editor is open
        this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.minecraft.options));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.capturingKey) {
            if (event.isEscape()) {
                this.capturingKey = false;
                this.rebuildWidgets();
            } else {
                this.bindKey(InputConstants.getKey(event));
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.capturingKey) {
            // Clicking anywhere cancels; extra mouse buttons (not left/right) bind like in vanilla's editor.
            int button = event.button();
            if (button != InputConstants.MOUSE_BUTTON_LEFT && button != InputConstants.MOUSE_BUTTON_RIGHT) {
                this.bindKey(InputConstants.Type.MOUSE.getOrCreate(button));
            } else {
                this.capturingKey = false;
                this.rebuildWidgets();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    // ---------------------------------------------------------------- actions

    /** Themes the arrows step through: the eight presets, plus Custom once a custom palette has been saved. */
    private List<ColorTheme> quickThemes(SettingsView settings) {
        var themes = new ArrayList<ColorTheme>();
        for (var theme : ColorTheme.values())
            if (theme != ColorTheme.CUSTOM || settings.hasCustomPalette() || settings.theme() == ColorTheme.CUSTOM) themes.add(theme);
        return themes;
    }

    private void cycleTheme(int direction) {
        var settings = this.controller.snapshot().settings();
        var themes = this.quickThemes(settings);
        var next = themes.get(Math.floorMod(Math.max(0, themes.indexOf(settings.theme())) + direction, themes.size()));
        if (!this.controller.setTheme(next)) {
            this.fail(L10n.tr("settings.error.theme", "Could not save the theme."));
            return;
        }
        Theme.sync(this.controller.snapshot().settings());
        this.rebuildKeepingFocus();
    }

    private void openThemeEditor() {
        this.capturingKey = false;
        this.openingChild = true; // keep drafts (including an unsent code) while the editor is open
        this.minecraft.gui.setScreen(new ThemeEditorScreen(this, this.controller));
    }

    private void stepTransparency(int delta) {
        // Routed through the slider so its label and track follow; it calls applyTransparency.
        if (this.transparencySlider != null) this.transparencySlider.setValue(this.transparencySlider.value() + delta);
    }

    /** Saves and previews a transparency value without rebuilding, so focus and drafts stay put. */
    private boolean applyTransparency(int percent) {
        if (!this.controller.setTransparency(percent)) {
            this.fail(L10n.tr("settings.error.transparency", "Could not save the transparency."));
            return false;
        }
        Theme.setTransparency(percent);
        return true;
    }

    /** Rebuilds the widgets and puts focus back on the control at the same position (layouts keep their order). */
    private void rebuildKeepingFocus() {
        int index = this.children().indexOf(this.getFocused());
        this.rebuildWidgets();
        if (index >= 0 && index < this.children().size()) this.setFocused(this.children().get(index));
    }

    private void save() {
        String url = this.url.strip();
        String code = this.connectionCode.strip();
        if (url.isEmpty()) {
            this.fail(L10n.tr("settings.error.url", "Enter the T3 server URL or paste the MCP URL from T3 Code."));
            return;
        }
        try {
            T3OAuth.connectionAddress(url).resolveCode(code);
        } catch (IOException e) {
            this.fail(e.getMessage());
            return;
        }
        // Blank code: the backend reuses the saved login for the same endpoint, otherwise signs in via the browser.
        var update = new SettingsUpdate(url, code, this.notificationMode);
        if (!this.controller.saveProfile(this.profileName.strip(), update)) {
            this.fail(L10n.tr("settings.error.save", "Could not save this server."));
            return;
        }
        this.onClose();
    }

    private void fail(String message) {
        this.error = message;
        this.rebuildKeepingFocus();
    }

    private void clearValidation() {
        this.error = "";
        this.validationNotes.clear();
    }

    @Override
    public void onClose() {
        this.connectionCode = "";
        this.capturingKey = false;
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void removed() {
        if (!this.openingChild) this.connectionCode = "";
        this.capturingKey = false;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return this.parent != null && this.parent.isPauseScreen();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (Theme.seeThrough()) Theme.seeThroughBackdrop(this.minecraft, graphics, this.width, this.height);
        else super.extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, this.width, this.height, Theme.BG);
        if (!this.serverForm) {
            int left = this.panelX + 10;
            int w = this.panelW - 20;
            UiShapes.panel(graphics, left, 28, w, 23, UiShapes.R_CONTROL, Theme.INPUT, Theme.BORDER, false);
            UiShapes.panel(graphics, this.panelX, 58, this.panelW, Math.max(20, this.panelBottom - 58),
                    UiShapes.R_PANEL, Theme.PANEL_SURFACE, Theme.BORDER_STRONG, true);
        } else {
            UiShapes.panel(graphics, this.panelX, 26, this.panelW, Math.max(20, this.panelBottom - 26),
                    UiShapes.R_PANEL, Theme.PANEL_SURFACE, Theme.BORDER_STRONG, true);
        }
    }

    @Override
    public void extractRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        var heading = this.getTitle().copy().withStyle(s -> s.withBold(true));
        Theme.plate(graphics, this.panelX + 10, 10, this.font.width(heading), this.font.lineHeight, Theme.TEXT_PLATE);
        graphics.text(this.font, heading, this.panelX + 10, 10, Theme.TEXT, false);
        var connection = this.controller.snapshot().connection();
        String status = Theme.connectionLabel(connection.state());
        Theme.plate(graphics, this.panelX + this.panelW - 19 - this.font.width(status), 10, 9 + this.font.width(status),
                this.font.lineHeight, Theme.TEXT_PLATE);
        UiIcons.connectionMark(graphics, this.panelX + this.panelW - 19 - this.font.width(status), 11,
                connection.state(), Theme.connectionColor(connection.state()));
        graphics.text(this.font, status, this.panelX + this.panelW - 10 - this.font.width(status), 10, Theme.MUTED, false);

        for (var note : this.notes) graphics.text(this.font, note.text(), note.x(), note.y(), note.color(), false);
        for (var note : this.validationNotes) graphics.text(this.font, note.text(), note.x(), note.y(), note.color(), false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        for (var swatch : this.swatches) {
            UiShapes.pill(graphics, swatch.x(), swatch.y(), 9, 9, swatch.color(), Theme.BORDER_STRONG);
        }
    }
}
