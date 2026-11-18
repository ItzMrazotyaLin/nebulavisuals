package me.itzmr.nebulavisuals.render.engine2d.util;

/**
 * Высокопроизводительные утилиты для работы с цветом без аллокаций.
 * Цвета представляются в виде 32-битного упакованного ARGB или RGBA целого числа.
 */
public final class ColorUtil {
    private ColorUtil() {}

    /**
     * Создает ARGB цвет из компонентов (0-255).
     */
    public static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /**
     * Создает ARGB цвет из RGB компонентов с полной непрозрачностью.
     */
    public static int rgb(int r, int g, int b) {
        return argb(255, r, g, b);
    }

    /**
     * Преобразует ARGB int (0xAARRGGBB) в формат RGBA int для OpenGL буфера (little-endian byte order: R, G, B, A).
     */
    public static int argbToRgba(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        // В little-endian архитектуре: byte0 = R, byte1 = G, byte2 = B, byte3 = A
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Линейная интерполяция между двумя ARGB цветами.
     */
    public static int lerp(int c1, int c2, float t) {
        t = Math.clamp(t, 0.0f, 1.0f);
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF, r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;

        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);

        return argb(a, r, g, b);
    }

    /**
     * Изменяет альфу (прозрачность) цвета (alphaFactor 0.0 - 1.0).
     */
    public static int withAlpha(int argb, float alphaFactor) {
        int a = (int) (((argb >> 24) & 0xFF) * Math.clamp(alphaFactor, 0.0f, 1.0f));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
