package top.msu333;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class AwaBot implements DedicatedServerModInitializer {
    private static String currentPassword = "123456";
    private static int safety = 0;
    private static int preventMultiBind = 1;
    private static int serverPort = 25566;
    private static MinecraftServer mcServer;
    private static HttpServer httpServer;
    private static final long[] tickTimes = new long[100];
    private static int tickIndex = 0;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final Map<String, String> pendingBinds = new ConcurrentHashMap<>();

    public record BoundPlayer(String name, String uuid) {}
    public record BotConfig(Map<String, List<BoundPlayer>> binds, Set<String> explicit_admins, Map<String, Long> banned_qqs) {}

    private static final Map<String, List<BoundPlayer>> playerBinds = new ConcurrentHashMap<>();
    private static final Set<String> explicitAdmins = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> bannedQQs = new ConcurrentHashMap<>();

    public static class BindSession {
        public String name;
        public String uuid;
        public String code;
        public long expireTime;
        public String qq;

        public BindSession(String name, String uuid, String code) {
            this.name = name;
            this.uuid = uuid;
            this.code = code;
            this.expireTime = System.currentTimeMillis() + 5 * 60 * 1000L;
        }
    }
    private static final Map<String, BindSession> codeToSession = new ConcurrentHashMap<>();
    private static final List<BindSession> pendingAdminApprovals = Collections.synchronizedList(new ArrayList<>());

    public record ServerResponse(int count, List<String> players, Map<String, String> binds, List<String> unbinds, List<String> admins) {}
    public record PlayerStatsResponse(String name, long playTime, long mined, long kills, double walk, long deaths, long fish, List<String> candidates) {}
    public record TpsResponse(double tps) {}
    public record SecurePayload(String data, long ts) {}

    @Override
    public void onInitializeServer() {
        loadProperties();
        loadJsonConfig();

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

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (safety > 0) {
                ServerPlayer player = handler.getPlayer();
                String uuid = player.getStringUUID();
                String name = player.getName().getString();

                // 1. 查找当前 UUID 是否有绑定记录
                String boundQQ = null;
                for (Map.Entry<String, List<BoundPlayer>> entry : playerBinds.entrySet()) {
                    if (entry.getValue().stream().anyMatch(bp -> bp.uuid().equals(uuid))) {
                        boundQQ = entry.getKey();
                        break;
                    }
                }

                // 2. 如果已绑定，检查封禁状态
                if (boundQQ != null) {
                    if (bannedQQs.containsKey(boundQQ)) {
                        long expireTime = bannedQQs.get(boundQQ);
                        if (expireTime != -1 && System.currentTimeMillis() > expireTime) {
                            bannedQQs.remove(boundQQ);
                            saveJsonConfig();
                        } else {
                            String timeStr = expireTime == -1 ? "永久" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(expireTime));
                            server.execute(() -> {
                                if (player.connection != null) {
                                    player.connection.disconnect(Component.literal("§c您关联的QQ已被服务器封禁！\n§f解封时间: " + timeStr));
                                }
                            });
                            return; // 确保被封禁玩家直接结束逻辑
                        }
                    }

                    // --- 新增：制裁中途开启“一对一限制”产生的历史多绑账号 ---
                    if (preventMultiBind == 1 && playerBinds.get(boundQQ) != null && playerBinds.get(boundQQ).size() > 1) {
                        server.execute(() -> {
                            if (player.connection != null) {
                                player.connection.disconnect(Component.literal("§c服务器已开启QQ一对一绑定模式喵！\n§f检测到您的QQ绑定了多个角色，已被系统拦截。\n§e请联系管理员使用 /clean 清理多余的绑定记录~"));
                            }
                        });
                        return; // 拦截并踢出
                    }
                    // --------------------------------------------------------

                    return; // 核心修复：只要绑过了、没被封、且符合绑定数量限制，直接放行进入游戏！
                }

                // 3. 只有【未绑定】的玩家才会进入这里的发码和拦截逻辑
                boolean isPendingAdmin = false;
                synchronized (pendingAdminApprovals) {
                    for (BindSession s : pendingAdminApprovals) {
                        if (s.uuid.equals(uuid)) {
                            isPendingAdmin = true;
                            break;
                        }
                    }
                }

                if (isPendingAdmin) {
                    server.execute(() -> {
                        if (player.connection != null)
                            player.connection.disconnect(Component.literal("§e您的绑定请求正在等待管理员审核，请稍后再试或联系管理员"));
                    });
                    return;
                }

                codeToSession.entrySet().removeIf(e -> e.getValue().expireTime < System.currentTimeMillis());
                String code = null;
                for (Map.Entry<String, BindSession> entry : codeToSession.entrySet()) {
                    if (entry.getValue().uuid.equals(uuid)) {
                        code = entry.getKey();
                        break;
                    }
                }

                if (code == null) {
                    SecureRandom random = new SecureRandom();
                    code = String.format("%04d", random.nextInt(10000));
                    while (codeToSession.containsKey(code)) {
                        code = String.format("%04d", random.nextInt(10000));
                    }
                    codeToSession.put(code, new BindSession(name, uuid, code));
                }

                String finalCode = code;
                server.execute(() -> {
                    if (player.connection != null) {
                        player.connection.disconnect(Component.literal(
                                "§c您需要绑定QQ才能进入\n" +
                                        "§f绑定码 [§e" + finalCode + "§f]\n" +
                                        "§b请在5min内私聊或群聊@Bot 并发送 /bind server " + finalCode + "\n" +
                                        "§e绑定后即可进入服务器~\n" +
                                        "§e该qq会与服务器绑定，不是与bot绑定"
                        ));
                    }
                });
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("qqbot")
                    .then(Commands.literal("admin")
                            .requires(source -> source.getEntity() == null)
                            .then(Commands.argument("target", StringArgumentType.string())
                                    .then(Commands.argument("force_qq", StringArgumentType.string())
                                            .executes(context -> {
                                                String targetName = StringArgumentType.getString(context, "target");
                                                final String forceQQ = StringArgumentType.getString(context, "force_qq");
                                                final String uuid;

                                                ServerPlayer onlineP = mcServer.getPlayerList().getPlayerByName(targetName);
                                                if (onlineP != null) {
                                                    uuid = onlineP.getStringUUID();
                                                } else {
                                                    String foundUuid = null;
                                                    Path cachePath = mcServer.getServerDirectory().resolve("usercache.json");
                                                    if (Files.exists(cachePath)) {
                                                        try (Reader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
                                                            List<Map<String, Object>> cacheList = GSON.fromJson(reader, new TypeToken<List<Map<String, Object>>>(){}.getType());
                                                            if (cacheList != null) {
                                                                for (Map<String, Object> entry : cacheList) {
                                                                    if (targetName.equalsIgnoreCase((String) entry.get("name"))) {
                                                                        foundUuid = (String) entry.get("uuid");
                                                                        break;
                                                                    }
                                                                }
                                                            }
                                                        } catch (Exception ignored) {}
                                                    }
                                                    uuid = foundUuid;
                                                }

                                                if (uuid == null) {
                                                    context.getSource().sendSystemMessage(Component.literal("[QQBot] Error: Player not found!"));
                                                    return 0;
                                                }

                                                bindPlayerLogic(forceQQ, targetName, uuid);
                                                explicitAdmins.add(forceQQ);

                                                synchronized (pendingAdminApprovals) {
                                                    pendingAdminApprovals.removeIf(s -> s.uuid.equals(uuid) || s.qq.equals(forceQQ));
                                                }
                                                codeToSession.entrySet().removeIf(e -> e.getValue().uuid.equals(uuid));

                                                saveJsonConfig();
                                                context.getSource().sendSystemMessage(Component.literal("[QQBot] Force bound " + targetName + " to " + forceQQ + " as Admin."));
                                                return 1;
                                            }))
                                    .executes(context -> {
                                        final String target = StringArgumentType.getString(context, "target");
                                        boolean handled = false;

                                        // 1. 查已绑定的玩家表
                                        for (Map.Entry<String, List<BoundPlayer>> entry : playerBinds.entrySet()) {
                                            if (entry.getValue().stream().anyMatch(bp -> bp.name().equalsIgnoreCase(target))) {
                                                explicitAdmins.add(entry.getKey());
                                                saveJsonConfig();
                                                context.getSource().sendSystemMessage(Component.literal("[QQBot] Success! Set existing bound player as Admin."));
                                                handled = true;
                                                break;
                                            }
                                        }

                                        // 2. 查待审核列表捞人
                                        if (!handled) {
                                            synchronized (pendingAdminApprovals) {
                                                Iterator<BindSession> it = pendingAdminApprovals.iterator();
                                                while (it.hasNext()) {
                                                    BindSession s = it.next();
                                                    if (s.name.equalsIgnoreCase(target) || (s.qq != null && s.qq.equals(target))) {
                                                        bindPlayerLogic(s.qq, s.name, s.uuid);
                                                        explicitAdmins.add(s.qq);
                                                        it.remove();
                                                        codeToSession.entrySet().removeIf(e -> e.getValue().uuid.equals(s.uuid));
                                                        saveJsonConfig();
                                                        context.getSource().sendSystemMessage(Component.literal("[QQBot] Found in pending! Auto-accepted and set Admin: " + s.name));
                                                        handled = true;
                                                        break;
                                                    }
                                                }
                                            }
                                        }

                                        // 3. 都没找到，视为 QQ OpenID 预授权
                                        if (!handled) {
                                            explicitAdmins.add(target);
                                            saveJsonConfig();
                                            context.getSource().sendSystemMessage(Component.literal("[QQBot] Pre-authorized OpenID as Admin: " + target));
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("unadmin")
                            .requires(source -> source.getEntity() == null)
                            .then(Commands.argument("target", StringArgumentType.string())
                                    .executes(context -> {
                                        String target = StringArgumentType.getString(context, "target");
                                        String targetQQ = target;
                                        for (Map.Entry<String, List<BoundPlayer>> entry : playerBinds.entrySet()) {
                                            if (entry.getValue().stream().anyMatch(bp -> bp.name().equalsIgnoreCase(target))) {
                                                targetQQ = entry.getKey();
                                                break;
                                            }
                                        }
                                        if (explicitAdmins.remove(targetQQ)) {
                                            saveJsonConfig();
                                            context.getSource().sendSystemMessage(Component.literal("[QQBot] Success! Removed Admin: " + targetQQ));
                                        } else {
                                            context.getSource().sendSystemMessage(Component.literal("[QQBot] Target not found in Admin list."));
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("bind")
                            .then(Commands.argument("code", StringArgumentType.string())
                                    .executes(context -> {
                                        String code = StringArgumentType.getString(context, "code").replace("[", "").replace("]", "").trim();
                                        ServerPlayer player = context.getSource().getPlayer();
                                        if (player != null) {
                                            pendingBinds.put(player.getName().getString(), code);
                                            context.getSource().sendSystemMessage(Component.literal("§a[QQBot] §f快捷绑定已发送到 Bot，请稍候数据同步,bot不回复消息属于正常情况喵~"));
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("unbind")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    context.getSource().sendSystemMessage(Component.literal("§e[QQBot] §f若想改变绑定QQ，请联系管理员喵~"));
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
            httpServer.createContext("/api/action", new ApiHandler("action"));
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
                        List<String> playerNames = mcServer.getPlayerList().getPlayers().stream().map(p -> p.getName().getString()).toList();
                        Map<String, String> binds = new HashMap<>(pendingBinds);
                        pendingBinds.clear();
                        List<String> unbinds = new ArrayList<>();
                        List<String> admins = new ArrayList<>(explicitAdmins);
                        yield GSON.toJson(new ServerResponse(playerNames.size(), playerNames, binds, unbinds, admins));
                    }
                    case "action" -> {
                        String query = exchange.getRequestURI().getQuery();
                        Map<String, String> params = parseQuery(query);
                        String action = params.getOrDefault("action", "");

                        if ("submit_code".equals(action)) {
                            String qq = params.get("qq");
                            String code = params.get("code");
                            if (qq == null || code == null) yield "{\"error\":\"Missing qq or code\"}";

                            if (preventMultiBind == 1 && playerBinds.containsKey(qq) && !playerBinds.get(qq).isEmpty()) {
                                yield "{\"error\":\"该 QQ 已绑定过玩家，本服设置仅允许一绑一\"}";
                            }

                            if (bannedQQs.containsKey(qq)) {
                                long expireTime = bannedQQs.get(qq);
                                if (expireTime == -1 || System.currentTimeMillis() <= expireTime) {
                                    yield "{\"error\":\"您的QQ处于封禁状态\"}";
                                } else {
                                    bannedQQs.remove(qq);
                                }
                            }

                            codeToSession.entrySet().removeIf(e -> e.getValue().expireTime < System.currentTimeMillis());
                            BindSession session = codeToSession.remove(code);
                            if (session == null) {
                                yield "{\"error\":\"验证码无效或已过期，请重新进服获取\"}";
                            } else {
                                if (safety == 2) {
                                    session.qq = qq;
                                    pendingAdminApprovals.add(session);
                                    yield "{\"status\":\"pending_admin\",\"msg\":\"Waiting for admin approval\"}";
                                } else {
                                    bindPlayerLogic(qq, session.name, session.uuid);
                                    saveJsonConfig();
                                    yield "{\"status\":\"success\",\"msg\":\"Bind successful\"}";
                                }
                            }
                        } else if ("get_requests".equals(action)) {
                            List<Map<String, String>> reqs = new ArrayList<>();
                            synchronized (pendingAdminApprovals) {
                                for (int i = 0; i < pendingAdminApprovals.size(); i++) {
                                    BindSession s = pendingAdminApprovals.get(i);
                                    Map<String, String> rm = new HashMap<>();
                                    rm.put("index", String.valueOf(i + 1));
                                    rm.put("qq", s.qq);
                                    rm.put("name", s.name);
                                    reqs.add(rm);
                                }
                            }
                            yield GSON.toJson(reqs);
                        } else if ("accept".equals(action)) {
                            String target = params.get("target");
                            int accepted = 0;
                            synchronized (pendingAdminApprovals) {
                                if ("all".equalsIgnoreCase(target)) {
                                    for (BindSession s : pendingAdminApprovals) {
                                        bindPlayerLogic(s.qq, s.name, s.uuid);
                                        accepted++;
                                    }
                                    pendingAdminApprovals.clear();
                                } else {
                                    try {
                                        int idx = Integer.parseInt(target) - 1;
                                        if (idx >= 0 && idx < pendingAdminApprovals.size()) {
                                            BindSession s = pendingAdminApprovals.remove(idx);
                                            bindPlayerLogic(s.qq, s.name, s.uuid);
                                            accepted++;
                                        }
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                            if (accepted > 0) saveJsonConfig();
                            yield "{\"status\":\"success\",\"accepted\":" + accepted + "}";
                        } else if ("refuse".equals(action)) {
                            // --- 修改：支持按序号或 all 拒绝申请（与 accept 逻辑对齐） ---
                            String target = params.get("target");
                            int refused = 0;
                            synchronized (pendingAdminApprovals) {
                                if ("all".equalsIgnoreCase(target)) {
                                    refused = pendingAdminApprovals.size();
                                    pendingAdminApprovals.clear();
                                } else {
                                    try {
                                        int idx = Integer.parseInt(target) - 1;
                                        if (idx >= 0 && idx < pendingAdminApprovals.size()) {
                                            pendingAdminApprovals.remove(idx);
                                            refused++;
                                        }
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                            yield "{\"status\":\"success\",\"refused\":" + refused + "}";

                        } else if ("clean".equals(action)) {
                            // --- 修改：将原 refuse 逻辑重命名为 clean，用于清理指定玩家的历史绑定 ---
                            String target = params.get("target");
                            boolean removed = false;

                            // 顺便把处于申请列表里的该玩家/QQ也清理掉
                            synchronized (pendingAdminApprovals) {
                                removed = pendingAdminApprovals.removeIf(s -> s.name.equalsIgnoreCase(target) || s.qq.equals(target));
                            }

                            // 清理已绑定列表
                            for (Iterator<Map.Entry<String, List<BoundPlayer>>> it = playerBinds.entrySet().iterator(); it.hasNext();) {
                                Map.Entry<String, List<BoundPlayer>> entry = it.next();
                                if (entry.getKey().equals(target)) { // 如果 target 是 QQ 号，直接解绑该QQ下所有玩家
                                    it.remove();
                                    removed = true;
                                } else { // 如果 target 是玩家名，解绑特定玩家
                                    if (entry.getValue().removeIf(bp -> bp.name().equalsIgnoreCase(target))) {
                                        removed = true;
                                        if (entry.getValue().isEmpty()) it.remove(); // 如果QQ下没绑玩家了，把这行数据干掉
                                    }
                                }
                            }
                            if (removed) { saveJsonConfig(); yield "{\"status\":\"success\"}"; }
                            else yield "{\"error\":\"Not found\"}";

                        } else if ("ban".equals(action)) {
                            String target = params.get("target");
                            String hoursStr = params.get("hours");
                            String targetQQ = null;
                            for (Map.Entry<String, List<BoundPlayer>> entry : playerBinds.entrySet()) {
                                if (entry.getValue().stream().anyMatch(bp -> bp.name().equalsIgnoreCase(target))) {
                                    targetQQ = entry.getKey();
                                    break;
                                }
                            }
                            if (targetQQ == null) yield "{\"error\":\"No bind\"}";
                            long expire = -1;
                            if (hoursStr != null && !hoursStr.isEmpty()) {
                                try { expire = System.currentTimeMillis() + (long)(Double.parseDouble(hoursStr) * 3600000L); } catch (Exception ignored) {}
                            }
                            bannedQQs.put(targetQQ, expire);
                            saveJsonConfig();
                            yield "{\"status\":\"success\"}";
                        } else if ("unban".equals(action)) {
                            String target = params.get("target");
                            String targetQQ = null;
                            for (Map.Entry<String, List<BoundPlayer>> entry : playerBinds.entrySet()) {
                                if (entry.getValue().stream().anyMatch(bp -> bp.name().equalsIgnoreCase(target))) {
                                    targetQQ = entry.getKey();
                                    break;
                                }
                            }
                            if (targetQQ != null && bannedQQs.remove(targetQQ) != null) { saveJsonConfig(); yield "{\"status\":\"success\"}"; }
                            else yield "{\"error\":\"Not banned\"}";
                        } else yield "{\"error\":\"Unknown action\"}";
                    }
                    case "player" -> {
                        String query = exchange.getRequestURI().getQuery();
                        String targetInput = parseQuery(query).getOrDefault("name", "");
                        if (targetInput.isEmpty()) yield "{\"error\":\"Empty Name\"}";

                        // 收集所有可能的玩家名字源
                        Set<String> allNames = new HashSet<>();
                        // 1. 在线玩家
                        mcServer.getPlayerList().getPlayers().forEach(p -> allNames.add(p.getName().getString()));
                        // 2. 缓存玩家 (usercache)
                        Path cachePath = mcServer.getServerDirectory().resolve("usercache.json");
                        if (Files.exists(cachePath)) {
                            try (Reader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
                                List<Map<String, Object>> cacheList = GSON.fromJson(reader, new TypeToken<List<Map<String, Object>>>(){}.getType());
                                if (cacheList != null) cacheList.forEach(e -> {
                                    String n = (String) e.get("name");
                                    if (n != null) allNames.add(n);
                                });
                            } catch (Exception ignored) {}
                        }
                        // 3. 已绑定玩家
                        playerBinds.values().forEach(list -> list.forEach(bp -> allNames.add(bp.name())));

                        // 开始搜索
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

                        if (!exactMatches.isEmpty()) {
                            // 优先返回完全匹配
                            yield getPlayerStatsByName(exactMatches.get(0));
                        } else if (containsMatches.size() == 1) {
                            // 只有一个模糊匹配，直接返回
                            yield getPlayerStatsByName(containsMatches.get(0));
                        } else if (containsMatches.size() > 1) {
                            // 找到多个模糊匹配，返回列表让用户选
                            yield GSON.toJson(new PlayerStatsResponse("Multiple", 0, 0, 0, 0, 0, 0, containsMatches));
                        } else {
                            yield "{\"error\":\"Player Not Found\"}";
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

        private String getPlayerStatsByName(String playerName) {
            try {
                String targetUuid = null;
                String actualName = playerName;

                ServerPlayer onlinePlayer = mcServer.getPlayerList().getPlayerByName(playerName);
                if (onlinePlayer != null) {
                    targetUuid = onlinePlayer.getStringUUID();
                    actualName = onlinePlayer.getName().getString();
                } else {
                    Path usercachePath = mcServer.getServerDirectory().resolve("usercache.json");
                    if (Files.exists(usercachePath)) {
                        try (Reader reader = Files.newBufferedReader(usercachePath, StandardCharsets.UTF_8)) {
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
                        }
                    }
                }

                if (targetUuid == null) return "{\"error\":\"Player Not Found\"}";

                Path worldPath = mcServer.getWorldPath(LevelResource.ROOT);
                // 兼容不同版本的统计数据存放路径
                Path statsPath = worldPath.resolve("stats").resolve(targetUuid + ".json");
                if (!Files.exists(statsPath)) {
                    statsPath = worldPath.resolve("players").resolve("stats").resolve(targetUuid + ".json");
                }

                if (!Files.exists(statsPath))
                    return "{\"error\":\"No Stats Data\"}";

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
                        if (v instanceof Number n) totalMined += n.longValue();
                    }
                }

                return GSON.toJson(new PlayerStatsResponse(actualName, playTime, totalMined, kills, walk, deaths, fish, null));

            } catch (Exception e) {
                e.printStackTrace();
                return "{\"error\":\"Internal Error: " + e.getMessage() + "\"}";
            }
        }

        private long getStat(Map<String, Object> stats, String category, String key) {
            try {
                Map<String, Object> cat = (Map<String, Object>) stats.get(category);
                if (cat != null) {
                    Object val = cat.get(key);
                    if (val instanceof Number n) return n.longValue();
                }
            } catch (Exception ignored) {}
            return 0;
        }

        private void sendResponse(HttpExchange exchange, int code, String content) throws IOException {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) try {
                    map.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
        }
        return map;
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
        long gap = tickTimes[prev] - tickTimes[tickIndex];
        if (gap <= 0) return 20.0;
        return Math.min(20.0, 1000.0 / ((double) gap / 100.0));
    }

    private void loadProperties() {
        Path propPath = FabricLoader.getInstance().getConfigDir().resolve("awabot.properties");
        Properties props = new Properties();
        if (Files.exists(propPath)) {
            try (InputStream is = Files.newInputStream(propPath)) {
                props.load(is);
                serverPort = Integer.parseInt(props.getProperty("port", "25566"));
                currentPassword = props.getProperty("password", currentPassword);
                safety = Integer.parseInt(props.getProperty("safety", "0"));
                preventMultiBind = Integer.parseInt(props.getProperty("prevent_multi_bind", "1"));
            } catch (Exception e) { e.printStackTrace(); }
        } else {
            // 这里是初始化配置的地方
            props.setProperty("port", "25566");
            props.setProperty("password", currentPassword);
            props.setProperty("safety", "0");
            props.setProperty("prevent_multi_bind", "1");

            try (OutputStream os = Files.newOutputStream(propPath)) {
                String comments = "=== AwaBot Configuration ===\n" +
                        "# port: API Server Port (default 25566)\n" +
                        "# password: API encryption password (recommend 32 chars)\n" +
                        "# safety: 0=No bind needed, 1=Bind required, 2=Bind + Admin approval\n" +
                        "# prevent_multi_bind: 0=Allow multiple binds per QQ, 1=One QQ one player";

                props.store(new OutputStreamWriter(os, StandardCharsets.UTF_8), comments);
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void loadJsonConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("awabot_config.json");
        if (!Files.exists(path)) { saveJsonConfig(); return; }
        try {
            JsonObject root = GSON.fromJson(Files.newBufferedReader(path), JsonObject.class);
            if (root == null) return;
            if (root.has("binds")) {
                JsonObject bindsObj = root.getAsJsonObject("binds");
                for (String qq : bindsObj.keySet()) {
                    List<BoundPlayer> list = GSON.fromJson(bindsObj.get(qq), new TypeToken<List<BoundPlayer>>(){}.getType());
                    playerBinds.put(qq, list);
                }
            }
            if (root.has("explicit_admins"))
                explicitAdmins.addAll(GSON.fromJson(root.get("explicit_admins"), new TypeToken<Set<String>>(){}.getType()));
            if (root.has("banned_qqs"))
                bannedQQs.putAll(GSON.fromJson(root.get("banned_qqs"), new TypeToken<Map<String, Long>>(){}.getType()));
        } catch (Exception e) { e.printStackTrace(); saveJsonConfig(); }
    }

    private static synchronized void saveJsonConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("awabot_config.json");
        try {
            Files.writeString(path, GSON.toJson(new BotConfig(playerBinds, explicitAdmins, bannedQQs)));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void bindPlayerLogic(String qq, String playerName, String uuid) {
        playerBinds.values().forEach(list -> list.removeIf(bp -> bp.uuid().equals(uuid)));
        playerBinds.values().removeIf(List::isEmpty);
        List<BoundPlayer> qqBinds = playerBinds.computeIfAbsent(qq, k -> new ArrayList<>());
        if (preventMultiBind == 1) qqBinds.clear();
        qqBinds.add(new BoundPlayer(playerName, uuid));
    }
}