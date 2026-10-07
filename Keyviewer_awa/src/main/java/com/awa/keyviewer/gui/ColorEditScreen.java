package com.awa.keyviewer.gui;

import com.awa.keyviewer.KeyViewerMod;
import com.awa.keyviewer.config.KeyViewerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 单个颜色的 RGB 编辑界面。 */
public class ColorEditScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int ROW_GAP = 4;
	private static final int SLIDER_WIDTH = 200;

	private final Screen parent;
	private final ColorTarget target;

	public ColorEditScreen(Screen parent, ColorTarget target) {
		super(Component.translatable("keyviewer_awa.color.title"));
		this.parent = parent;
		this.target = target;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int top = Math.max(46, this.height / 2 - 70);
		int left = centerX - SLIDER_WIDTH / 2;

		channel(left, top, 16, "red");
		channel(left, top + ROW_HEIGHT + ROW_GAP, 8, "green");
		channel(left, top + (ROW_HEIGHT + ROW_GAP) * 2, 0, "blue");

		addRenderableWidget(Button.builder(Component.translatable("keyviewer_awa.back"), button -> this.onClose())
				.bounds(left, top + (ROW_HEIGHT + ROW_GAP) * 3 + 6, SLIDER_WIDTH, ROW_HEIGHT)
				.build());
	}

	private void channel(int x, int y, int shift, String name) {
		addRenderableWidget(new ValueSlider(x, y, SLIDER_WIDTH, ROW_HEIGHT,
				Component.translatable("keyviewer_awa.color." + name), 0.0F, 255.0F, false,
				() -> (float) ((this.target.get(KeyViewerMod.config()) >> shift) & 0xFF),
				value -> {
					KeyViewerConfig config = KeyViewerMod.config();
					int current = this.target.get(config);
					this.target.set(config, (current & ~(0xFF << shift)) | ((Math.round(value) & 0xFF) << shift));
				}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		Font font = Minecraft.getInstance().font;
		graphics.centeredText(font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable(this.target.translationKey()), this.width / 2, 30, 0xFFA0A0A0);

		int color = this.target.get(KeyViewerMod.config());
		int previewWidth = 80;
		int previewHeight = 24;
		int previewX = this.width / 2 - previewWidth / 2;
		int previewY = this.height - 52;
		graphics.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, color | 0xFF000000);
		graphics.fill(previewX, previewY, previewX + previewWidth, previewY + 1, 0xFFFFFFFF);
		graphics.fill(previewX, previewY + previewHeight - 1, previewX + previewWidth, previewY + previewHeight, 0xFFFFFFFF);
		graphics.fill(previewX, previewY, previewX + 1, previewY + previewHeight, 0xFFFFFFFF);
		graphics.fill(previewX + previewWidth - 1, previewY, previewX + previewWidth, previewY + previewHeight, 0xFFFFFFFF);
		graphics.centeredText(font, String.format("#%06X", color & 0xFFFFFF), this.width / 2, previewY + 8, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		KeyViewerMod.config().save();
		Minecraft.getInstance().setScreenAndShow(this.parent);
	}
}
