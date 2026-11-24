package dev.t3mc;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Resolves mod-authored text against the current Minecraft language, including resource pack overrides. */
public final class L10n {
    private L10n() {}

    public static String tr(String key, String englishFallback, Object... args) {
        String translationKey = "t3mc." + key;
        if (args.length == 0) return Language.getInstance().getOrDefault(translationKey, englishFallback);
        return Component.translatableWithFallback(translationKey, englishFallback, args).getString();
    }
}
