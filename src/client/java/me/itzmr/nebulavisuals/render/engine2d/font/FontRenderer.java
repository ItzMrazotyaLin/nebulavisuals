package me.itzmr.nebulavisuals.render.engine2d.font;

import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Реестр и фасад для управления наборами шрифтов MSDF в моде Nebula Visuals.
 */
public final class FontRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NebulaFont");
    private static final Map<String, MsdfFont> FONTS = new HashMap<>();
    private static MsdfFont defaultFont;
    private static boolean loggedDrawDiag = false;

    private FontRenderer() {}

    /**
     * Регистрирует шрифт из папки `assets/nebulavisuals/fonts/`.
     * 
     * @param fontKey        Уникальный ключ (например, "regular", "bold", "inter-medium")
     * @param jsonAssetPath  Путь к JSON-метрикам в ресурсах
     * @param pngAssetPath   Путь к PNG-атласу в ресурсах
     */
    public static MsdfFont registerFont(String fontKey, String jsonAssetPath, String pngAssetPath) {
        MsdfFont font = new MsdfFont(jsonAssetPath, pngAssetPath);
        FONTS.put(fontKey.toLowerCase(), font);
        if (defaultFont == null) {
            defaultFont = font;
        }
        return font;
    }

    public static MsdfFont getFont(String fontKey) {
        return FONTS.getOrDefault(fontKey.toLowerCase(), defaultFont);
    }

    public static boolean hasDefaultFont() {
        return defaultFont != null;
    }

    public static MsdfFont getDefaultFont() {
        if (defaultFont == null && !me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine.isInitialized()) {
            me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine.init();
        }
        return defaultFont;
    }

    public static void setDefaultFont(MsdfFont font) {
        defaultFont = font;
    }

    // =========================================================================
    // Быстрые делегаты через дефолтный шрифт
    // =========================================================================

    public static void drawText(RenderBatch2D batch, CharSequence text, float x, float y, float size, int color) {
        MsdfFont font = getDefaultFont();
        if (font != null) {
            if (!loggedDrawDiag && text != null && text.length() > 0) {
                loggedDrawDiag = true;
                LOGGER.info("[NebulaFont-Diag] drawText sample='{}', texId={}, size={}, color=0x{}, alpha={}",
                        text, font.getTextureId(), size, Integer.toHexString(color), (color >>> 24) & 0xFF);
            }
            font.drawText(batch, text, x, y, size, color);
        }
    }

    public static void drawCenteredText(RenderBatch2D batch, CharSequence text, float centerX, float y, float size, int color) {
        MsdfFont font = getDefaultFont();
        if (font != null) {
            font.drawCenteredText(batch, text, centerX, y, size, color);
        }
    }

    public static void drawGradientText(RenderBatch2D batch, CharSequence text, float x, float y, float size, int c1, int c2) {
        MsdfFont font = getDefaultFont();
        if (font != null) {
            font.drawGradientText(batch, text, x, y, size, c1, c2);
        }
    }

    public static float getWidth(CharSequence text, float size) {
        MsdfFont font = getDefaultFont();
        return font != null ? font.getStringWidth(text, size) : 0.0f;
    }

    public static float getHeight(float size) {
        MsdfFont font = getDefaultFont();
        return font != null ? font.getStringHeight(size) : 0.0f;
    }

    public static void cleanup() {
        for (MsdfFont font : FONTS.values()) {
            font.close();
        }
        FONTS.clear();
        defaultFont = null;
    }
}
