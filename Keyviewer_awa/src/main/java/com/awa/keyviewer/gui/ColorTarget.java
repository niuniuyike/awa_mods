package com.awa.keyviewer.gui;

import com.awa.keyviewer.config.KeyViewerConfig;

import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/** 可编辑的颜色项。 */
public enum ColorTarget {
	BORDER("borderColor", config -> config.borderColor, (config, value) -> config.borderColor = value),
	PRESSED("pressedColor", config -> config.pressedColor, (config, value) -> config.pressedColor = value),
	RELEASED("releasedColor", config -> config.releasedColor, (config, value) -> config.releasedColor = value);

	private final String optionKey;
	private final ToIntFunction<KeyViewerConfig> getter;
	private final ObjIntConsumer<KeyViewerConfig> setter;

	ColorTarget(String optionKey, ToIntFunction<KeyViewerConfig> getter, ObjIntConsumer<KeyViewerConfig> setter) {
		this.optionKey = optionKey;
		this.getter = getter;
		this.setter = setter;
	}

	public String translationKey() {
		return "keyviewer_awa.option." + optionKey;
	}

	public int get(KeyViewerConfig config) {
		return getter.applyAsInt(config);
	}

	public void set(KeyViewerConfig config, int value) {
		setter.accept(config, value);
	}
}
