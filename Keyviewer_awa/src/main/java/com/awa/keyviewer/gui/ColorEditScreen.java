package com.awa.keyviewer.gui;

import com.awa.keyviewer.KeyViewerMod;
import com.awa.keyviewer.config.KeyViewerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 单个颜色的编辑界面：RGB 滑块 + 16 进制输入框。 */
public class ColorEditScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int ROW_GAP = 4;
	private static final int FIELD_WIDTH = 200;

	private final Screen parent;
	private final ColorTarget target;
	private final List<ValueSlider> channels = new ArrayList<>();

	private EditBox hexBox;
	/** 程序改动输入框内容时用来挡住 responder 重入。 */
	private boolean normalizing;
	private int hexLabelY;

	public ColorEditScreen(Screen parent, ColorTarget target) {
		super(Component.translatable("keyviewer_awa.color.title"));
		this.parent = parent;
		this.target = target;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int top = Math.max(46, this.height / 2 - 80);
		int left = centerX - FIELD_WIDTH / 2;

		this.channels.clear();
		channel(left, top, 16, "red");
		channel(left, top + ROW_HEIGHT + ROW_GAP, 8, "green");
		channel(left, top + (ROW_HEIGHT + ROW_GAP) * 2, 0, "blue");

		// 16 进制输入框（# 不可删除，非法字符自动过滤，不足 6 位按 0 补全）
		this.hexLabelY = top + (ROW_HEIGHT + ROW_GAP) * 3 + 2;
		int hexY = this.hexLabelY + 12;

		this.hexBox = new EditBox(Minecraft.getInstance().font, left, hexY, FIELD_WIDTH, ROW_HEIGHT,
				Component.translatable("keyviewer_awa.color.hex"));
		this.hexBox.setMaxLength(7);
		this.normalizing = true;
		this.hexBox.setValue(formatHex(this.target.get(KeyViewerMod.config())));
		this.hexBox.setCursorPosition(this.hexBox.getValue().length());
		this.normalizing = false;
		this.hexBox.setResponder(this::onHexChanged);
		addRenderableWidget(this.hexBox);

		addRenderableWidget(Button.builder(Component.translatable("keyviewer_awa.back"), button -> this.onClose())
				.bounds(left, hexY + ROW_HEIGHT + 8, FIELD_WIDTH, ROW_HEIGHT)
				.build());
	}

	private void channel(int x, int y, int shift, String name) {
		ValueSlider slider = addRenderableWidget(new ValueSlider(x, y, FIELD_WIDTH, ROW_HEIGHT,
				Component.translatable("keyviewer_awa.color." + name), 0.0F, 255.0F, false,
				() -> (float) ((this.target.get(KeyViewerMod.config()) >> shift) & 0xFF),
				value -> {
					KeyViewerConfig config = KeyViewerMod.config();
					int current = this.target.get(config);
					this.target.set(config, (current & ~(0xFF << shift)) | ((Math.round(value) & 0xFF) << shift));
					syncHexBox();
				}));
		this.channels.add(slider);
	}

	/** 输入框内容变化：先归一化，再写进配置。 */
	private void onHexChanged(String raw) {
		if (this.normalizing) {
			return;
		}

		String normalized = normalizeHex(raw);

		if (!normalized.equals(raw)) {
			// 例如用户删掉了 #、粘贴了非法字符：直接改回规范内容
			this.normalizing = true;
			this.hexBox.setValue(normalized);
			this.hexBox.setCursorPosition(normalized.length());
			this.normalizing = false;
		}

		applyHex(normalized);
	}

	/** 只有 # 时保持原色不变；位数不足的部分默认补 0。 */
	private void applyHex(String hexText) {
		String digits = hexText.substring(1);

		if (digits.isEmpty()) {
			return;
		}

		StringBuilder padded = new StringBuilder(digits);

		while (padded.length() < 6) {
			padded.append('0');
		}

		int rgb = Integer.parseInt(padded.toString(), 16);
		KeyViewerConfig config = KeyViewerMod.config();
		int current = this.target.get(config);
		this.target.set(config, (current & 0xFF000000) | (rgb & 0xFFFFFF));

		// 让三个滑块跟上
		for (ValueSlider slider : this.channels) {
			slider.sync();
		}
	}

	/** 滑块改动后刷新输入框（正在输入时不打断光标）。 */
	private void syncHexBox() {
		if (this.hexBox == null || this.hexBox.isFocused()) {
			return;
		}

		String text = formatHex(this.target.get(KeyViewerMod.config()));

		if (text.equals(this.hexBox.getValue())) {
			return;
		}

		this.normalizing = true;
		this.hexBox.setValue(text);
		this.hexBox.setCursorPosition(text.length());
		this.normalizing = false;
	}

	/** 去掉 # 与非法字符、统一大写、最多 6 位，并保证以 # 开头。 */
	private static String normalizeHex(String raw) {
		StringBuilder digits = new StringBuilder();

		for (int i = 0; i < raw.length() && digits.length() < 6; i++) {
			char c = raw.charAt(i);

			if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
				digits.append(Character.toUpperCase(c));
			}
		}

		return "#" + digits;
	}

	private static String formatHex(int argb) {
		return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		Font font = Minecraft.getInstance().font;
		graphics.centeredText(font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable(this.target.translationKey()), this.width / 2, 30, 0xFFA0A0A0);
		graphics.centeredText(font, Component.translatable("keyviewer_awa.color.hex"), this.width / 2, this.hexLabelY, 0xFFA0A0A0);

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
	}

	@Override
	public void onClose() {
		KeyViewerMod.config().save();
		Minecraft.getInstance().setScreenAndShow(this.parent);
	}
}
