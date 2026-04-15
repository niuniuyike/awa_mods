package top.msu333;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public class AwaBot implements DedicatedServerModInitializer {
    private static String currentPassword = "admin_secure_key_32bytes_long!!";
    private static MinecraftServer mcServer;
    private static HttpServer httpServer;
    private static final long[] tickTimes = new long[100];
    private static int tickIndex = 0;
    private static int serverPort = 25566;
    private static final Gson GSON = new GsonBuilder().create();

    // 搜索结果缓存：按请求来源IP存储
    private static final Map<String, List<String>> searchCache = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final Map<String, String> pendingBinds = new ConcurrentHashMap<>();
    private static final List<String> pendingUnbinds = Collections.synchronizedList(new ArrayList<>());

    public record BotConfig(int port, String default_password) {}
    public record ServerResponse(int count, List<String> players, Map<String, String> binds, List<String> unbinds) {}
    public record PlayerStatsResponse(String name, long playTime, long mined, long kills, double walk, long deaths, long fish, List<String> candidates) {}
    public record TpsResponse(double tps) {}
    public record SecurePayload(String data, long ts) {}

    @Override
    public void onInitializeServer() {
        loadConfig();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickTimes[tickIndex] = System.currentTimeMillis();
            tickIndex = (tickIndex + 1) % 100;
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            mcServer = server;
            startHttpServer();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (httpServer != null) httpServer.stop(0);
            scheduler.shutdownNow();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("qqbot")
                    .then(Commands.literal("bind")
                            .then(Commands.argument("code", StringArgumentType.string())
                                    .executes(context -> {
                                        String code = StringArgumentType.getString(context, "code")
                                                .replace("[", "").replace("]", "").trim();
                                        ServerPlayer player = context.getSource().getPlayer();
                                        if (player != null) {
                                            pendingBinds.put(player.getName().getString(), code);
                                            context.getSource().sendSystemMessage(Component.literal("§a[QQBot] §f绑定请求已发送: " + code));
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("unbind")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    pendingUnbinds.add(player.getName().getString());
                                    context.getSource().sendSystemMessage(Component.literal("§a[QQBot] §f解绑请求已发送"));
                                }
                                return 1;
                            }))
            );
        });
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(serverPort), 0);
            httpServer.createContext("/api/server", new ApiHandler("server"));
            httpServer.createContext("/api/tps", new ApiHandler("tps"));
            httpServer.createContext("/api/player", new ApiHandler("player"));
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
            System.out.println("[AwaBot] API Server started on port " + serverPort);
        } catch (IOException e) { e.printStackTrace(); }
    }

    static class ApiHandler implements HttpHandler {
        private final String type;
        public ApiHandler(String t) { this.type = t; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.trim().equals(currentPassword)) {
                sendResponse(exchange, 401, "{\"error\":\"401 Unauthorized\"}");
                return;
            }
            try {
                String rawJson = switch (type) {
                    case "tps" -> GSON.toJson(new TpsResponse(calculateTps()));
                    case "server" -> {
                        List<String> playerNames = mcServer.getPlayerList().getPlayers().stream()
                                .map(p -> p.getName().getString()).toList();
                        Map<String, String> binds = new HashMap<>(pendingBinds);
                        pendingBinds.clear();
                        List<String> unbinds;
                        synchronized (pendingUnbinds) {
                            unbinds = new ArrayList<>(pendingUnbinds);
                            pendingUnbinds.clear();
                        }
                        yield GSON.toJson(new ServerResponse(playerNames.size(), playerNames, binds, unbinds));
                    }
                    case "player" -> {
                        String query = exchange.getRequestURI().getQuery();
                        String targetInput = "";
                        if (query != null) {
                            for (String param : query.split("&")) {
                                if (param.startsWith("name=")) {
                                    targetInput = java.net.URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                                    break;
                                }
                            }
                        }
                        if (targetInput.isEmpty()) yield "{\"error\":\"Empty Name\"}";

                        // 获取客户端标识用于缓存隔离
                        String clientKey = exchange.getRemoteAddress().getAddress().getHostAddress();

                        // 1. 处理数字选择
                        if (targetInput.matches("^\\d+$")) {
                            int index = Integer.parseInt(targetInput) - 1;
                            List<String> candidates = searchCache.get(clientKey);
                            if (candidates != null && index >= 0 && index < candidates.size()) {
                                String selectedName = candidates.get(index);
                                searchCache.remove(clientKey);
                                yield getPlayerStatsByName(selectedName);
                            }
                            yield "{\"error\":\"Invalid selection or session expired\"}";
                        }

                        // 2. 收集所有已知玩家名称
                        Set<String> allNames = new HashSet<>();

                        // 在线玩家
                        mcServer.getPlayerList().getPlayers().forEach(p -> allNames.add(p.getName().getString()));

                        // usercache.json
                        Path cachePath = mcServer.getServerDirectory().resolve("usercache.json");
                        if (Files.exists(cachePath)) {
                            try (Reader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
                                List<Map<String, Object>> cacheList = GSON.fromJson(reader, new TypeToken<List<Map<String, Object>>>(){}.getType());
                                if (cacheList != null) {
                                    for (Map<String, Object> entry : cacheList) {
                                        String name = (String) entry.get("name");
                                        if (name != null) allNames.add(name);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        // 3. 精确匹配优先
                        List<String> exactMatches = new ArrayList<>();
                        List<String> containsMatches = new ArrayList<>();
                        String lowerInput = targetInput.toLowerCase();

                        for (String name : allNames) {
                            if (name.equalsIgnoreCase(targetInput)) {
                                exactMatches.add(name);
                            } else if (name.toLowerCase().contains(lowerInput)) {
                                containsMatches.add(name);
                            }
                        }

                        // 合并结果
                        List<String> matches = new ArrayList<>();
                        matches.addAll(exactMatches);
                        matches.addAll(containsMatches);

                        // 去重
                        Set<String> uniqueMatches = new LinkedHashSet<>(matches);
                        List<String> finalMatches = new ArrayList<>(uniqueMatches);

                        // 4. 规则判定
                        if (finalMatches.isEmpty()) {
                            yield "{\"error\":\"Player Not Found\"}";
                        } else if (finalMatches.size() == 1) {
                            yield getPlayerStatsByName(finalMatches.get(0));
                        } else if (targetInput.length() >= 3) {
                            // 多个匹配，返回候选列表
                            searchCache.put(clientKey, finalMatches);
                            scheduler.schedule(() -> searchCache.remove(clientKey), 30, TimeUnit.SECONDS);
                            yield GSON.toJson(new PlayerStatsResponse("Multiple", 0, 0, 0, 0, 0, 0, finalMatches));
                        } else {
                            // 输入太短，返回第一个
                            yield getPlayerStatsByName(finalMatches.get(0));
                        }
                    }
                    default -> "{}";
                };
                String encrypted = encrypt(rawJson, currentPassword);
                sendResponse(exchange, 200, GSON.toJson(new SecurePayload(encrypted, System.currentTimeMillis())));
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            }
        }

        private String getPlayerStatsByName(String playerName) throws IOException {
            String targetUuid = null;
            String actualName = playerName;

            // 1. 先查在线玩家
            ServerPlayer onlinePlayer = mcServer.getPlayerList().getPlayerByName(playerName);
            if (onlinePlayer != null) {
                targetUuid = onlinePlayer.getStringUUID();
                actualName = onlinePlayer.getName().getString();
            } else {
                // 2. 查usercache.json
                Path cachePath = mcServer.getServerDirectory().resolve("usercache.json");
                if (Files.exists(cachePath)) {
                    try (Reader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
                        List<Map<String, Object>> cacheList = GSON.fromJson(reader, new TypeToken<List<Map<String, Object>>>(){}.getType());
                        if (cacheList != null) {
                            for (Map<String, Object> entry : cacheList) {
                                if (playerName.equalsIgnoreCase((String) entry.get("name"))) {
                                    targetUuid = (String) entry.get("uuid");
                                    actualName = (String) entry.get("name");
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (targetUuid == null) {
                return "{\"error\":\"Player Not Found\"}";
            }

            // 3. 读取统计文件 - 修复路径：world/players/stats/
            Path worldPath = mcServer.getWorldPath(LevelResource.ROOT);
            Path statsPath = worldPath.resolve("players").resolve("stats").resolve(targetUuid + ".json");

            if (!Files.exists(statsPath)) {
                return "{\"error\":\"No Stats Data\"}";
            }

            Map<String, Object> fullJson;
            try (Reader reader = Files.newBufferedReader(statsPath, StandardCharsets.UTF_8)) {
                fullJson = GSON.fromJson(reader, new TypeToken<Map<String, Object>>(){}.getType());
            }

            if (fullJson == null) return "{\"error\":\"Stats Empty\"}";

            Map<String, Object> stats = (Map<String, Object>) fullJson.get("stats");
            if (stats == null) return "{\"error\":\"Stats Empty\"}";

            long playTime = getStat(stats, "minecraft:custom", "minecraft:play_time");
            long kills = getStat(stats, "minecraft:custom", "minecraft:mob_kills");
            double walk = getStat(stats, "minecraft:custom", "minecraft:walk_one_cm") / 100.0;
            long deaths = getStat(stats, "minecraft:custom", "minecraft:deaths");
            long fish = getStat(stats, "minecraft:custom", "minecraft:fish_caught");

            long totalMined = 0;
            Map<String, Object> minedMap = (Map<String, Object>) stats.get("minecraft:mined");
            if (minedMap != null) {
                for (Object v : minedMap.values()) {
                    if (v instanceof Double d) totalMined += d.longValue();
                }
            }

            return GSON.toJson(new PlayerStatsResponse(actualName, playTime, totalMined, kills, walk, deaths, fish, null));
        }

        private long getStat(Map<String, Object> stats, String category, String key) {
            try {
                Map<String, Object> cat = (Map<String, Object>) stats.get(category);
                if (cat != null) {
                    Object val = cat.get(key);
                    if (val instanceof Double d) return d.longValue();
                }
            } catch (Exception ignored) {}
            return 0;
        }

        private void sendResponse(HttpExchange exchange, int code, String content) throws IOException {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String encrypt(String data, String key) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        byte[] keyBytes = Arrays.copyOf(key.getBytes(StandardCharsets.UTF_8), 32);
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static double calculateTps() {
        int prev = (tickIndex - 1 + 100) % 100;
        long gap = Math.abs(tickTimes[prev] - tickTimes[tickIndex]);
        if (gap == 0) return 20.0;
        return Math.min(20.0, 1000.0 / ((double) gap / 100.0));
    }

    private void loadConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("awabot_config.json");
        try {
            if (!Files.exists(path)) saveConfig();
            else {
                BotConfig cfg = GSON.fromJson(Files.newBufferedReader(path), BotConfig.class);
                if (cfg != null) {
                    serverPort = cfg.port();
                    currentPassword = cfg.default_password();
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void saveConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("awabot_config.json");
        try {
            Files.writeString(path, GSON.toJson(new BotConfig(serverPort, currentPassword)));
        } catch (Exception e) { e.printStackTrace(); }
    }
}