# Keyviewer_awa

A client-side key viewer HUD for **Minecraft 26.3 (Fabric)**. In-game name: **Keyviewer Awa**.

It shows the keys you are holding while you play — WASD, left/right mouse buttons and the space bar — and hides itself the moment any GUI opens (ESC, chat, inventory…).

## Features

* Live key display: `W` on top, `A S D` below, wide `L` / `R` mouse bars, and a thin space bar
* Visible only while actually playing (mouse grabbed); hidden instantly when a screen is open
* The whole HUD **sways with your mouse** — faster movement pushes it further, it pulls back to its anchor when you stop, and crosses the anchor when you turn the other way
* Fade in/out press animation, per-state colors and opacities, adjustable position, size and sensitivity
* Colors can be set with RGB sliders **or** by typing a hex code

## Usage

1. Install **Fabric Loader 0.19.5+** for Minecraft 26.3
2. Put `Keyviewer_awa-1.1.jar` and `fabric-api-0.161.0+26.3.jar` into `.minecraft/mods/`
3. Launch the game and join a world — the HUD shows up in the lower-left corner

### Settings

Type this in chat to open the settings screen (with live preview):

```
/awakeyviewer
```

| Section | Options |
| --- | --- |
| Toggles | WASD keys, L/R mouse buttons, space bar, follow mouse |
| Colors | border / pressed / released (RGB sliders + hex code) |
| Opacity | overall, text, pressed, released, border |
| Layout | position X, position Y, size, sensitivity |

Everything is saved to `.minecraft/config/keyviewer_awa.json` automatically.

### Hex colors

In any color screen the hex code can be edited directly:

* `#` is fixed — it cannot be changed or deleted
* Invalid characters are filtered out, letters are upper-cased, max 6 digits
* Missing digits are filled with `0` (so `#FF` means `#FF0000`), and the swatch previews it live

## Requirements

| Dependency | Version |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.161.0+26.3 |
| Java | 25 |

Client-side only, no need to install it on a server.

## Building

Requires JDK 25:

```bash
gradlew build
```

The jar ends up in `build/libs/`.
