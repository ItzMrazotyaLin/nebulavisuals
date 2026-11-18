package me.itzmr.nebulavisuals.config;

import me.itzmr.nebulavisuals.module.ColorSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;

import java.util.List;

/**
 * Глобальный менеджер клиентских настроек Nebula Visuals.
 * Управляет акцентным цветом, прозрачностью подложек (UI Opacity), масштабом UI,
 * интенсивностью размытия и скоростью анимаций в реальном времени.
 */
public final class ClientSettings {
    public record AccentPreset(String name, int argb) {}

    public static final List<AccentPreset> ACCENT_PRESETS = List.of(
            new AccentPreset("Violet", 0xFF8B5CF6),
            new AccentPreset("Cosmic Blue", 0xFF3B82F6),
            new AccentPreset("Indigo", 0xFF6366F1),
            new AccentPreset("Mint Cyan", 0xFF06B6D4),
            new AccentPreset("Neon Pink", 0xFFEC4899),
            new AccentPreset("Emerald", 0xFF10B981),
            new AccentPreset("Monochrome", 0xFFE5E7EB)
    );

    private static final ColorSetting ACCENT_COLOR = new ColorSetting(
            "Accent Color",
            "Global accent tint across ClickGUI and HUD",
            0xFF8B5CF6,
            false
    );

    private static final SliderSetting ACCENT_SATURATION = new SliderSetting(
            "Saturation",
            "Accent color saturation",
            0.62, 0.0, 1.0, 0.01, ""
    );

    private static final SliderSetting ACCENT_BRIGHTNESS = new SliderSetting(
            "Brightness",
            "Accent color brightness",
            0.96, 0.10, 1.0, 0.01, ""
    );

    private static final SliderSetting CLICK_GUI_SCALE = new SliderSetting(
            "ClickGUI Scale",
            "ClickGUI window scale multiplier",
            1.0, 1.0, 2.0, 0.05, "x"
    );

    private static final SliderSetting UI_OPACITY = new SliderSetting(
            "UI Opacity",
            "Background surface opacity across ClickGUI and HUD",
            0.85, 0.10, 1.00, 0.05, ""
    );

    private static final SliderSetting BLUR_STRENGTH = new SliderSetting(
            "Blur Strength",
            "Kawase blur intensity multiplier",
            1.0, 0.0, 2.0, 0.05, "x"
    );

    private static final SliderSetting ANIMATION_SPEED = new SliderSetting(
            "Animation Speed",
            "Global UI & HUD transition speed multiplier",
            1.0, 0.25, 2.0, 0.05, "x"
    );

    private static float accentHue = 0.0f;
    private static float accentSat = 0.62f;
    private static float accentBri = 0.96f;

    static {
        syncHsbFromArgb(ACCENT_COLOR.getValue());
    }

    private ClientSettings() {}

    public static ColorSetting getAccentColorSetting() {
        return ACCENT_COLOR;
    }

    public static SliderSetting getAccentSaturationSetting() {
        return ACCENT_SATURATION;
    }

    public static SliderSetting getAccentBrightnessSetting() {
        return ACCENT_BRIGHTNESS;
    }

    public static SliderSetting getClickGuiScaleSetting() {
        return CLICK_GUI_SCALE;
    }

    public static SliderSetting getUiOpacitySetting() {
        return UI_OPACITY;
    }

    public static SliderSetting getBlurStrengthSetting() {
        return BLUR_STRENGTH;
    }

    public static SliderSetting getAnimationSpeedSetting() {
        return ANIMATION_SPEED;
    }

    public static int getAccentColor() {
        return ACCENT_COLOR.getValue();
    }

    public static int getAccentHoverColor() {
        return ColorUtil.lerp(ACCENT_COLOR.getValue(), 0xFFFFFFFF, 0.25f);
    }

    public static float getAccentHue() {
        return accentHue;
    }

    public static float getAccentSat() {
        return accentSat;
    }

    public static float getAccentBri() {
        return accentBri;
    }

    public static void setAccentColor(int argb) {
        int opaque = 0xFF000000 | (argb & 0x00FFFFFF);
        ACCENT_COLOR.setValue(opaque);
        syncHsbFromArgb(opaque);
    }

