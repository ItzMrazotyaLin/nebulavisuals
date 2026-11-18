package me.itzmr.nebulavisuals.module;

import me.itzmr.nebulavisuals.render.engine2d.animation.SpringAnimation;

import java.util.function.Supplier;

/**
 * Логическая настройка (Вкл/Выкл) с плавной физической пружинной анимацией.
 */
public class BooleanSetting extends Setting<Boolean> {
    private final SpringAnimation toggleAnim;

    public BooleanSetting(String name, String description, boolean defaultValue, Supplier<Boolean> visibility) {
        super(name, description, defaultValue, visibility);
        this.toggleAnim = new SpringAnimation(defaultValue ? 1.0f : 0.0f, 240.0f, 20.0f);
    }

    public BooleanSetting(String name, String description, boolean defaultValue) {
        this(name, description, defaultValue, () -> true);
    }

    public BooleanSetting(String name, boolean defaultValue) {
        this(name, "", defaultValue, () -> true);
    }

    public void toggle() {
        setValue(!getValue());
    }

    @Override
    public void setValue(Boolean value) {
        super.setValue(value);
        this.toggleAnim.setTarget(value ? 1.0f : 0.0f);
    }

    public SpringAnimation getToggleAnim() {
        return toggleAnim;
    }
}
