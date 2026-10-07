package com.awa.keyviewer.hud;

/**
 * 原始鼠标增量中转站。
 *
 * {@code MouseHandler.xpos()/ypos()} 是绝对光标坐标，鼠标光标到窗口边缘会被钳住不变，
 * 所以不能拿它算鼠标速度（会出现“鼠标一直在动，HUD 却回原点”）。
 * MC 真正消费的原始增量在 {@code MouseHandler.accumulatedDX/DY}，由 mixin 在这里投递，
 * HUD 每帧取走一次，不重复计算也不丢数据。
 */
public final class MouseMotion {
	private static double pendingX;
	private static double pendingY;

	private MouseMotion() {
	}

	/** mixin 调用：投递这一帧/这一 tick 的原始鼠标增量。 */
	public static void push(double deltaX, double deltaY) {
		pendingX += deltaX;
		pendingY += deltaY;
	}

	public static double takeX() {
		double value = pendingX;
		pendingX = 0.0D;
		return value;
	}

	public static double takeY() {
		double value = pendingY;
		pendingY = 0.0D;
		return value;
	}

	public static void clear() {
		pendingX = 0.0D;
		pendingY = 0.0D;
	}
}