    public static void setAccentHsb(float hue, float sat, float bri) {
        accentHue = wrapHue(hue);
        accentSat = Math.clamp(sat, 0.0f, 1.0f);
        accentBri = Math.clamp(bri, 0.10f, 1.0f);
        ACCENT_SATURATION.setValue((double) accentSat);
        ACCENT_BRIGHTNESS.setValue((double) accentBri);
        ACCENT_COLOR.setValue(hsbToArgb(accentHue, accentSat, accentBri));
    }

    public static void syncColorFromHsbSliders() {
        setAccentHsb(
                accentHue,
                ACCENT_SATURATION.getValue().floatValue(),
                ACCENT_BRIGHTNESS.getValue().floatValue()
        );
    }

    public static float getClickGuiScale() {
        return Math.clamp(CLICK_GUI_SCALE.getValue().floatValue(), 1.0f, 2.0f);
    }

    public static void resetToDefaults() {
        ACCENT_COLOR.reset();
        syncHsbFromArgb(ACCENT_COLOR.getValue());
        CLICK_GUI_SCALE.reset();
        UI_OPACITY.reset();
        BLUR_STRENGTH.reset();
        ANIMATION_SPEED.reset();
    }

    public static float getUiOpacity() {
        return Math.clamp(UI_OPACITY.getValue().floatValue(), 0.10f, 1.0f);
    }

    public static float getBlurStrength() {
        return Math.clamp(BLUR_STRENGTH.getValue().floatValue(), 0.0f, 2.0f);
    }

    public static float getAnimationSpeed() {
        return Math.clamp(ANIMATION_SPEED.getValue().floatValue(), 0.25f, 2.0f);
    }

    public static void updateAnimations(float dt) {
        ACCENT_SATURATION.getProgressAnim().update(dt);
        ACCENT_BRIGHTNESS.getProgressAnim().update(dt);
        CLICK_GUI_SCALE.getProgressAnim().update(dt);
        UI_OPACITY.getProgressAnim().update(dt);
        BLUR_STRENGTH.getProgressAnim().update(dt);
        ANIMATION_SPEED.getProgressAnim().update(dt);
    }

    public static int hsbToArgb(float h, float s, float b) {
        float hue = wrapHue(h) * 6.0f;
        int sector = ((int) Math.floor(hue)) % 6;
        float f = hue - (float) Math.floor(hue);
        float p = b * (1.0f - s);
        float q = b * (1.0f - f * s);
        float t = b * (1.0f - (1.0f - f) * s);

        float r, g, bl;
        switch (sector) {
            case 0 -> { r = b; g = t; bl = p; }
            case 1 -> { r = q; g = b; bl = p; }
            case 2 -> { r = p; g = b; bl = t; }
            case 3 -> { r = p; g = q; bl = b; }
            case 4 -> { r = t; g = p; bl = b; }
            default -> { r = b; g = p; bl = q; }
        }
        return ColorUtil.argb(255, Math.round(r * 255.0f), Math.round(g * 255.0f), Math.round(bl * 255.0f));
    }

    private static void syncHsbFromArgb(int argb) {
        float[] hsb = argbToHsb(argb);
        accentHue = hsb[0];
        accentSat = hsb[1];
        accentBri = hsb[2];

        ACCENT_SATURATION.setValue((double) accentSat);
        ACCENT_BRIGHTNESS.setValue((double) accentBri);
    }

    public static float[] argbToHsb(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float bri = Math.clamp(max, 0.10f, 1.0f);
        float sat = max == 0.0f ? 0.0f : (delta / max);

        float hue;
        if (delta == 0.0f) {
            hue = 0.0f;
        } else {
            if (max == r) {
                hue = ((g - b) / delta) % 6.0f;
            } else if (max == g) {
                hue = ((b - r) / delta) + 2.0f;
            } else {
                hue = ((r - g) / delta) + 4.0f;
            }
            hue = wrapHue(hue / 6.0f);
        }
        return new float[]{hue, sat, bri};
    }

    private static float wrapHue(float hue) {
        float wrapped = hue % 1.0f;
        return wrapped < 0.0f ? wrapped + 1.0f : wrapped;
    }
}
