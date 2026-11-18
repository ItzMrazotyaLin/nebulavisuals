package me.itzmr.nebulavisuals.module;

import me.itzmr.nebulavisuals.config.ConfigManager;
import me.itzmr.nebulavisuals.module.hud.NotificationsHudModule;
import me.itzmr.nebulavisuals.render.engine2d.animation.SpringAnimation;
import me.itzmr.nebulavisuals.render.hud.NotificationsRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Базовый класс визуального модуля клиентского мода Nebula Visuals.
 */
public abstract class VisualModule {
    private final String name;
    private final String description;
    private final Category category;
    private final boolean defaultEnabled;

    private boolean enabled;
    private boolean expanded = false;

    private final List<Setting<?>> settings = new ArrayList<>();

    // Анимации интерфейса
    private final SpringAnimation toggleAnim = new SpringAnimation(0.0f, 240.0f, 20.0f);
    private final SpringAnimation hoverAnim = new SpringAnimation(0.0f, 200.0f, 20.0f);
    private float expandRawProgress = 0.0f;

    public VisualModule(String name, String description, Category category, boolean defaultEnabled) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.defaultEnabled = defaultEnabled;
        this.enabled = defaultEnabled;
        this.toggleAnim.setValue(defaultEnabled ? 1.0f : 0.0f);
        this.toggleAnim.setTarget(defaultEnabled ? 1.0f : 0.0f);
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public void resetToDefault() {
        setEnabled(defaultEnabled);
        for (Setting<?> s : settings) {
            if (!(s instanceof ActionSetting)) {
                s.reset();
            }
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            this.toggleAnim.setTarget(enabled ? 1.0f : 0.0f);
            if (enabled) {
                onEnable();
            } else {
                onDisable();
            }
            if (!ConfigManager.isLoadingConfig()) {
                NotificationsHudModule notif = ModuleManager.getNotificationsHudModule();
                if (notif != null && notif.isEnabled() && notif.moduleToggles.getValue()) {
                    NotificationsRenderer.pushModuleToggle(this.name, enabled);
                }
            }
            ConfigManager.markDirty();
        }
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    protected void onEnable() {}
    protected void onDisable() {}

    public void addSettings(Setting<?>... settings) {
        Collections.addAll(this.settings, settings);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public List<Setting<?>> getSettings() {
        return settings;
    }

    public boolean isBooleanSettingEnabled(String name, boolean fallback) {
        for (Setting<?> s : settings) {
            if (s instanceof BooleanSetting bs && s.getName().equalsIgnoreCase(name)) {
                return bs.getValue();
            }
        }
        return fallback;
    }

    public double getSliderSettingValue(String name, double fallback) {
        for (Setting<?> s : settings) {
            if (s instanceof SliderSetting ss && s.getName().equalsIgnoreCase(name)) {
                return ss.getValue();
            }
        }
        return fallback;
    }

    public SpringAnimation getToggleAnim() {
        return toggleAnim;
    }

    public float getExpandRawProgress() {
        return expandRawProgress;
    }

    /**
     * Монотонная кривая EaseInOutCubic в [0.0f, 1.0f] для плавного аккордеона без пружинных перелётов.
     */
    public float getExpandProgress() {
        float t = Math.clamp(expandRawProgress, 0.0f, 1.0f);
        if (t <= 0.0f) return 0.0f;
        if (t >= 1.0f) return 1.0f;
        return t < 0.5f
                ? 4.0f * t * t * t
                : 1.0f - (float) Math.pow(-2.0f * t + 2.0f, 3.0) / 2.0f;
    }

    public SpringAnimation getHoverAnim() {
        return hoverAnim;
    }

    public void updateAnimations(float dt) {
        toggleAnim.update(dt);
        hoverAnim.update(dt);

        // dt из AnimationEngine.updateDelta() уже домножен на ClientSettings.getAnimationSpeed()
        float target = expanded ? 1.0f : 0.0f;
        float step = Math.max(0.0f, dt * 4.5f);
        if (expandRawProgress < target) {
            expandRawProgress = Math.min(target, expandRawProgress + step);
        } else if (expandRawProgress > target) {
            expandRawProgress = Math.max(target, expandRawProgress - step);
        }

        for (Setting<?> s : settings) {
            if (s instanceof BooleanSetting bs) {
                bs.getToggleAnim().update(dt);
            } else if (s instanceof SliderSetting ss) {
                ss.getProgressAnim().update(dt);
            }
        }
    }
}
