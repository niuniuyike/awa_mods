package top.msu333.welcomemod;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
// 26.1 修改点：类名从 ServerPlayerEntity 简化为 ServerPlayer
import net.minecraft.server.level.ServerPlayer;
// 26.1 修改点：Text 类的位置发生了变化
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WelcomeMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "welcome-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static WelcomeConfig config;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Welcome mod [1.1] initializing for MC 26.1...");

        config = WelcomeConfig.load();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            server.execute(() -> sendWelcomeMessage(player));
        });

        // 注册 /welcome refresh 命令（控制台专用，热重载配置）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("welcome")
                            .then(
                                    Commands.literal("reload")
                                            .requires(source -> source.getEntity() == null) // 仅控制台可执行
                                            .executes(context -> {
                                                WelcomeMod.reloadConfig();
                                                context.getSource().sendSuccess(
                                                        () -> Component.literal("Welcome config reloaded."),
                                                        true
                                                );
                                                return 1;
                                            })
                            )
            );
        });

        LOGGER.info("Welcome mod initialized!");
    }

    private void sendWelcomeMessage(ServerPlayer player) {
        if (player == null) return;

        String playerName = player.getName().getString();
        String rawMessage = config.getWelcomeMessage();
        if (rawMessage == null || rawMessage.isBlank()) return;

        String formattedMessage = rawMessage.replace("%player%", playerName);
        sendFormattedMessage(player, formattedMessage);

        LOGGER.info("Welcomed player: {}", playerName);
    }

    private void sendFormattedMessage(ServerPlayer player, String message) {
        String[] lines = message.split("\\\\n|\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            player.sendSystemMessage(Component.literal(parseColorCodes(line)));
        }
    }

    private String parseColorCodes(String message) {
        return (message == null) ? "" : message.replace('&', '§');
    }

    public static WelcomeConfig getConfig() {
        return config;
    }

    public static void reloadConfig() {
        config = WelcomeConfig.load();
        LOGGER.info("Configuration reloaded.");
    }
}