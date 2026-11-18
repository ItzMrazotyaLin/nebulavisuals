package me.itzmr.nebulavisuals.module;

import me.itzmr.nebulavisuals.config.ConfigManager;

import java.util.function.Supplier;

/**
 * Настройка цвета с поддержкой альфа-канала.
 */
public class ColorSetting extends Setting<Integer> {
    private final boolean defaultRainbow;
    private boolean rainbow;

    public ColorSetting(String name, String description, int defaultArgb, boolean rainbow, Supplier<Boolean> visibility) {
        super(name, description, defaultArgb, visibility);
        this.defaultRainbow = rainbow;
        this.rainbow = rainbow;
    }

    @Override
    public void reset() {
        super.reset();
        setRainbow(defaultRainbow);
    }

    public ColorSetting(String name, String description, int defaultArgb, boolean rainbow) {
        this(name, description, defaultArgb, rainbow, () -> true);
    }

    public ColorSetting(String name, int defaultArgb, boolean rainbow) {
        this(name, "", defaultArgb, rainbow, () -> true);
    }

    public boolean isRainbow() {
        return rainbow;
    }

    public boolean isDefaultRainbow() {
        return defaultRainbow;
    }

    public String getDefaultHex() {
        Integer def = getDefaultValue();
        int rgb = def != null ? (0xFFFFFF & def) : 0;
        return String.format("#%06X", rgb);
    }

    public void setRainbow(boolean rainbow) {
        this.rainbow = rainbow;
        ConfigManager.markDirty();
    }

    public int getRed() {
        return (value >> 16) & 0xFF;
    }

    public int getGreen() {
        return (value >> 8) & 0xFF;
    }

    public int getBlue() {
        return value & 0xFF;
    }

    public int getAlpha() {
        return (value >> 24) & 0xFF;
    }

    public String getHex() {
        return String.format("#%06X", (0xFFFFFF & value));
    }
}
