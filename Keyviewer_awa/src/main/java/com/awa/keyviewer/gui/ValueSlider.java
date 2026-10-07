package com.awa.keyviewer.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 通用设置滑块：直接读写配置字段。 */
public class ValueSlider extends AbstractSliderButton {
	private final Component label;
	private final float min;
	private final float max;
	private final boolean percent;
	private final Supplier<Float> getter;
	private final Consumer<Float> setter;

	public ValueSlider(int x, int y, int width, int height, Component label, float min, float max, boolean percent,
			Supplier<Float> getter, Consumer<Float> setter) {
		super(x, y, width, height, label, normalize(getter.get(), min, max));
		this.label = label;
		this.min = min;
		this.max = max;
		this.percent = percent;
		this.getter = getter;
		this.setter = setter;
		this.updateMessage();
	}

	private static double normalize(float value, float min, float max) {
		if (max <= min) {
			return 0.0D;
		}

		return Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
	}

	public float currentValue() {
		return (float) (min + this.value * (max - min));
	}

	/** 配置被外部改动（例如重置）后刷新显示。 */
	public void sync() {
		this.value = normalize(getter.get(), min, max);
		this.updateMessage();
	}

	@Override
	protected void updateMessage() {
		if (label == null) {
			// 父类构造期间会调用一次，此时字段还没赋值
			return;
		}

		String text = percent
				? Math.round(currentValue() * 100.0F) + "%"
				: String.format(Locale.ROOT, "%.2f", currentValue());
		this.setMessage(label.copy().append(": " + text));
	}

	@Override
	protected void applyValue() {
		setter.accept(currentValue());
	}
}
