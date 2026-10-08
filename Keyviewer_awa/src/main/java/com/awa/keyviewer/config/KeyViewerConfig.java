package com.awa.keyviewer.config;

import com.awa.keyviewer.KeyViewerMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 全部设置项。颜色都是 ARGB（0xAARRGGBB）。
 */
public class KeyViewerConfig {
	public static final int DEF_BORDER_COLOR = 0xFFFFFFFF;
	public static final int DEF_PRESSED_COLOR = 0xFFF2F2F2;
	public static final int DEF_RELEASED_COLOR = 0xC0181818;
	public static final float DEF_OPACITY = 1.0F;
	public static final float DEF_TEXT_OPACITY = 1.0F;
	public static final float DEF_PRESSED_OPACITY = 1.0F;
	public static final float DEF_RELEASED_OPACITY = 1.0F;
	public static final float DEF_BORDER_OPACITY = 1.0F;
	public static final float DEF_POS_X = 0.04F;
	public static final float DEF_POS_Y = 0.70F;
	public static final float DEF_SIZE = 1.0F;
	public static final float DEF_SENSITIVITY = 1.0F;

	// 显示开关
	public boolean showMovementKeys = true;
	public boolean showMouseButtons = true;
	public boolean showJump = true;
	/** 是否让 HUD 跟随鼠标移动（关掉就固定在设定位置）。 */
	public boolean swayEnabled = true;

	// 颜色
	public int borderColor = DEF_BORDER_COLOR;
	public int pressedColor = DEF_PRESSED_COLOR;
	public int releasedColor = DEF_RELEASED_COLOR;

	// 透明度
	public float opacity = DEF_OPACITY;
	public float textOpacity = DEF_TEXT_OPACITY;
	/** 键位亮起（按下）时的透明度。 */
	public float pressedOpacity = DEF_PRESSED_OPACITY;
	/** 键位松开时的透明度。 */
	public float releasedOpacity = DEF_RELEASED_OPACITY;
	/** 边框透明度。 */
	public float borderOpacity = DEF_BORDER_OPACITY;

	// 位置（0~1，相对屏幕，指 HUD 左上角）
	public float posX = DEF_POS_X;
	public float posY = DEF_POS_Y;

	// 大小（缩放倍率）
	public float size = DEF_SIZE;

	// 灵敏度（鼠标速度 -> HUD 位移）
	public float sensitivity = DEF_SENSITIVITY;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(KeyViewerMod.MOD_ID + ".json");
	}

	public static KeyViewerConfig load() {
		Path path = configPath();

		if (Files.exists(path)) {
			try {
				KeyViewerConfig loaded = GSON.fromJson(Files.readString(path), KeyViewerConfig.class);

				if (loaded != null) {
					loaded.sanitize();
					return loaded;
				}
			} catch (Exception e) {
				KeyViewerMod.LOGGER.warn("[AWA KeyViewer] failed to read config, using defaults", e);
			}
		}

		KeyViewerConfig fresh = new KeyViewerConfig();
		fresh.save();
		return fresh;
	}

	public void save() {
		Path path = configPath();

		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException e) {
			KeyViewerMod.LOGGER.warn("[AWA KeyViewer] failed to save config", e);
		}
	}

	public void reset() {
		showMovementKeys = true;
		showMouseButtons = true;
		showJump = true;
		swayEnabled = true;
		borderColor = DEF_BORDER_COLOR;
		pressedColor = DEF_PRESSED_COLOR;
		releasedColor = DEF_RELEASED_COLOR;
		opacity = DEF_OPACITY;
		textOpacity = DEF_TEXT_OPACITY;
		pressedOpacity = DEF_PRESSED_OPACITY;
		releasedOpacity = DEF_RELEASED_OPACITY;
		borderOpacity = DEF_BORDER_OPACITY;
		posX = DEF_POS_X;
		posY = DEF_POS_Y;
		size = DEF_SIZE;
		sensitivity = DEF_SENSITIVITY;
	}

	private void sanitize() {
		opacity = clamp(opacity, 0.0F, 1.0F);
		textOpacity = clamp(textOpacity, 0.0F, 1.0F);
		pressedOpacity = clamp(pressedOpacity, 0.0F, 1.0F);
		releasedOpacity = clamp(releasedOpacity, 0.0F, 1.0F);
		borderOpacity = clamp(borderOpacity, 0.0F, 1.0F);
		posX = clamp(posX, 0.0F, 1.0F);
		posY = clamp(posY, 0.0F, 1.0F);
		size = clamp(size, 0.5F, 3.0F);
		sensitivity = clamp(sensitivity, 0.0F, 2.0F);
	}

	private static float clamp(float value, float min, float max) {
		if (Float.isNaN(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
