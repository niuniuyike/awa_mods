package top.msu333;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*; // 关键修复：导入了 Arrays 所在的包
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class AwaBot implements DedicatedServerModInitializer {
    // 密钥建议设置为 32 位以支持 AES-256
    private static String currentPassword = "admin_secure_key_32bytes_long!!";
    private static MinecraftServer mcServer;
    private static HttpServer httpServer;
    private static final long[] tickTimes = new long[100];
    private static int tickIndex = 0;
    private static int serverPort = 25566;
    private static final Gson GSON = new GsonBuilder().create();

    private static final Map<String, String> pendingBinds = new ConcurrentHashMap<>();
    private static final List<String> pendingUnbinds = Collections.synchronizedList(new ArrayList<>());

    // 数据模型 (Java 25 Record)
    public record BotConfig(int port, String default_password) {}
    public record ServerResponse(int count, List<String> players, Map<String, String> binds, List<String> unbinds) {}
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
                                            context.getSource().sendSystemMessage(Component.literal("Bind request sent: " + code));
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("unbind")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null) {
                                    pendingUnbinds.add(player.getName().getString());
                                    context.getSource().sendSystemMessage(Component.literal("Unbind request sent"));
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

            // 使用 Java 25 虚拟线程提升高并发隐私处理性能
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
            System.out.println("AwaBot API with AES-GCM Privacy enabled on port " + serverPort);
        } catch (IOException e) { e.printStackTrace(); }
    }

    static class ApiHandler implements HttpHandler {
        private final String type;
        public ApiHandler(String t) { this.type = t; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.trim().equals(currentPassword)) {
                sendResponse(exchange, 401, "{\"error\":\"401\"}");
                return;
            }

            try {
                String rawJson = switch (type) {
                    case "tps" -> GSON.toJson(new TpsResponse(calculateTps()));
                    case "server" -> {
                        List<String> playerNames = mcServer.getPlayerList().getPlayers().stream()
                                .map(p -> p.getName().getString()).toList();
                        Map<String, String> binds = new HashMap<>(pendingBinds);
                        pendingBinds.keySet().removeAll(binds.keySet());
                        List<String> unbinds;
                        synchronized (pendingUnbinds) {
                            unbinds = new ArrayList<>(pendingUnbinds);
                            pendingUnbinds.clear();
                        }
                        yield GSON.toJson(new ServerResponse(playerNames.size(), playerNames, binds, unbinds));
                    }
                    default -> "{}";
                };

                // 隐私加密逻辑
                String encrypted = encrypt(rawJson, currentPassword);
                String finalResponse = GSON.toJson(new SecurePayload(encrypted, System.currentTimeMillis()));

                sendResponse(exchange, 200, finalResponse);
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "{\"error\":\"Internal Security Error\"}");
            }
        }

        private void sendResponse(HttpExchange exchange, int code, String content) throws IOException {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    // --- 加密隐私方法 ---
    private static String encrypt(String data, String key) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        // 关键修复点：Arrays.copyOf 现在由于 import java.util.* 可以被识别了
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