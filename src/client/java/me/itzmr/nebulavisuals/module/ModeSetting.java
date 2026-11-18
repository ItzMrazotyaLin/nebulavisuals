package me.itzmr.nebulavisuals.module;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Настройка переключения режимов (список вариантов).
 */
public class ModeSetting extends Setting<String> {
    private final List<String> modes;
    private int index;

    public ModeSetting(String name, String description, String defaultMode, List<String> modes, Supplier<Boolean> visibility) {
        super(name, description, defaultMode, visibility);
        this.modes = Collections.unmodifiableList(modes);
        this.index = Math.max(0, modes.indexOf(defaultMode));
    }

    public ModeSetting(String name, String description, String defaultMode, List<String> modes) {
        this(name, description, defaultMode, modes, () -> true);
    }

    public ModeSetting(String name, String defaultMode, List<String> modes) {
        this(name, "", defaultMode, modes, () -> true);
    }

    public void cycle() {
        if (modes.isEmpty()) return;
        index = (index + 1) % modes.size();
        setValue(modes.get(index));
    }

    public void cycleBack() {
        if (modes.isEmpty()) return;
        index = (index - 1 + modes.size()) % modes.size();
        setValue(modes.get(index));
    }

    @Override
    public void setValue(String value) {
        int idx = modes.indexOf(value);
        if (idx != -1) {
            this.index = idx;
            super.setValue(value);
        }
    }

    public List<String> getModes() {
        return modes;
    }

    public int getIndex() {
        return index;
    }
}
