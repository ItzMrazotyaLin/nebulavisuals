package me.itzmr.nebulavisuals.render.engine2d.animation;

/**
 * Набор функций плавности (Easing Functions) для интерфейсных анимаций.
 */
@FunctionalInterface
public interface Easing {
    float ease(float t);

    Easing LINEAR = t -> t;

    Easing EASE_IN_QUAD = t -> t * t;
    Easing EASE_OUT_QUAD = t -> t * (2.0f - t);
    Easing EASE_IN_OUT_QUAD = t -> t < 0.5f ? 2.0f * t * t : -1.0f + (4.0f - 2.0f * t) * t;

    Easing EASE_IN_CUBIC = t -> t * t * t;
    Easing EASE_OUT_CUBIC = t -> {
        float f = t - 1.0f;
        return f * f * f + 1.0f;
    };
    Easing EASE_IN_OUT_CUBIC = t -> t < 0.5f
            ? 4.0f * t * t * t
            : (t - 1.0f) * (2.0f * t - 2.0f) * (2.0f * t - 2.0f) + 1.0f;

    Easing EASE_IN_QUART = t -> t * t * t * t;
    Easing EASE_OUT_QUART = t -> {
        float f = t - 1.0f;
        return 1.0f - f * f * f * f;
    };

    Easing EASE_IN_EXPO = t -> t == 0.0f ? 0.0f : (float) Math.pow(2.0, 10.0 * (t - 1.0));
    Easing EASE_OUT_EXPO = t -> t == 1.0f ? 1.0f : 1.0f - (float) Math.pow(2.0, -10.0 * t);
    Easing EASE_IN_OUT_EXPO = t -> {
        if (t == 0.0f) return 0.0f;
        if (t == 1.0f) return 1.0f;
        if (t < 0.5f) return (float) Math.pow(2.0, 20.0 * t - 10.0) / 2.0f;
        return (2.0f - (float) Math.pow(2.0, -20.0 * t + 10.0)) / 2.0f;
    };

    Easing EASE_IN_SINE = t -> 1.0f - (float) Math.cos((t * Math.PI) / 2.0);
    Easing EASE_OUT_SINE = t -> (float) Math.sin((t * Math.PI) / 2.0);
    Easing EASE_IN_OUT_SINE = t -> -((float) Math.cos(Math.PI * t) - 1.0f) / 2.0f;

    Easing EASE_IN_BACK = t -> {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        return c3 * t * t * t - c1 * t * t;
    };

    Easing EASE_OUT_BACK = t -> {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float f = t - 1.0f;
        return 1.0f + c3 * f * f * f + c1 * f * f;
    };

    Easing EASE_IN_OUT_BACK = t -> {
        float c1 = 1.70158f;
        float c2 = c1 * 1.525f;
        return t < 0.5f
                ? ((float) Math.pow(2.0 * t, 2.0) * ((c2 + 1.0f) * 2.0f * t - c2)) / 2.0f
                : ((float) Math.pow(2.0 * t - 2.0, 2.0) * ((c2 + 1.0f) * (t * 2.0f - 2.0f) + c2) + 2.0f) / 2.0f;
    };

    Easing EASE_OUT_ELASTIC = t -> {
        if (t == 0.0f) return 0.0f;
        if (t == 1.0f) return 1.0f;
        float c4 = (float) ((2.0 * Math.PI) / 3.0);
        return (float) (Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0);
    };

    Easing EASE_OUT_BOUNCE = t -> {
        float n1 = 7.5625f;
        float d1 = 2.75f;
        if (t < 1.0f / d1) {
            return n1 * t * t;
        } else if (t < 2.0f / d1) {
            float f = t - 1.5f / d1;
            return n1 * f * f + 0.75f;
        } else if (t < 2.5f / d1) {
            float f = t - 2.25f / d1;
            return n1 * f * f + 0.9375f;
        } else {
            float f = t - 2.625f / d1;
            return n1 * f * f + 0.984375f;
        }
    };
}
