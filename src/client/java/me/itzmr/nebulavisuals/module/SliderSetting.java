package me.itzmr.nebulavisuals.module;

import me.itzmr.nebulavisuals.render.engine2d.animation.SpringAnimation;

import java.util.function.Supplier;

/**
 * Числовой слайдер с шагом и плавной анимацией прогресс-бара.
 */
public class SliderSetting extends Setting<Double> {
    private final double min;
    private final double max;
    private final double step;
    private final String suffix;
    private final SpringAnimation progressAnim;
    private double valueSq;
    private boolean dragging = false;
    private String cachedFormattedValue;

    public SliderSetting(String name, String description, double defaultValue, double min, double max, double step, String suffix, Supplier<Boolean> visibility) {
        super(name, description, defaultValue, visibility);
        this.min = min;
        this.max = max;
        this.step = step;
        this.suffix = suffix != null ? suffix : "";
        this.valueSq = defaultValue * defaultValue;
        float initialProgress = (float) Math.clamp((defaultValue - min) / (max - min), 0.0, 1.0);
        this.progressAnim = new SpringAnimation(initialProgress, 260.0f, 22.0f);
        this.cachedFormattedValue = formatValueString(defaultValue);
    }

    public SliderSetting(String name, String description, double defaultValue, double min, double max, double step, String suffix) {
        this(name, description, defaultValue, min, max, step, suffix, () -> true);
    }

    public SliderSetting(String name, double defaultValue, double min, double max, double step, String suffix) {
        this(name, "", defaultValue, min, max, step, suffix, () -> true);
    }

    @Override
    public void setValue(Double value) {
        double clamped = Math.clamp(value, min, max);
        if (step > 0.0) {
            clamped = Math.round((clamped - min) / step) * step + min;
        }
        this.valueSq = clamped * clamped;
        super.setValue(clamped);
        float progress = (float) Math.clamp((clamped - min) / (max - min), 0.0, 1.0);
        this.progressAnim.setTarget(progress);
        this.cachedFormattedValue = formatValueString(clamped);
    }

    public double getValueSq() {
        return valueSq;
    }

    public void setFromNormalized(float norm) {
        float clamped = Math.clamp(norm, 0.0f, 1.0f);
        double targetVal = min + (max - min) * clamped;
        setValue(targetVal);
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getStep() {
        return step;
    }

    public String getSuffix() {
        return suffix;
    }

    public SpringAnimation getProgressAnim() {
        return progressAnim;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    public String getFormattedValue() {
        return cachedFormattedValue;
    }

    private String formatValueString(double v) {
        if (step == (long) step && min == (long) min && max == (long) max) {
            return String.format("%.0f%s", v, suffix);
        } else if (step >= 0.1) {
            return String.format("%.1f%s", v, suffix);
        } else {
            return String.format("%.2f%s", v, suffix);
        }
    }
}
