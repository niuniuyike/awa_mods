package com.awa.keyviewer.hud;

import com.awa.keyviewer.KeyViewerMod;
import com.awa.keyviewer.config.KeyViewerConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Map;

/**
 * 键位显示 HUD。
 *
 * 只在“游玩状态”（鼠标被抓取、没有光标）时显示，ESC / 聊天 / 背包等界面一打开就隐藏。
 *
 * 位移：位置会跟随鼠标当前速度偏出去，鼠标停下就拉回原位（往回收更快，有拉拽感）。
 * 速度来自 mixin 抓到的**原始鼠标增量**，不能用 xpos()/ypos()（绝对光标坐标会被窗口边缘钳住，
 * 会导致鼠标一直在动、HUD 却回原点）。
 */
public class KeyViewerHud implements HudElement {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(KeyViewerMod.MOD_ID, "keyviewer");

	/** 基础方块边长（GUI 像素）。 */
	private static final double BASE_KEY = 20.0D;
	/** 方块之间的空隙。 */
	private static final double BASE_GAP = 4.0D;
	/** 位移增益基准：灵敏度 1.0 时，鼠标速度(px/s) * 0.03 = 位移像素。 */
	private static final double SWAY_GAIN = 0.03D;
	/**
	 * 测速窗口（秒）。
	 *
	 * HUD 每帧都会跑一次，但鼠标事件不是每帧都有（帧率高于鼠标轮询率），
	 * 所以单帧增量经常是 0，不能直接当速度用。按固定时间窗口求平均就稳了。
	 */
	private static final double MEASURE_WINDOW_SECONDS = 0.12D;
	/** 速度跟上（加速）的速率。 */
	private static final double VELOCITY_FOLLOW = 10.0D;
	/** 速度回落（减速）的速率，比跟上快一点。 */
	private static final double VELOCITY_RETURN = 16.0D;
	/** 位移跟出去的速度。 */
	private static final double OFFSET_FOLLOW = 12.0D;
	/** 位移往回收的速度（越大回中的拉拽感越强）。 */
	private static final double OFFSET_RETURN = 20.0D;
	/** 窗口平均速度超过这个值就当成异常数据，忽略掉。 */
	private static final double TELEPORT_SPEED = 15000.0D;

	/** 按键亮起 / 熄灭的渐变速度（渐入渐出）。 */
	private static final float PRESS_FADE_IN = 16.0F;
	private static final float PRESS_FADE_OUT = 10.0F;
	/** 文字固定白色：按下时不反色。 */
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	private static final Map<KeyId, Float> PRESS_ANIMATION = new EnumMap<>(KeyId.class);

	static {
		for (KeyId id : KeyId.values()) {
			PRESS_ANIMATION.put(id, 0.0F);
		}
	}

	// 位移状态（整个 mod 只有一个 HUD 实例，用静态字段方便设置界面重置）
	private static double velocityX;
	private static double velocityY;
	private static double offsetX;
	private static double offsetY;
	private static double measuredSpeedX;
	private static double measuredSpeedY;
	private static double windowDeltaX;
	private static double windowDeltaY;
	private static long windowStartNanos;
	private static long lastFrameNanos;
	private static long lastDrawNanos;
	private static boolean primed;
	private static int lastGuiWidth = -1;
	private static int lastGuiHeight = -1;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		KeyViewerConfig config = KeyViewerMod.config();
		Minecraft client = Minecraft.getInstance();

		if (config == null || client.player == null || client.level == null) {
			resetOffsets();
			return;
		}

		if (!client.mouseHandler.isMouseGrabbed()) {
			// 鼠标出现（ESC / 聊天 / 背包）：隐藏，位移自然回中
			unprime();
			return;
		}

		// 改变窗口大小 / 分辨率时重新取基准，避免数据突变把 HUD 顶飞
		if (graphics.guiWidth() != lastGuiWidth || graphics.guiHeight() != lastGuiHeight) {
			lastGuiWidth = graphics.guiWidth();
			lastGuiHeight = graphics.guiHeight();
			unprime();
			velocityX = 0.0D;
			velocityY = 0.0D;
		}

		if (config.swayEnabled) {
			updateSway(config);
		} else {
			// 关掉位移跟随：直接固定在设定位置
			resetOffsets();
			lastGuiWidth = graphics.guiWidth();
			lastGuiHeight = graphics.guiHeight();
		}

