package me.itzmr.nebulavisuals.module;

import me.itzmr.nebulavisuals.config.ConfigManager;

import java.util.function.Supplier;

/**
 * Базовый абстрактный класс для настроек визуальных модулей.
 */
public abstract class Setting<T> {
    private final String name;
    private final String description;
    private final Supplier<Boolean> visibility;
    private final T defaultValue;
    protected T value;

    public Setting(String name, String description, T defaultValue, Supplier<Boolean> visibility) {
        this.name = name;
        this.description = description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.visibility = visibility;
    }

    public Setting(String name, T defaultValue) {
        this(name, "", defaultValue, () -> true);
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public void reset() {
        setValue(defaultValue);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
        ConfigManager.markDirty();
    }

    public boolean isVisible() {
        return visibility == null || visibility.get();
    }
}
