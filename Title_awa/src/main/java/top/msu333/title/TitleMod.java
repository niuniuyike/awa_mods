package top.msu333.title;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TitleMod implements ModInitializer {
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("welcome-mod");
    private static ModConfig config;
    private static final Map<UUID, PlayerData> playerDataMap = new HashMap<>();
    private static final String AFK_TEAM_NAME = "afk_team";

    @Override
    public void onInitialize() {
        // 仅服务器端执行
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            return;
        }

        LOGGER.info("Loading AFK title module...");
        config = ModConfig.load();

        // 玩家断开连接时清理数据
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            UUID uuid = player.getUUID();
            PlayerData data = playerDataMap.remove(uuid);

            // 如果玩家是 AFK 状态退出，把他从 AFK 队伍中移除
            if (data != null && data.afk) {
                Scoreboard scoreboard = server.getScoreboard();
                PlayerTeam afkTeam = scoreboard.getPlayerTeam(AFK_TEAM_NAME);
                if (afkTeam != null) {
                    scoreboard.removePlayerFromTeam(player.getName().getString(), afkTeam);
                }
            }
        });

        // 每秒检测 AFK
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            if (!config.afk.enabled) return;

            Scoreboard scoreboard = server.getScoreboard();
            PlayerTeam afkTeam = ensureAfkTeam(scoreboard);

            long now = System.currentTimeMillis();
            long afkThreshold = config.afk.seconds * 1000L;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID uuid = player.getUUID();
                PlayerData data = playerDataMap.get(uuid);

                if (data == null) {
                    data = new PlayerData(now, player.getX(), player.getY(), player.getZ(),
                            player.getYRot(), player.getXRot(), false, null);
                    playerDataMap.put(uuid, data);
                    continue;
                }

                boolean moved = hasPlayerMoved(player, data);
                if (moved) {
                    data.updatePosition(now, player);
                    if (data.afk) {
                        setPlayerAFK(player, false, data, scoreboard);
                    }
                } else if (!data.afk && (now - data.lastActiveTime) >= afkThreshold) {
                    setPlayerAFK(player, true, data, scoreboard);
                }
            }
        });

        // 注册 /title reload 命令（仅控制台可执行）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("title")
                            .then(Commands.literal("reload")
                                    .requires(source -> source.getEntity() == null) // 仅控制台
                                    .executes(context -> {
                                        MinecraftServer server = context.getSource().getServer();
                                        reloadConfig(server);
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("Title config reloaded!"), true);
                                        return 1;
                                    })
                            )
            );
        });
    }

    private boolean hasPlayerMoved(ServerPlayer player, PlayerData data) {
        return data.x != player.getX() || data.y != player.getY() || data.z != player.getZ()
                || data.yaw != player.getYRot() || data.pitch != player.getXRot();
    }

    private void setPlayerAFK(ServerPlayer player, boolean afk, PlayerData data, Scoreboard scoreboard) {
        PlayerTeam afkTeam = ensureAfkTeam(scoreboard);
        String playerName = player.getName().getString();

        if (afk) {
            PlayerTeam currentTeam = scoreboard.getPlayerTeam(playerName);
            data.previousTeamName = (currentTeam != null) ? currentTeam.getName() : null;
            scoreboard.addPlayerToTeam(playerName, afkTeam);
            data.afk = true;
            //LOGGER.info("Player {} is now AFK", playerName);
        } else {
            scoreboard.removePlayerFromTeam(playerName, afkTeam);
            if (data.previousTeamName != null) {
                PlayerTeam previousTeam = scoreboard.getPlayerTeam(data.previousTeamName);
                if (previousTeam != null) {
                    scoreboard.addPlayerToTeam(playerName, previousTeam);
                }
            }
            data.afk = false;
            data.previousTeamName = null;
            //LOGGER.info("Player {} is no longer AFK", playerName);
        }
    }

    private PlayerTeam ensureAfkTeam(Scoreboard scoreboard) {
        PlayerTeam team = scoreboard.getPlayerTeam(AFK_TEAM_NAME);
        if (team == null) {
            team = scoreboard.addPlayerTeam(AFK_TEAM_NAME);
            team.setPlayerPrefix(Component.literal(formatColors(config.afk.prefix)));
        } else {
            String currentPrefix = team.getPlayerPrefix().getString();
            String desiredPrefix = formatColors(config.afk.prefix);
            if (!currentPrefix.equals(desiredPrefix)) {
                team.setPlayerPrefix(Component.literal(desiredPrefix));
            }
        }
        return team;
    }

    private static String formatColors(String input) {
        return input.replace('&', '§');
    }

    private void reloadConfig(MinecraftServer server) {
        config = ModConfig.load();
        Scoreboard scoreboard = server.getScoreboard();

        // 若 AFK 功能被关闭，移除所有现有 AFK 玩家
        if (!config.afk.enabled) {
            for (Map.Entry<UUID, PlayerData> entry : playerDataMap.entrySet()) {
                PlayerData data = entry.getValue();
                if (data.afk) {
                    ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                    if (player != null) {
                        setPlayerAFK(player, false, data, scoreboard);
                    } else {
                        data.afk = false;
                    }
                }
            }
        } else {
            // 更新队伍前缀
            PlayerTeam afkTeam = scoreboard.getPlayerTeam(AFK_TEAM_NAME);
            if (afkTeam != null) {
                afkTeam.setPlayerPrefix(Component.literal(formatColors(config.afk.prefix)));
            }
        }
        LOGGER.info("Title config reloaded.");
    }
}