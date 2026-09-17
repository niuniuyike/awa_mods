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
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class AwaBot implements DedicatedServerModInitializer {
    private static String currentPassword = "123456";
    private static boolean weakPassword = false;
    private static int safety = 0;
    private static int preventMultiBind = 1;
    private static int serverPort = 25566;
    private static String bindAddress = "127.0.0.1";
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

    // ---------- 消息配置 ----------
    private static final Map<String, String> DEFAULT_MESSAGES = new LinkedHashMap<>();
    private static final Map<String, String> currentMessages = new ConcurrentHashMap<>();

    static {
        DEFAULT_MESSAGES.put("kick_bind_required", "§c您需要绑定QQ才能进入\n§f绑定码 [§e{code}§f]\n§b请在5min内私聊或群聊@Bot 并发送 /bind server {code}\n§e绑定后即可进入服务器~\n§e该qq会与服务器绑定，不是与bot绑定");
        DEFAULT_MESSAGES.put("kick_banned", "§c您关联的QQ已被服务器封禁！\n§f解封时间: {time}");
        DEFAULT_MESSAGES.put("kick_multi_bind", "§c服务器已开启QQ一对一绑定模式喵！\n§f检测到您的QQ绑定了多个角色，已被系统拦截。\n§e请联系管理员使用 /clean 清理多余的绑定记录~");
        DEFAULT_MESSAGES.put("kick_pending_admin", "§e您的绑定请求正在等待管理员审核，请稍后再试或联系管理员");
        DEFAULT_MESSAGES.put("msg_bind_bot_sent", "§a[QQBot] §f快捷绑定已发送到 Bot，请稍候数据同步~");
        DEFAULT_MESSAGES.put("msg_bind_bot_usage", "§e[QQBot] §f用法: /qqbot bind bot [验证码]\n§7请先在QQ私聊Bot发送 /bind bot 获取验证码");
        DEFAULT_MESSAGES.put("msg_bind_server_already_bound", "§e[QQBot] §f您已绑定过服务器，如需换绑请联系管理员喵~");
        DEFAULT_MESSAGES.put("msg_bind_server_pending", "§e[QQBot] §f您的绑定请求正在等待管理员审核，请耐心等待喵~");
        DEFAULT_MESSAGES.put("msg_bind_server_existing_code", "§a[QQBot] §f您已有有效验证码: §e{code}\n§b请在QQ私聊或群聊@Bot 发送 /bind server {code}");
        DEFAULT_MESSAGES.put("msg_bind_server_new_code", "§a[QQBot] §f您的服务器绑定验证码: §e{code}\n§b请在5分钟内在QQ私聊或群聊@Bot 发送 /bind server {code}\n§e绑定后数据将保存在服务器端喵~");
        DEFAULT_MESSAGES.put("msg_unbind_info", "§e[QQBot] §f若想改变绑定QQ，请联系管理员喵~");
        DEFAULT_MESSAGES.put("msg_admin_force_bound", "§a[QQBot] Force bound {player} to {qq} as Admin.");
        DEFAULT_MESSAGES.put("msg_admin_error_player_not_found", "§c[QQBot] Error: Player not found!");
        DEFAULT_MESSAGES.put("msg_admin_set_admin", "§a[QQBot] Success! Set existing bound player as Admin.");
        DEFAULT_MESSAGES.put("msg_admin_preauthorized", "§a[QQBot] Pre-authorized OpenID as Admin: {target}");
        DEFAULT_MESSAGES.put("msg_admin_remove_admin", "§a[QQBot] Success! Removed Admin: {target}");
        DEFAULT_MESSAGES.put("msg_admin_not_found", "§c[QQBot] Target not found in Admin list.");
        DEFAULT_MESSAGES.put("msg_admin_auto_accepted", "§a[QQBot] Found in pending! Auto-accepted and set Admin: {name}");
        DEFAULT_MESSAGES.put("kick_cleaned", "§c[QQBot] §f您的QQ绑定已被管理员清除，您已被移出服务器\n§e如需重新进入，请再次进服绑定QQ喵~");
    }

    public static Component getMessage(String key, String... replacements) {
        String msg = currentMessages.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key, key));
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return Component.literal(msg);
    }

    private static void generateDefaultMessageFile(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# AwaBot Message Configuration\n");
        sb.append("# Modify the texts below. Use § for color codes and \\n for line breaks.\n");
        sb.append("# Hot-reload: execute /qqbot reload in console after editing.\n");
        sb.append("\n");
        for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
            String comment = switch (entry.getKey()) {
                case "kick_bind_required" -> "Kick message for unbound players";
                case "kick_banned" -> "Kick message for banned QQ";
                case "kick_multi_bind" -> "Kick message when QQ is bound to multiple accounts (prevent_multi_bind=1)";
                case "kick_pending_admin" -> "Kick message while waiting for admin approval (safety=2)";
                case "msg_bind_bot_sent" -> "/qqbot bind bot success";
                case "msg_bind_bot_usage" -> "/qqbot bind bot help";
                case "msg_bind_server_already_bound" -> "/qqbot bind server when already bound";
                case "msg_bind_server_pending" -> "/qqbot bind server when pending approval";
                case "msg_bind_server_existing_code" -> "/qqbot bind server when code still valid";
                case "msg_bind_server_new_code" -> "/qqbot bind server new code generated";
                case "msg_unbind_info" -> "/qqbot unbind info";
                case "msg_admin_force_bound" -> "Console message: force bound ({player}, {qq})";
                case "msg_admin_error_player_not_found" -> "Console message: player not found";
                case "msg_admin_set_admin" -> "Console message: set existing bound player as admin";
                case "msg_admin_preauthorized" -> "Console message: preauthorize OpenID as admin ({target})";
                case "msg_admin_remove_admin" -> "Console message: admin removed ({target})";
                case "msg_admin_not_found" -> "Console message: target not in admin list";
                case "msg_admin_auto_accepted" -> "Console message: auto accepted from pending ({name})";
                case "kick_cleaned" -> "Kick message when binding is cleared by admin (/qqbot clean)";
                default -> "";
            };
            if (!comment.isEmpty()) {
                sb.append("# ").append(comment).append("\n");
            }
            // 将值中的换行符转换为 \n 字面量
            String value = entry.getValue().replace("\n", "\\n");
            sb.append(entry.getKey()).append("=").append(value).append("\n");
            sb.append("\n");
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void loadMessageProperties() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("awabot_messages.properties");
        if (!Files.exists(path)) {
            try {
                generateDefaultMessageFile(path);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (String key : DEFAULT_MESSAGES.keySet()) {
            String val = props.getProperty(key);
            if (val != null) {
                // Properties.load 已经自动将 \n 转义为换行符
                currentMessages.put(key, val);
            } else {
                currentMessages.put(key, DEFAULT_MESSAGES.get(key));
            }
        }
    }

    // ---------- 安全工具 ----------
    private static String generateRandomPassword() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        // 24 字节 -> 32 个 URL-safe 字符，正好也是 32 字节 AES key
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static String newBindCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String newBindCodeUnique() {
        String code;
        do {
            code = newBindCode();
        } while (codeToSession.containsKey(code));
        return code;
    }

    private static final Map<String, Deque<Long>> submitHits = new ConcurrentHashMap<>();
    private static final int MAX_SUBMIT_PER_MIN = 10;

    private static boolean allowSubmit(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> q = submitHits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000L) q.pollFirst();
            if (q.size() >= MAX_SUBMIT_PER_MIN) return false;
            q.addLast(now);
            return true;
        }
    }

    private static final Object BIND_LOCK = new Object();

    // ---------- 核心配置 ----------
    private static void loadCoreProperties() {
        Path propPath = FabricLoader.getInstance().getConfigDir().resolve("awabot.properties");
        Properties props = new Properties();
        if (Files.exists(propPath)) {
            try (InputStream is = Files.newInputStream(propPath)) {
                props.load(is);
                serverPort = Integer.parseInt(props.getProperty("port", "25566"));
                currentPassword = props.getProperty("password", currentPassword);
                safety = Integer.parseInt(props.getProperty("safety", "0"));
                preventMultiBind = Integer.parseInt(props.getProperty("prevent_multi_bind", "1"));
                bindAddress = props.getProperty("bind_address", "127.0.0.1").trim();
                if (bindAddress.isEmpty()) bindAddress = "127.0.0.1";
            } catch (Exception e) { e.printStackTrace(); }
            if (currentPassword == null || currentPassword.length() < 12 || "123456".equals(currentPassword)) {
                weakPassword = true;
                System.err.println("[AwaBot] 【危险】API 密码过弱（默认值或长度 < 12）。请修改 config/awabot.properties 的 password 后重启。");
            }
        } else {
            // 生成默认核心配置文件（随机强密码，不再使用 123456）
            currentPassword = generateRandomPassword();
            props.setProperty("port", "25566");
            props.setProperty("password", currentPassword);
            props.setProperty("bind_address", "127.0.0.1");
            props.setProperty("safety", "0");
            props.setProperty("prevent_multi_bind", "1");
            try (OutputStream os = Files.newOutputStream(propPath)) {
                String comments = "=== AwaBot Core Configuration ===\n"
                        + "# port: API Server Port (default 25566)\n"
                        + "# bind_address: API listen address (default 127.0.0.1 = local only; use 0.0.0.0 for a remote Bot)\n"
                        + "# password: API auth password & encryption key (randomly generated on first start)\n"
                        + "# safety: 0=No bind needed, 1=Bind required, 2=Bind + Admin approval\n"
                        + "# prevent_multi_bind: 0=Allow multiple binds per QQ, 1=One QQ one player";
                props.store(new OutputStreamWriter(os, StandardCharsets.UTF_8), comments);
            } catch (Exception e) { e.printStackTrace(); }
            System.out.println("[AwaBot] 首次启动，已生成随机 API 密码: " + currentPassword);
            System.out.println("[AwaBot] 请把它填入 QQ Bot 配置，并妥善保存本文件。");
        }
    }

    @Override
    public void onInitializeServer() {
        loadCoreProperties();
        loadMessageProperties();
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
                                    player.connection.disconnect(getMessage("kick_banned", "{time}", timeStr));
                                }
                            });
                            return;
                        }
                    }

                    if (preventMultiBind == 1 && playerBinds.get(boundQQ) != null && playerBinds.get(boundQQ).size() > 1) {
                        server.execute(() -> {
                            if (player.connection != null) {
                                player.connection.disconnect(getMessage("kick_multi_bind"));
                            }
                        });
                        return;
                    }

                    return;
                }

                // 3. 未绑定玩家处理
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
                            player.connection.disconnect(getMessage("kick_pending_admin"));
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
                    code = newBindCodeUnique();
                    codeToSession.put(code, new BindSession(name, uuid, code));
                }

                String finalCode = code;
                server.execute(() -> {
                    if (player.connection != null) {
                        player.connection.disconnect(getMessage("kick_bind_required", "{code}", finalCode));
                    }
                });
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("qqbot")
                    .then(Commands.literal("clean")
                            .requires(source -> source.getEntity() == null) // 仅控制台
                            .then(Commands.argument("player", StringArgumentType.string())
                                    .executes(context -> {
                                        String target = StringArgumentType.getString(context, "player");
                                        if (performClean(target)) {
                                            context.getSource().sendSystemMessage(Component.literal("§a[QQBot] Binding cleared, player kicked, Admin revoked for: " + target));
                                        } else {
                                            context.getSource().sendSystemMessage(Component.literal("§c[QQBot] No binding found for: " + target));
                                        }
                                        return 1;
                                    })))
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
                                                    context.getSource().sendSystemMessage(getMessage("msg_admin_error_player_not_found"));
                                                    return 0;
                                                }

                                                bindPlayerLogic(forceQQ, targetName, uuid);
                                                explicitAdmins.add(forceQQ);

                                                synchronized (pendingAdminApprovals) {
                                                    pendingAdminApprovals.removeIf(s -> s.uuid.equals(uuid) || s.qq.equals(forceQQ));
                                                }
                                                codeToSession.entrySet().removeIf(e -> e.getValue().uuid.equals(uuid));

                                                saveJsonConfig();
                                                context.getSource().sendSystemMessage(getMessage("msg_admin_force_bound", "{player}", targetName, "{qq}", forceQQ));
                                                return 1;
                                            }))
                                    .executes(context -> {
                                        final String target = StringArgumentType.getString(context, "target");
                                        boolean handled = false;

                                        for (Map.Entry<String, List<BoundPlayer>> entry : playerBinds.entrySet()) {
                                            if (entry.getValue().stream().anyMatch(bp -> bp.name().equalsIgnoreCase(target))) {
                                                explicitAdmins.add(entry.getKey());
                                                saveJsonConfig();
                                                context.getSource().sendSystemMessage(getMessage("msg_admin_set_admin"));
                                                handled = true;
                                                break;
                                            }
                                        }

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
                                                        context.getSource().sendSystemMessage(getMessage("msg_admin_auto_accepted", "{name}", s.name));
                                                        handled = true;
                                                        break;
                                                    }
                                                }
                                            }
                                        }

                                        if (!handled) {
                                            explicitAdmins.add(target);
                                            saveJsonConfig();
                                            context.getSource().sendSystemMessage(getMessage("msg_admin_preauthorized", "{target}", target));
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
                                            context.getSource().sendSystemMessage(getMessage("msg_admin_remove_admin", "{target}", targetQQ));
                                        } else {
                                            context.getSource().sendSystemMessage(getMessage("msg_admin_not_found"));
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("bind")
                            .then(Commands.literal("bot")
                                    .then(Commands.argument("code", StringArgumentType.string())
                                            .executes(context -> {
                                                String code = StringArgumentType.getString(context, "code").replace("[", "").replace("]", "").trim();
                                                ServerPlayer player = context.getSource().getPlayer();
                                                if (player != null) {
                                                    pendingBinds.put(player.getName().getString(), code);
                                                    context.getSource().sendSystemMessage(getMessage("msg_bind_bot_sent"));
                                                }
                                                return 1;
                                            }))
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayer();
                                        if (player != null) {
                                            context.getSource().sendSystemMessage(getMessage("msg_bind_bot_usage"));
                                        }
                                        return 1;
                                    }))
                            .then(Commands.literal("server")
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayer();
                                        if (player == null) return 0;

                                        String uuid = player.getStringUUID();
                                        String name = player.getName().getString();

                                        boolean alreadyBound = false;
                                        for (Map.Entry<String, List<BoundPlayer>> entry : playerBinds.entrySet()) {
                                            if (entry.getValue().stream().anyMatch(bp -> bp.uuid().equals(uuid))) {
                                                alreadyBound = true;
                                                break;
                                            }
                                        }

                                        if (alreadyBound) {
                                            context.getSource().sendSystemMessage(getMessage("msg_bind_server_already_bound"));
                                            return 1;
                                        }

                                        synchronized (pendingAdminApprovals) {
                                            for (BindSession s : pendingAdminApprovals) {
                                                if (s.uuid.equals(uuid)) {
                                                    context.getSource().sendSystemMessage(getMessage("msg_bind_server_pending"));
                                                    return 1;
                                                }
                                            }
                                        }

                                        codeToSession.entrySet().removeIf(e -> e.getValue().expireTime < System.currentTimeMillis());

                                        for (Map.Entry<String, BindSession> entry : codeToSession.entrySet()) {
                                            if (entry.getValue().uuid.equals(uuid)) {
                                                String existingCode = entry.getKey();
                                                context.getSource().sendSystemMessage(getMessage("msg_bind_server_existing_code", "{code}", existingCode));
                                                return 1;
                                            }
                                        }

                                        String code = newBindCodeUnique();
                                        codeToSession.put(code, new BindSession(name, uuid, code));

                                        context.getSource().sendSystemMessage(getMessage("msg_bind_server_new_code", "{code}", code));
                                        return 1;
                                    })))
                    .then(Commands.literal("unbind")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    context.getSource().sendSystemMessage(getMessage("msg_unbind_info"));
                                }
                                return 1;
                            }))
                    .then(Commands.literal("help")
                            .executes(context -> {
                                context.getSource().sendSystemMessage(Component.literal("§a========== QQBot Commands =========="));
                                context.getSource().sendSystemMessage(Component.literal("§e/qqbot help §7- Show this help"));
                                context.getSource().sendSystemMessage(Component.literal("§e/qqbot bind bot [code] §7- Verify/link your QQ in-game (code from QQ Bot)"));
                                context.getSource().sendSystemMessage(Component.literal("§e/qqbot bind server §7- Get a code to bind your QQ to this server"));
                                context.getSource().sendSystemMessage(Component.literal("§e/qqbot unbind §7- Show info about changing your bound QQ"));
                                context.getSource().sendSystemMessage(Component.literal("§e/qqbot admin <player> [qq] §7- (Console) Set/force-bind a player as Admin"));
                                context.getSource().sendSystemMessage(Component.literal("§e/qqbot unadmin <player/qq> §7- (Console) Remove an Admin"));
                                context.getSource().sendSystemMessage(Component.literal("§e/qqbot clean <player/qq> §7- (Console) Clear binding, kick player & revoke Admin"));
                                context.getSource().sendSystemMessage(Component.literal("§e/qqbot reload §7- (Console) Reload message config"));
                                return 1;
                            }))
                    .then(Commands.literal("reload")
                            .requires(source -> source.getEntity() == null) // 仅控制台
                            .executes(context -> {
                                // 检查核心配置是否变化
                                Path corePath = FabricLoader.getInstance().getConfigDir().resolve("awabot.properties");
                                if (!Files.exists(corePath)) {
                                    context.getSource().sendSystemMessage(Component.literal("§c[QQBot] Core config file not found. Cannot hot-reload."));
                                    return 0;
                                }
                                Properties coreProps = new Properties();
                                try (Reader reader = Files.newBufferedReader(corePath)) {
                                    coreProps.load(reader);
                                } catch (IOException e) {
                                    context.getSource().sendSystemMessage(Component.literal("§c[QQBot] Failed to read core config."));
                                    return 0;
                                }
                                int newSafety = Integer.parseInt(coreProps.getProperty("safety", String.valueOf(safety)));
                                int newPrevent = Integer.parseInt(coreProps.getProperty("prevent_multi_bind", String.valueOf(preventMultiBind)));
                                int newPort = Integer.parseInt(coreProps.getProperty("port", String.valueOf(serverPort)));
                                String newPassword = coreProps.getProperty("password", currentPassword);
                                String newBind = coreProps.getProperty("bind_address", bindAddress).trim();
                                if (newSafety != safety || newPrevent != preventMultiBind || newPort != serverPort
                                        || !newPassword.equals(currentPassword) || !newBind.equals(bindAddress)) {
                                    context.getSource().sendSystemMessage(Component.literal("§c[QQBot] 检测到 safety, prevent_multi_bind, port, bind_address 或 password 发生变更！这些修改需要重启服务器才能生效，本次热加载已取消。"));
                                    return 0;
                                }

                                // 重载消息文件
                                loadMessageProperties();
                                context.getSource().sendSystemMessage(Component.literal("§a[QQBot] 消息配置已热加载成功。"));
                                return 1;
                            }))
            );
        });
    }

    // ---------- HTTP 服务器及相关方法 ----------
    private void startHttpServer() {
        if (weakPassword) {
            System.err.println("[AwaBot] 出于安全考虑，拒绝以弱密码启动 API 服务。请修改 config/awabot.properties 的 password 后重启。");
            return;
        }
        try {
            InetSocketAddress endpoint = new InetSocketAddress(InetAddress.getByName(bindAddress), serverPort);
            httpServer = HttpServer.create(endpoint, 0);
            httpServer.createContext("/api/server", new ApiHandler("server"));
            httpServer.createContext("/api/tps", new ApiHandler("tps"));
            httpServer.createContext("/api/player", new ApiHandler("player"));
            httpServer.createContext("/api/action", new ApiHandler("action"));
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
            System.out.println("[AwaBot] API Server started on http://" + bindAddress + ":" + serverPort);
        } catch (Exception e) { e.printStackTrace(); }
    }

    static class ApiHandler implements HttpHandler {
        private final String type;
        public ApiHandler(String t) { this.type = t; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String token = auth == null ? null : auth.trim();
            if (token == null || !MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    currentPassword.getBytes(StandardCharsets.UTF_8))) {
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
                            String remoteIp = exchange.getRemoteAddress().getAddress() == null
                                    ? "unknown" : exchange.getRemoteAddress().getAddress().getHostAddress();
                            if (!allowSubmit(remoteIp)) {
                                yield "{\"error\":\"Too many attempts, please retry later\"}";
                            }
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
                            String target = params.get("target");
                            if (performClean(target)) { yield "{\"status\":\"success\"}"; }
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

                        Set<String> allNames = new HashSet<>();
                        mcServer.getPlayerList().getPlayers().forEach(p -> allNames.add(p.getName().getString()));
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
                        playerBinds.values().forEach(list -> list.forEach(bp -> allNames.add(bp.name())));

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
                            yield getPlayerStatsByName(exactMatches.get(0));
                        } else if (containsMatches.size() == 1) {
                            yield getPlayerStatsByName(containsMatches.get(0));
                        } else if (containsMatches.size() > 1) {
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
                return "{\"error\":\"Internal Error\"}";
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

    // v2 载荷信封: MAGIC(4) + PBKDF2 迭代次数(4, 大端) + salt(16) + iv(12) + AES-GCM 密文||tag
    private static final byte[] BLOB_MAGIC_V2 = {'A', 'W', '0', '2'};
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_SALT_LEN = 16;

    private static SecretKeySpec deriveKey(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private static String encrypt(String data, String key) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[PBKDF2_SALT_LEN];
        byte[] iv = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(iv);
        SecretKeySpec secretKey = deriveKey(key, salt, PBKDF2_ITERATIONS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[BLOB_MAGIC_V2.length + 4 + salt.length + iv.length + cipherText.length];
        int p = 0;
        System.arraycopy(BLOB_MAGIC_V2, 0, combined, p, BLOB_MAGIC_V2.length); p += BLOB_MAGIC_V2.length;
        combined[p++] = (byte) (PBKDF2_ITERATIONS >>> 24);
        combined[p++] = (byte) (PBKDF2_ITERATIONS >>> 16);
        combined[p++] = (byte) (PBKDF2_ITERATIONS >>> 8);
        combined[p++] = (byte) PBKDF2_ITERATIONS;
        System.arraycopy(salt, 0, combined, p, salt.length); p += salt.length;
        System.arraycopy(iv, 0, combined, p, iv.length); p += iv.length;
        System.arraycopy(cipherText, 0, combined, p, cipherText.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static double calculateTps() {
        int prev = (tickIndex - 1 + 100) % 100;
        long gap = tickTimes[prev] - tickTimes[tickIndex];
        if (gap <= 0) return 20.0;
        return Math.min(20.0, 1000.0 / ((double) gap / 100.0));
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
                    if (list != null) playerBinds.put(qq, new CopyOnWriteArrayList<>(list));
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
        synchronized (BIND_LOCK) {
            playerBinds.values().forEach(list -> list.removeIf(bp -> bp.uuid().equals(uuid)));
            playerBinds.values().removeIf(List::isEmpty);
            List<BoundPlayer> qqBinds = playerBinds.computeIfAbsent(qq, k -> new CopyOnWriteArrayList<>());
            if (preventMultiBind == 1) qqBinds.clear();
            qqBinds.add(new BoundPlayer(playerName, uuid));
        }
    }
    // ---------- 统一 clean 逻辑：清绑定 + 踢出玩家 + 收回该 QQ 的 admin ----------
    private static ServerPlayer findOnlinePlayerByUuid(String uuid) {
        if (mcServer == null) return null;
        for (ServerPlayer p : mcServer.getPlayerList().getPlayers()) {
            if (p.getStringUUID().equals(uuid)) return p;
        }
        return null;
    }

    private static boolean performClean(String target) {
        // 0) 先解析 target 关联的 QQ（target 可能是 QQ 号，也可能是绑定的玩家名）
        String affectedQQ = null;
        if (playerBinds.containsKey(target)) {
            affectedQQ = target;
        } else {
            outer:
            for (Map.Entry<String, List<BoundPlayer>> entry : playerBinds.entrySet()) {
                for (BoundPlayer bp : entry.getValue()) {
                    if (bp.name().equalsIgnoreCase(target)) {
                        affectedQQ = entry.getKey();
                        break outer;
                    }
                }
            }
        }

        // 1) 踢出在线的受影响玩家（务必在删绑定之前收集 uuid，之后映射就没了）
        List<String> uuidsToKick = new ArrayList<>();
        if (playerBinds.containsKey(target)) {
            playerBinds.get(target).forEach(bp -> uuidsToKick.add(bp.uuid()));
        } else {
            for (List<BoundPlayer> list : playerBinds.values()) {
                for (BoundPlayer bp : list) {
                    if (bp.name().equalsIgnoreCase(target)) uuidsToKick.add(bp.uuid());
                }
            }
        }
        if (affectedQQ != null) {
            synchronized (pendingAdminApprovals) {
                for (BindSession s : pendingAdminApprovals) {
                    if (s.qq != null && s.qq.equals(affectedQQ)) uuidsToKick.add(s.uuid);
                }
            }
        }
        for (String uuid : uuidsToKick) {
            final ServerPlayer online = findOnlinePlayerByUuid(uuid);
            if (online != null) {
                // 断连必须在服务器主线程执行，与 JOIN 里的踢人写法一致
                mcServer.execute(() -> {
                    if (online.connection != null) {
                        online.connection.disconnect(getMessage("kick_cleaned"));
                    }
                });
            }
        }

        boolean removed = false;

        // 2) 清绑定（兼容“按 QQ 删整条 / 按玩家名删一条”两种 target，逻辑与原有 HTTP clean 相同）
        for (Iterator<Map.Entry<String, List<BoundPlayer>>> it = playerBinds.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, List<BoundPlayer>> entry = it.next();
            if (entry.getKey().equals(target)) {
                it.remove();
                removed = true;
            } else if (entry.getValue().removeIf(bp -> bp.name().equalsIgnoreCase(target))) {
                removed = true;
                if (entry.getValue().isEmpty()) it.remove();
            }
        }

        // 3) 清待审核记录
        synchronized (pendingAdminApprovals) {
            removed = pendingAdminApprovals.removeIf(s -> s.name.equalsIgnoreCase(target)
                    || (s.qq != null && s.qq.equals(target))) || removed;
        }

        // 4) 检查该 QQ 是否有 bot admin，有则一并收回
        if (affectedQQ != null && explicitAdmins.remove(affectedQQ)) {
            removed = true;
        }

        if (removed) saveJsonConfig();
        return removed;
    }
}