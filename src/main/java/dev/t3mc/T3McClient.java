package dev.t3mc;

import dev.t3mc.ui.WorkspaceScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class T3McClient implements ClientModInitializer {
    private static KeyMapping workspaceKey;

    public static KeyMapping workspaceKey() {
        return workspaceKey;
    }

    @Override
    public void onInitializeClient() {
        var controller = new ClientController(FabricLoader.getInstance().getConfigDir().resolve("t3mc.json"));
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("t3mc", "workspace"));
        workspaceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.t3mc.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_U, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (workspaceKey.consumeClick()) if (client.gui.screen() == null) client.gui.setScreen(new WorkspaceScreen(controller));
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            boolean inGameMenu = client.level != null && (screen instanceof OptionsScreen
                    || screen instanceof PauseScreen pause && pause.showsPauseMenu());
            if (screen instanceof TitleScreen || inGameMenu) Screens.getWidgets(screen).add(Button
                    .builder(Component.literal("T3 Code"), b -> client.gui.setScreen(new WorkspaceScreen(controller, screen)))
                    .bounds(width - 74, 6, 68, 20).build());
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> controller.close());
    }
}
