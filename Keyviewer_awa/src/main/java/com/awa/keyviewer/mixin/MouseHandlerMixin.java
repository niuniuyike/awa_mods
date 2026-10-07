package com.awa.keyviewer.mixin;

import com.awa.keyviewer.hud.MouseMotion;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 MC 消费原始鼠标增量之前把它抓下来，给 KeyViewer 的 HUD 位移用。
 *
 * 不能用 {@code xpos()/ypos()}：那是绝对光标坐标，光标碰到窗口边缘就不动了，
 * 会让 HUD 误以为鼠标停了。
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void keyviewer$captureRawMotion(CallbackInfo info) {
		if (accumulatedDX != 0.0D || accumulatedDY != 0.0D) {
			MouseMotion.push(accumulatedDX, accumulatedDY);
		}
	}
}