		float animationDelta = nextAnimationDelta();

		Layout layout = Layout.of(config);

		if (layout == null) {
			return;
		}

		int x = (int) Math.round(config.posX * graphics.guiWidth() + offsetX);
		int y = (int) Math.round(config.posY * graphics.guiHeight() + offsetY);
		// 允许超出屏幕，但至少留一块按键宽度可见，永远不会整块消失
		x = clamp(x, -(layout.totalWidth - layout.key), graphics.guiWidth() - layout.key);
		y = clamp(y, -(layout.totalHeight - layout.key), graphics.guiHeight() - layout.key);

		draw(graphics, client, config, layout, x, y, animationDelta);
	}

	/** 把 HUD 位移归零（设置界面的“重置”用）。 */
	public static void resetOffsets() {
		velocityX = 0.0D;
		velocityY = 0.0D;
		offsetX = 0.0D;
		offsetY = 0.0D;
		measuredSpeedX = 0.0D;
		measuredSpeedY = 0.0D;
		windowDeltaX = 0.0D;
		windowDeltaY = 0.0D;
		windowStartNanos = 0L;
		lastFrameNanos = 0L;
		primed = false;
		MouseMotion.clear();
	}

	/** 设置界面里的预览：按可用空间等比缩放后绘制。 */
	public static void drawPreview(GuiGraphicsExtractor graphics, Minecraft client, KeyViewerConfig config,
			int centerX, int top, int maxWidth, int maxHeight) {
		Layout layout = Layout.of(config);

		if (layout == null) {
			return;
		}

		float scale = Math.min(1.0F, Math.min((float) maxWidth / layout.totalWidth, (float) maxHeight / layout.totalHeight));

		graphics.pose().pushMatrix();
		graphics.pose().translate(centerX - layout.totalWidth * scale / 2.0F, top);
		graphics.pose().scale(scale, scale);
		draw(graphics, client, config, layout, 0, 0, 1.0F / 60.0F);
		graphics.pose().popMatrix();
	}

	public static void draw(GuiGraphicsExtractor graphics, Minecraft client, KeyViewerConfig config,
			Layout layout, int originX, int originY, float animationDelta) {
		int key = layout.key;
		int gap = layout.gap;
		int step = key + gap;

		if (layout.rowW >= 0) {
			box(graphics, client, config, originX + step, originY + layout.rowW, key, key, "W", KeyId.W,
					client.options.keyUp.isDown(), animationDelta);
		}

		if (layout.rowAsd >= 0) {
			box(graphics, client, config, originX, originY + layout.rowAsd, key, key, "A", KeyId.A,
					client.options.keyLeft.isDown(), animationDelta);
			box(graphics, client, config, originX + step, originY + layout.rowAsd, key, key, "S", KeyId.S,
					client.options.keyDown.isDown(), animationDelta);
			box(graphics, client, config, originX + step * 2, originY + layout.rowAsd, key, key, "D", KeyId.D,
					client.options.keyRight.isDown(), animationDelta);
		}

		if (layout.rowMouse >= 0) {
			// 左右键是横着的长方形，占满整行，中间的空隙正好在 S 的正下方
			int half = layout.totalWidth / 2;
			int middleGap = Math.max(2, gap);
			int leftWidth = half - middleGap / 2;
			int rightX = originX + half + middleGap / 2;
			int rightWidth = layout.totalWidth - half - middleGap / 2;

			box(graphics, client, config, originX, originY + layout.rowMouse, leftWidth, key, "L", KeyId.L,
					client.mouseHandler.isLeftPressed(), animationDelta);
			box(graphics, client, config, rightX, originY + layout.rowMouse, rightWidth, key, "R", KeyId.R,
					client.mouseHandler.isRightPressed(), animationDelta);
		}

		if (layout.rowSpace >= 0) {
			box(graphics, client, config, originX, originY + layout.rowSpace, layout.totalWidth, layout.spaceHeight,
					null, KeyId.SPACE, client.options.keyJump.isDown(), animationDelta);
		}
	}

	private static void box(GuiGraphicsExtractor graphics, Minecraft client, KeyViewerConfig config,
			int x, int y, int width, int height, String label, KeyId id, boolean pressed, float animationDelta) {
		float lit = animatePress(id, pressed, animationDelta);
		float opacity = clampF(config.opacity, 0.0F, 1.0F);
		int releasedFill = applyOpacity(config.releasedColor, opacity * clampF(config.releasedOpacity, 0.0F, 1.0F));
		int pressedFill = applyOpacity(config.pressedColor, opacity * clampF(config.pressedOpacity, 0.0F, 1.0F));
		int fill = lerpColor(releasedFill, pressedFill, lit);
		int border = applyOpacity(config.borderColor, opacity * clampF(config.borderOpacity, 0.0F, 1.0F));
		int thickness = Math.max(1, (int) Math.round(Math.min(width, height) * 0.06D));

		graphics.fill(x, y, x + width, y + height, fill);
		// 细边框：上下左右各一条 1~2 像素的线
		graphics.fill(x, y, x + width, y + thickness, border);
		graphics.fill(x, y + height - thickness, x + width, y + height, border);
		graphics.fill(x, y + thickness, x + thickness, y + height - thickness, border);
		graphics.fill(x + width - thickness, y + thickness, x + width, y + height - thickness, border);

		if (label != null) {
			Font font = client.font;
			int textColor = applyOpacity(TEXT_COLOR, clampF(config.textOpacity, 0.0F, 1.0F));
			// 文字跟着方块大小一起缩放（以基础方块 20 像素为 1 倍）
			float textScale = clampF((float) height / (float) Math.round(BASE_KEY), 0.25F, 4.0F);

			graphics.pose().pushMatrix();
			graphics.pose().translate(x + width / 2.0F, y + height / 2.0F);
			graphics.pose().scale(textScale, textScale);
			graphics.centeredText(font, label, 0, -font.lineHeight / 2 + 1, textColor);
			graphics.pose().popMatrix();
		}
	}

	/** 按下 / 松开的渐入渐出：返回 0（暗）~1（亮）。 */
	private static float animatePress(KeyId id, boolean pressed, float delta) {
		float current = PRESS_ANIMATION.getOrDefault(id, 0.0F);
		float target = pressed ? 1.0F : 0.0F;
		float rate = pressed ? PRESS_FADE_IN : PRESS_FADE_OUT;
		float next = current + (target - current) * (1.0F - (float) Math.exp(-Math.max(0.001F, delta) * rate));

		if (Math.abs(next - target) < 0.002F) {
			next = target;
		}

		PRESS_ANIMATION.put(id, next);
		return next;
	}

	private static float nextAnimationDelta() {
		long now = System.nanoTime();

		if (lastDrawNanos == 0L) {
			lastDrawNanos = now;
			return 1.0F / 60.0F;
		}

		float delta = (float) ((now - lastDrawNanos) / 1_000_000_000.0D);
		lastDrawNanos = now;
		return Math.max(0.001F, Math.min(0.1F, delta));
	}

	private void updateSway(KeyViewerConfig config) {
		long now = System.nanoTime();

		if (!primed) {
			primed = true;
			lastFrameNanos = now;
			windowStartNanos = now;
			windowDeltaX = 0.0D;
			windowDeltaY = 0.0D;
			measuredSpeedX = 0.0D;
			measuredSpeedY = 0.0D;
			MouseMotion.clear();
			return;
		}

		double dt = (now - lastFrameNanos) / 1_000_000_000.0D;
		lastFrameNanos = now;

		if (dt <= 0.0D) {
			return;
		}

		dt = Math.max(0.0005D, Math.min(0.1D, dt));

		// 取走 MC 这一帧真正消费掉的原始鼠标增量（不受光标钳制影响）
		windowDeltaX += MouseMotion.takeX();
		windowDeltaY += MouseMotion.takeY();

		// 够一个测速窗口就重新算一次平均速度（没够就沿用上一次的值）
		double window = (now - windowStartNanos) / 1_000_000_000.0D;

		if (window >= MEASURE_WINDOW_SECONDS) {
			double rawSpeedX = windowDeltaX / window;
			double rawSpeedY = windowDeltaY / window;

			if (Math.abs(rawSpeedX) > TELEPORT_SPEED || Math.abs(rawSpeedY) > TELEPORT_SPEED) {
				rawSpeedX = 0.0D;
				rawSpeedY = 0.0D;
			}

			measuredSpeedX = rawSpeedX;
			measuredSpeedY = rawSpeedY;
			windowStartNanos = now;
			windowDeltaX = 0.0D;
			windowDeltaY = 0.0D;
		}

		// 速度平滑：加速时慢慢跟上，减速时回落快一点
		double rateVx = Math.abs(measuredSpeedX) < Math.abs(velocityX) ? VELOCITY_RETURN : VELOCITY_FOLLOW;
		double rateVy = Math.abs(measuredSpeedY) < Math.abs(velocityY) ? VELOCITY_RETURN : VELOCITY_FOLLOW;
		velocityX += (measuredSpeedX - velocityX) * (1.0D - Math.exp(-dt * rateVx));
		velocityY += (measuredSpeedY - velocityY) * (1.0D - Math.exp(-dt * rateVy));

		// 位移正比于鼠标速度：灵敏度用平方曲线，低端更细腻（下限更小、更弱）
		double gain = SWAY_GAIN * config.sensitivity * config.sensitivity;
		double targetX = velocityX * gain;
		double targetY = velocityY * gain;

		// 是往回收（目标比当前小）还是往外走：往回收用更快的速度，做出明显的拉拽感
		double rateX = Math.abs(targetX) < Math.abs(offsetX) ? OFFSET_RETURN : OFFSET_FOLLOW;
		double rateY = Math.abs(targetY) < Math.abs(offsetY) ? OFFSET_RETURN : OFFSET_FOLLOW;

		offsetX += (targetX - offsetX) * (1.0D - Math.exp(-dt * rateX));
		offsetY += (targetY - offsetY) * (1.0D - Math.exp(-dt * rateY));
	}

	private void unprime() {
		primed = false;
		lastFrameNanos = 0L;
		// 重新进游戏时不要拿旧数据当基准，重新开一个测速窗口
		measuredSpeedX = 0.0D;
		measuredSpeedY = 0.0D;
		windowDeltaX = 0.0D;
		windowDeltaY = 0.0D;
		windowStartNanos = 0L;
		MouseMotion.clear();
	}

	private static int applyOpacity(int color, float opacity) {
		int alpha = (int) Math.round(((color >>> 24) & 0xFF) * opacity);
		return (clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
	}

	private static int lerpColor(int from, int to, float amount) {
		float t = clampF(amount, 0.0F, 1.0F);
		int a = lerpChannel((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, t);
		int r = lerpChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
		int g = lerpChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
		int b = lerpChannel(from & 0xFF, to & 0xFF, t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int lerpChannel(int from, int to, float t) {
		return clamp(Math.round(from + (to - from) * t), 0, 255);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clampF(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	/** HUD 上的每一个按键，用来各自独立记录渐变动画进度。 */
	public enum KeyId {
		W, A, S, D, L, R, SPACE
	}

	/** 一次布局的尺寸与各行纵坐标。 */
	public static final class Layout {
		public final int key;
		public final int gap;
		public final int spaceHeight;
		public final int totalWidth;
		public final int totalHeight;
		public final int rowW;
		public final int rowAsd;
		public final int rowMouse;
		public final int rowSpace;

		private Layout(int key, int gap, int spaceHeight, int totalWidth, int totalHeight,
				int rowW, int rowAsd, int rowMouse, int rowSpace) {
			this.key = key;
			this.gap = gap;
			this.spaceHeight = spaceHeight;
			this.totalWidth = totalWidth;
			this.totalHeight = totalHeight;
			this.rowW = rowW;
			this.rowAsd = rowAsd;
			this.rowMouse = rowMouse;
			this.rowSpace = rowSpace;
		}

		public static Layout of(KeyViewerConfig config) {
			int key = Math.max(6, (int) Math.round(BASE_KEY * config.size));
			int gap = Math.max(1, (int) Math.round(BASE_GAP * config.size));
			int step = key + gap;
			int spaceHeight = Math.max(2, (int) Math.round(key * 0.45D));
			int totalWidth = key * 3 + gap * 2;

			int rowW = -1;
			int rowAsd = -1;
			int rowMouse = -1;
			int rowSpace = -1;
			int cursor = 0;

			if (config.showMovementKeys) {
				rowW = cursor;
				cursor += step;
				rowAsd = cursor;
				cursor += step;
			}

			if (config.showMouseButtons) {
				rowMouse = cursor;
				cursor += step;
			}

			if (config.showJump) {
				rowSpace = cursor;
				cursor += spaceHeight;
			}

			if (cursor == 0) {
				return null;
			}

			return new Layout(key, gap, spaceHeight, totalWidth, cursor,
					rowW, rowAsd, rowMouse, rowSpace);
		}
	}
}
