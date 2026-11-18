package me.itzmr.nebulavisuals.render.engine2d;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.font.MsdfFont;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine2d.util.GlStateTracker;
import me.itzmr.nebulavisuals.render.engine2d.util.IconAtlas;
import me.itzmr.nebulavisuals.render.engine2d.util.Texture2D;

import java.util.HashMap;
import java.util.Map;

/**
 * Глобальный менеджер и точка доступа к 2D-движку рендеринга Nebula Visuals.
 * Синглтон предотвращает повторные аллокации буферов и FBO при открытии экранов.
 */
public final class NebulaRenderEngine {
    private static RenderBatch2D sharedBatch;
    private static KawaseBlur sharedBlur;
    private static final Map<String, Texture2D> TEXTURES = new HashMap<>();
    private static volatile boolean initialized = false;
    private static boolean initializing = false;

    private NebulaRenderEngine() {}

    public static boolean isInitialized() {
        return initialized;
    }

    public static synchronized void init() {
        if (initialized || initializing) return;
        initializing = true;
        GlStateTracker initTracker = new GlStateTracker();
        initTracker.push();
        try {
            if (sharedBatch == null) {
                sharedBatch = new RenderBatch2D();
            }
            if (sharedBlur == null) {
                sharedBlur = new KawaseBlur();
            }

            // Инициализация MSDF шрифта Medeo Light (основной шрифт UI из Figma)
            if (!FontRenderer.hasDefaultFont()) {
                try {
                    MsdfFont medeoLight = FontRenderer.registerFont(
                            "medeo-light",
                            "/assets/nebulavisuals/fonts/medeo-light.json",
                            "/assets/nebulavisuals/fonts/medeo-light.png"
                    );
                    FontRenderer.setDefaultFont(medeoLight);
                } catch (Exception e) {
                    System.err.println("[NebulaVisuals] ERROR: Failed to load Medeo Light MSDF font: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // Регистрация логотипа NebulaPVP и атласа MSDF-иконок
            registerTexture("logo", "/assets/nebulavisuals/png/logo.png");
            IconAtlas.init();

            // Инициализация модульной системы
            ModuleManager.init();

            initialized = true;
        } finally {
            initTracker.pop();
            initializing = false;
        }
    }

    public static Texture2D registerTexture(String key, String resourcePath) {
        if (key == null || resourcePath == null) return null;
        String normalized = key.toLowerCase();
        Texture2D existing = TEXTURES.get(normalized);
        if (existing != null) {
            return existing;
        }
        try {
            Texture2D loaded = Texture2D.fromResource(resourcePath);
            TEXTURES.put(normalized, loaded);
            System.out.println("[NebulaVisuals] Texture registered '" + normalized + "': " + loaded.getWidth() + "x" + loaded.getHeight() + ", texID=" + loaded.getTextureId());
            return loaded;
        } catch (Exception e) {
            System.err.println("[NebulaVisuals] ERROR: Failed to load texture '" + normalized + "' from " + resourcePath + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public static Texture2D getTexture(String key) {
        if (!initialized) init();
        return key == null ? null : TEXTURES.get(key.toLowerCase());
    }

    public static Texture2D getLogoTexture() {
        return getTexture("logo");
    }

    /**
     * Переиспользуемый хелпер отрисовки монохромного логотипа {@code logo.png},
     * динамически тонированного в глобальный {@code Accent Color}, с поворотом вокруг центра.
     */
    public static void renderAccentLogo(RenderBatch2D batch, float centerX, float centerY,
                                        float size, float rotationDegrees, float alpha) {
        renderAccentLogoClipped(batch, centerX, centerY, size, rotationDegrees, alpha,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    /**
     * Отрисовывает обрезанную по видимым границам глифа иконку логотипа {@code logo.png}
     * (без поворота, с выравниванием по верхнему левому углу {@code (x, y)}) в текущем {@code Accent Color}.
     */
    public static void renderAccentLogoIcon(RenderBatch2D batch, float x, float y, float height, float alpha) {
        if (batch == null || height <= 0.0f || alpha <= 0.001f) {
            return;
        }
        Texture2D logoTex = getLogoTexture();
        if (logoTex == null || logoTex.getTextureId() <= 0) {
            return;
        }
        int accentTint = ColorUtil.withAlpha(ClientSettings.getAccentColor(), Math.clamp(alpha, 0.0f, 1.0f));
        if (((accentTint >>> 24) & 0xFF) == 0) {
            return;
        }
        // Видимая часть глифа N внутри 2000x2000 logo.png: [500..1420] x [492..1548]
        float u1 = 0.250f;
        float v1 = 0.246f;
        float u2 = 0.710f;
        float v2 = 0.774f;
        float width = height * ((u2 - u1) / (v2 - v1));
        batch.drawTexturedRect(logoTex.getTextureId(), x, y, width, height, u1, v1, u2, v2, accentTint);
    }

    /**
     * Отрисовывает обрезанный по глифу логотип {@code logo.png} вокруг центра {@code (centerX, centerY)}
     * с поворотом на {@code rotationDegrees} (для бейджа в левом верхнем углу шапки ClickGUI).
     */
    public static void renderAccentLogoBadge(RenderBatch2D batch, float centerX, float centerY,
                                             float height, float rotationDegrees, float alpha) {
        if (batch == null || height <= 0.0f || alpha <= 0.001f) {
            return;
        }
        Texture2D logoTex = getLogoTexture();
        if (logoTex == null || logoTex.getTextureId() <= 0) {
            return;
        }
        int accentTint = ColorUtil.withAlpha(ClientSettings.getAccentColor(), Math.clamp(alpha, 0.0f, 1.0f));
        if (((accentTint >>> 24) & 0xFF) == 0) {
            return;
        }
        float u1 = 0.250f;
        float v1 = 0.246f;
        float u2 = 0.710f;
        float v2 = 0.774f;
        float width = height * ((u2 - u1) / (v2 - v1));
        batch.drawRotatedTexturedRect(
                logoTex.getTextureId(),
                centerX, centerY,
                width, height,
                rotationDegrees,
                u1, v1, u2, v2,
                accentTint
        );
    }

    public static void renderAccentLogoClipped(RenderBatch2D batch, float centerX, float centerY,
                                               float size, float rotationDegrees, float alpha,
                                               float clipX, float clipY, float clipW, float clipH,
                                               float rTL, float rTR, float rBR, float rBL) {
        if (batch == null || size <= 0.0f || alpha <= 0.001f) {
            return;
        }
        Texture2D logoTex = getLogoTexture();
        if (logoTex == null || logoTex.getTextureId() <= 0) {
            return;
        }

        int accentTint = ColorUtil.withAlpha(ClientSettings.getAccentColor(), Math.clamp(alpha, 0.0f, 1.0f));
        if (((accentTint >>> 24) & 0xFF) == 0) {
            return;
        }

        float texW = Math.max(1, logoTex.getWidth());
        float texH = Math.max(1, logoTex.getHeight());
        float aspect = texW / texH;
        float drawW = aspect >= 1.0f ? size : size * aspect;
        float drawH = aspect >= 1.0f ? size / aspect : size;

        batch.drawRotatedTexturedRectClipped(
                logoTex.getTextureId(),
                centerX, centerY,
                drawW, drawH,
                rotationDegrees,
                0.0f, 0.0f, 1.0f, 1.0f,
                accentTint,
                clipX, clipY, clipW, clipH,
                rTL, rTR, rBR, rBL
        );
    }

    public static RenderBatch2D getBatch() {
        if (!initialized) init();
        return sharedBatch;
    }

    public static KawaseBlur getBlur() {
        if (!initialized) init();
        return sharedBlur;
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        if (sharedBatch != null) {
            sharedBatch.close();
            sharedBatch = null;
        }
        if (sharedBlur != null) {
            sharedBlur.close();
            sharedBlur = null;
        }
        for (Texture2D tex : TEXTURES.values()) {
            tex.close();
        }
        TEXTURES.clear();
        IconAtlas.cleanup();
        FontRenderer.cleanup();
        me.itzmr.nebulavisuals.render.visuals.MotionBlurRenderer.shutdown();
        initialized = false;
    }
}
