package top.msu333.title;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()      // 防止 & 被转义为 \u0026
            .create();
    private static final String FILE_NAME = "title_awa.json";

    public AfkConfig afk = new AfkConfig();

    public static class AfkConfig {
        public boolean enabled = true;
        public int seconds = 60;
        public String prefix = "&7[AFK] ";
    }

    public static ModConfig load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        if (Files.exists(configPath)) {
            try {
                String raw = Files.readString(configPath);
                // 去除注释行（以 // 开头）
                StringBuilder builder = new StringBuilder();
                for (String line : raw.split("\\r?\\n")) {
                    String trimmed = line.strip();
                    if (!trimmed.startsWith("//") && !trimmed.isEmpty()) {
                        builder.append(line).append("\n");
                    }
                }
                return GSON.fromJson(builder.toString(), ModConfig.class);
            } catch (Exception e) {
                TitleMod.LOGGER.error("Failed to parse config, using defaults", e);
            }
        }

        // 生成默认配置并保存
        ModConfig config = new ModConfig();
        save(config);
        return config;
    }

    public static void save(ModConfig config) {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(configPath.getParent());

            String json = GSON.toJson(config);
            String commented = "// Title Awa Configuration\n"
                    + "// afk.enabled: Enable/disable AFK prefix\n"
                    + "// afk.seconds: Seconds of inactivity before AFK\n"
                    + "// afk.prefix: Prefix shown in name tag, supports & color codes (e.g. &7[AFK] &r)\n"
                    + json;
            Files.writeString(configPath, commented);
        } catch (IOException e) {
            TitleMod.LOGGER.error("Failed to save config", e);
        }
    }
}