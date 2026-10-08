package com.awa.keyviewer.gui;

import com.awa.keyviewer.KeyViewerMod;
import com.awa.keyviewer.config.KeyViewerConfig;
import com.awa.keyviewer.hud.KeyViewerHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** /awakeyviewer 打开的设置界面（两列网格）。 */
public class KeyViewerScreen extends Screen {
	private static final int WIDGET_HEIGHT = 20;
	private static final int ROW_GAP = 4;
	private static final int COLUMN_WIDTH = 150;
	private static final int COLUMN_GAP = 6;
	private static final int COLUMNS = 2;

	private int previewTop;
	private int previewHeight = 40;

	public KeyViewerScreen() {
		super(Component.translatable("keyviewer_awa.title"));
	}

	@Override
	protected void init() {
		KeyViewerConfig config = KeyViewerMod.config();

		int totalWidth = COLUMN_WIDTH * COLUMNS + COLUMN_GAP * (COLUMNS - 1);
		int left = (this.width - totalWidth) / 2;
		int top = Math.max(28, (this.height - 230) / 2);

		int cell = 0;

		// 开关
		cell = toggleCell(cell, left, top, "showMovementKeys",
				() -> config.showMovementKeys, value -> config.showMovementKeys = value);
		cell = toggleCell(cell, left, top, "showMouseButtons",
				() -> config.showMouseButtons, value -> config.showMouseButtons = value);
		cell = toggleCell(cell, left, top, "showJump",
				() -> config.showJump, value -> config.showJump = value);
		cell = toggleCell(cell, left, top, "swayEnabled",
				() -> config.swayEnabled, value -> config.swayEnabled = value);

		// 颜色
		cell = colorCell(cell, left, top, ColorTarget.BORDER);
		cell = colorCell(cell, left, top, ColorTarget.PRESSED);
		cell = colorCell(cell, left, top, ColorTarget.RELEASED);

		// 透明度 / 位置 / 大小 / 灵敏度
		cell = sliderCell(cell, left, top, "opacity", 0.0F, 1.0F, true,
				() -> config.opacity, value -> config.opacity = value);
		cell = sliderCell(cell, left, top, "textOpacity", 0.0F, 1.0F, true,
				() -> config.textOpacity, value -> config.textOpacity = value);
		cell = sliderCell(cell, left, top, "pressedOpacity", 0.0F, 1.0F, true,
				() -> config.pressedOpacity, value -> config.pressedOpacity = value);
		cell = sliderCell(cell, left, top, "releasedOpacity", 0.0F, 1.0F, true,
				() -> config.releasedOpacity, value -> config.releasedOpacity = value);
		cell = sliderCell(cell, left, top, "borderOpacity", 0.0F, 1.0F, true,
				() -> config.borderOpacity, value -> config.borderOpacity = value);
		cell = sliderCell(cell, left, top, "posX", 0.0F, 1.0F, true,
				() -> config.posX, value -> config.posX = value);
		cell = sliderCell(cell, left, top, "posY", 0.0F, 1.0F, true,
				() -> config.posY, value -> config.posY = value);
		cell = sliderCell(cell, left, top, "size", 0.5F, 3.0F, false,
				() -> config.size, value -> config.size = value);
		cell = sliderCell(cell, left, top, "sensitivity", 0.0F, 2.0F, false,
				() -> config.sensitivity, value -> config.sensitivity = value);

		int rows = (cell + COLUMNS - 1) / COLUMNS;
		int contentBottom = top + rows * (WIDGET_HEIGHT + ROW_GAP);
		int bottom = this.height - WIDGET_HEIGHT - 8;
		this.previewTop = contentBottom + 12;
		this.previewHeight = Math.max(20, bottom - 12 - this.previewTop);

		addRenderableWidget(Button.builder(Component.translatable("keyviewer_awa.reset"), button -> {
			KeyViewerMod.config().reset();
			KeyViewerHud.resetOffsets();
			// 重建控件以刷新所有文字
			this.init(this.width, this.height);
		}).bounds(left, bottom, 90, WIDGET_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("keyviewer_awa.done"), button -> this.onClose())
				.bounds(left + totalWidth - 90, bottom, 90, WIDGET_HEIGHT).build());
	}

	private int cellX(int left, int index) {
		return left + (index % COLUMNS) * (COLUMN_WIDTH + COLUMN_GAP);
	}

	private int cellY(int top, int index) {
		return top + (index / COLUMNS) * (WIDGET_HEIGHT + ROW_GAP);
	}

	private int toggleCell(int index, int left, int top, String optionKey,
			Supplier<Boolean> getter, Consumer<Boolean> setter) {
		Component label = Component.translatable("keyviewer_awa.option." + optionKey);

		addRenderableWidget(Button.builder(toggleLabel(label, getter.get()), button -> {
			setter.accept(!getter.get());
			button.setMessage(toggleLabel(label, getter.get()));
		}).bounds(cellX(left, index), cellY(top, index), COLUMN_WIDTH, WIDGET_HEIGHT).build());

		return index + 1;
	}

	private static Component toggleLabel(Component label, boolean value) {
		return label.copy().append(": ").append(Component.translatable(value ? "keyviewer_awa.on" : "keyviewer_awa.off"));
	}

	private int colorCell(int index, int left, int top, ColorTarget target) {
		int color = target.get(KeyViewerMod.config());
		Component label = Component.translatable(target.translationKey())
				.append(" " + String.format("#%06X", color & 0xFFFFFF));

		addRenderableWidget(Button.builder(label, button ->
				Minecraft.getInstance().setScreenAndShow(new ColorEditScreen(this, target)))
				.bounds(cellX(left, index), cellY(top, index), COLUMN_WIDTH, WIDGET_HEIGHT).build());

		return index + 1;
	}

	private int sliderCell(int index, int left, int top, String optionKey, float min, float max, boolean percent,
			Supplier<Float> getter, Consumer<Float> setter) {
		addRenderableWidget(new ValueSlider(cellX(left, index), cellY(top, index), COLUMN_WIDTH, WIDGET_HEIGHT,
				Component.translatable("keyviewer_awa.option." + optionKey), min, max, percent, getter, setter));

		return index + 1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		Minecraft client = Minecraft.getInstance();
		Font font = client.font;

		graphics.centeredText(font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable("keyviewer_awa.preview"), this.width / 2, this.previewTop - 10,
				0xFFB0B0B0);

		int frameX = this.width / 2 - 130;
		int frameY = this.previewTop;
		int frameW = 260;
		graphics.fill(frameX, frameY, frameX + frameW, frameY + this.previewHeight, 0x40000000);
		graphics.fill(frameX, frameY, frameX + frameW, frameY + 1, 0x60FFFFFF);
		graphics.fill(frameX, frameY + this.previewHeight - 1, frameX + frameW, frameY + this.previewHeight, 0x60FFFFFF);
		graphics.fill(frameX, frameY, frameX + 1, frameY + this.previewHeight, 0x60FFFFFF);
		graphics.fill(frameX + frameW - 1, frameY, frameX + frameW, frameY + this.previewHeight, 0x60FFFFFF);

		KeyViewerHud.drawPreview(graphics, client, KeyViewerMod.config(), this.width / 2, frameY + 4,
				frameW - 16, Math.max(10, this.previewHeight - 8));
	}

	@Override
	public void onClose() {
		KeyViewerMod.config().save();
		super.onClose();
	}

	@Override
	public void removed() {
		KeyViewerMod.config().save();
	}
}
