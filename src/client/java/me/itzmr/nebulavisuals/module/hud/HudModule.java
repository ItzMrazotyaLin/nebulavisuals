package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.config.ConfigManager;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Базовый класс перетаскиваемого HUD-модуля Nebula Visuals.
 * Хранит сохранённые базовые координаты в пространстве canvas и текущие габариты для Chat HUD Editor.
 */
public abstract class HudModule extends VisualModule {
    public static final float MIN_HUD_SCALE = 0.5f;
    public static final float MAX_HUD_SCALE = 2.5f;

    private final float defaultX;
    private final float defaultY;
    private final float defaultScale;
    private float hudX;
    private float hudY;
    private float hudScale;

    private float lastRenderedX;
    private float lastRenderedY;
    private float lastWidth;
    private float lastHeight;

    protected HudModule(String name, String description, boolean defaultEnabled, float defaultX, float defaultY) {
        this(name, description, defaultEnabled, defaultX, defaultY, 1.0f);
    }

    protected HudModule(String name, String description, boolean defaultEnabled,
                        float defaultX, float defaultY, float defaultScale) {
        super(name, description, Category.HUD, defaultEnabled);
        this.defaultX = defaultX;
        this.defaultY = defaultY;
        this.defaultScale = Math.clamp(defaultScale, MIN_HUD_SCALE, MAX_HUD_SCALE);
        this.hudX = defaultX;
        this.hudY = defaultY;
        this.hudScale = this.defaultScale;
        this.lastRenderedX = defaultX;
        this.lastRenderedY = defaultY;
        this.lastWidth = 120.0f;
        this.lastHeight = 28.0f;
    }

    public float getDefaultHudScale() {
        return defaultScale;
    }

    public float getDefaultX() {
        return defaultX;
    }

    public float getDefaultY() {
        return defaultY;
    }

    public void resetHudToDefault() {
        setHudPosition(defaultX, defaultY);
        setHudScale(defaultScale);
    }

    public float getHudX() {
        return hudX;
    }

    public float getHudY() {
        return hudY;
    }

    public void setHudPosition(float x, float y) {
        if (this.hudX != x || this.hudY != y) {
            this.hudX = x;
            this.hudY = y;
            ConfigManager.markDirty();
        }
    }

    /**
     * Обновляет позицию без пометки конфига как изменённого (для якорной компенсации ширины).
     */
    public void setHudPositionSilently(float x, float y) {
        this.hudX = x;
        this.hudY = y;
    }

    public float getHudScale() {
        return hudScale;
    }

    public void setHudScale(float scale) {
        float clamped = Math.clamp(scale, MIN_HUD_SCALE, MAX_HUD_SCALE);
        if (Math.abs(this.hudScale - clamped) > 1e-4f) {
            this.hudScale = clamped;
            ConfigManager.markDirty();
        }
    }

    public void updateRenderedBounds(float x, float y, float width, float height) {
        this.lastRenderedX = x;
        this.lastRenderedY = y;
        this.lastWidth = width;
        this.lastHeight = height;
    }

    public float getLastRenderedX() {
        return lastRenderedX;
    }

    public float getLastRenderedY() {
        return lastRenderedY;
    }

    public float getLastWidth() {
        return lastWidth;
    }

    public float getLastHeight() {
        return lastHeight;
    }
}
