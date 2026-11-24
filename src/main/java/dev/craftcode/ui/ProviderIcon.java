package dev.craftcode.ui;

import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.craftcode.ui.model.ModelProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** The built-in drivers with bundled pixel marks. Instance names and model IDs are user-defined. */
enum ProviderIcon {
    CODEX("codex"),
    CLAUDE("claude"),
    OPENCODE("opencode"),
    ANTIGRAVITY("antigravity"),
    CURSOR("cursor");

    static final int SIZE = 10;
    // Published provider icon packs replace textures and atlas sprites under this namespace.
    private static final String PACK_NAMESPACE = "t3mc";
    private static final Identifier CODEX_COLOR = Identifier.fromNamespaceAndPath(PACK_NAMESPACE, "provider_icons/codex_color");
    private final Identifier texture;

    ProviderIcon(String name) {
        texture = Identifier.fromNamespaceAndPath(PACK_NAMESPACE, "textures/gui/providers/" + name + ".png");
    }

    static @Nullable ProviderIcon resolve(@Nullable ModelProvider provider, String providerId) {
        String driver = provider == null ? providerId : provider.driverKind();
        if (driver == null) return null;
        return switch (driver) {
            case "codex" -> CODEX;
            case "claudeAgent" -> CLAUDE;
            case "opencode" -> OPENCODE;
            case "antigravity" -> ANTIGRAVITY;
            case "cursor" -> CURSOR;
            default -> null;
        };
    }

    void draw(SpruceGuiGraphics graphics, int x, int y, boolean dimmed) {
        if (this == CODEX) {
            var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.GUI);
            var fullColorSprite = atlas.getSprite(CODEX_COLOR);
            if (fullColorSprite != atlas.missingSprite()) {
                graphics.vanilla().blitSprite(RenderPipelines.GUI_TEXTURED, fullColorSprite, x, y, SIZE, SIZE,
                        dimmed ? 0xA0FFFFFF : 0xFFFFFFFF);
                return;
            }
        }
        int tint = switch (this) {
            case CODEX, OPENCODE, CURSOR -> Theme.TEXT;
            case CLAUDE -> Theme.isLight() ? 0xFFAE5033 : 0xFFE99370;
            case ANTIGRAVITY -> Theme.isLight() ? 0xFFD8D8E0 : 0xFFFFFFFF;
        };
        int color = dimmed ? (tint & 0x00FFFFFF) | 0xA0000000 : tint;
        graphics.vanilla().blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, SIZE, SIZE, SIZE, SIZE, color);
    }
}
