package dev.craftcode.ui;

import dev.craftcode.ui.model.NotificationMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Presents T3 notifications either in chat or as a toast popup. Safe to call from any thread. */
public final class Notifications {
    private Notifications() {}

    public static void present(NotificationMode mode, String title, String body) {
        if (mode == null || mode == NotificationMode.OFF) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> present(mode, title, body));
            return;
        }

        String safeTitle = Theme.orDefault(title, "CraftCode").strip();
        String safeBody = body == null ? "" : body.strip().replace('\n', ' ');

        switch (mode) {
            case CHAT -> {
                if (minecraft.gui == null) return;
                var message = Component.empty()
                        .append(Component.literal("[CraftCode] ").withStyle(s -> s.withBold(true).withColor(Theme.isLight() ? 0xF0AB78 : Theme.ACCENT & 0xFFFFFF)))
                        .append(Component.literal(safeTitle));
                if (!safeBody.isEmpty()) {
                    message.append(Component.literal(" — " + truncate(safeBody, 240)).withStyle(net.minecraft.ChatFormatting.GRAY));
                }
                minecraft.gui.hud.getChat().addClientSystemMessage(message);
            }
            case POPUP -> {
                if (minecraft.gui == null) return;
                SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal(truncate(safeTitle, 48)),
                        safeBody.isEmpty() ? null : Component.literal(truncate(safeBody, 64)));
            }
            default -> {}
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
