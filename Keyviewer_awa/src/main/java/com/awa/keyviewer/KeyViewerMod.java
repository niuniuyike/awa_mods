package com.awa.keyviewer;

import com.awa.keyviewer.config.KeyViewerConfig;
import com.awa.keyviewer.gui.KeyViewerScreen;
import com.awa.keyviewer.hud.KeyViewerHud;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeyViewerMod implements ClientModInitializer {
	public static final String MOD_ID = "keyviewer_awa";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static KeyViewerConfig config;

	public static KeyViewerConfig config() {
		if (config == null) {
			config = KeyViewerConfig.load();
		}
		return config;
	}

	@Override
	public void onInitializeClient() {
		config = KeyViewerConfig.load();

		HudElementRegistry.addLast(KeyViewerHud.ID, new KeyViewerHud());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("awakeyviewer")
						.executes(context -> {
							Minecraft client = Minecraft.getInstance();
							client.execute(() -> client.setScreenAndShow(new KeyViewerScreen()));
							return 1;
						})));

		LOGGER.info("[AWA KeyViewer] initialized (config: {})", KeyViewerConfig.configPath());
	}
}
