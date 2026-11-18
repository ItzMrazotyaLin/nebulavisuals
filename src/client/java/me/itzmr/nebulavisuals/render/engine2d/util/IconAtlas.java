package me.itzmr.nebulavisuals.render.engine2d.util;

import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.font.MsdfFont;
import me.itzmr.nebulavisuals.render.engine2d.font.MsdfGlyph;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Атлас векторных иконок Nebula Visuals на базе MSDF-атласа {@code assets/nebulavisuals/png/icons.png} + {@code icons.json}.
 * Поддерживает точное геометрическое центрирование, плавный поворот и программный fallback {@code 128x64} (тайлы 64x64).
 */
public final class IconAtlas {
    public enum Icon {
        PLUS(0xE145, 0),        // 57669: add / create
        MINUS(0xE15B, 1),       // 57691: remove
        SAVE(0xE161, 0),        // 57697: save / overwrite
        VISIBILITY(0xE8F4, 0),  // 59636: visibility (Visuals)
        HUD(0xE3B4, 0),         // 58292: center_focus_strong (HUD)
        PALETTE(0xE40A, 0),     // 58378: palette (Utilities)
        SETTINGS(0xE8B8, 0),    // 59576: settings (Settings)
        BOLT(0xE3E7, 0),        // 58343: bolt (Optimization)
        FOLDER(0xE2C7, 0),      // 58055: folder (Configs)
        REFRESH(0xE5D5, 0),     // 58837: refresh
        RESET(0xE8B3, 0),       // 59571: reset to defaults
        DELETE(0xE872, 1),      // 59506: delete
        RENAME(0xE3C9, 0),      // 58313: edit / rename
        LOAD(0xE2C4, 0),        // 58052: download / load
        INFO(0xE88E, 0),        // 59534: info (About)
        CHECK(0xE5CA, 0),       // 58826
        CLOSE(0xE5CD, 1),       // 58829
        CHEVRON_UP(0xE5CE, 1),  // 58830
        CHEVRON_DOWN(0xE5CF, 0);// 58831

        final int unicode;
        final int fallbackTileX;

        Icon(int unicode, int fallbackTileX) {
            this.unicode = unicode;
            this.fallbackTileX = fallbackTileX;
        }
    }

    private static final String FONTS_ICONS_JSON = "/assets/nebulavisuals/fonts/icons.json";
    private static final String FONTS_ICONS_PNG = "/assets/nebulavisuals/fonts/icons.png";
    private static final String PNG_ICONS_JSON = "/assets/nebulavisuals/png/icons.json";
    private static final String PNG_ICONS_PNG = "/assets/nebulavisuals/png/icons.png";

    private static MsdfFont msdfIcons;
    private static int fallbackTexId = 0;
    private static boolean initialized = false;

    private IconAtlas() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            if (IconAtlas.class.getResource(FONTS_ICONS_JSON) != null
                    && IconAtlas.class.getResource(FONTS_ICONS_PNG) != null) {
                msdfIcons = new MsdfFont(FONTS_ICONS_JSON, FONTS_ICONS_PNG);
            } else {
                msdfIcons = new MsdfFont(PNG_ICONS_JSON, PNG_ICONS_PNG);
            }
        } catch (Exception e) {
            System.err.println("[NebulaVisuals] WARN: Failed to load MSDF icons atlas (" + PNG_ICONS_PNG + "), generating fallback 128x64 RGBA texture: " + e.getMessage());
            createFallbackAtlas();
        }
    }

    /**
     * Рисует иконку строго по центру (centerX, centerY) с поддержкой угла поворота (в радианах).
     */
    public static void drawIcon(RenderBatch2D batch, Icon icon, float centerX, float centerY, float size, float angleRad, int argbColor) {
        if (!initialized) init();
        if (icon == null || batch == null) return;

        if (msdfIcons != null) {
            MsdfGlyph g = msdfIcons.getGlyph(icon.unicode);
            if (g != null && g.hasPlaneBounds() && g.hasAtlasBounds()) {
                float pw = Math.max(0.001f, g.planeRight() - g.planeLeft());
                float ph = Math.max(0.001f, g.planeTop() - g.planeBottom());
                float maxDim = Math.max(pw, ph);
                float halfW = (pw / maxDim) * size * 0.5f;
                float halfH = (ph / maxDim) * size * 0.5f;

                int aw = msdfIcons.getAtlasWidth();
                int ah = msdfIcons.getAtlasHeight();
                float u0 = g.atlasLeft() / aw;
                float u1 = g.atlasRight() / aw;
                float v0;
                float v1;
                if (msdfIcons.isYOriginBottom()) {
                    v0 = (ah - g.atlasTop()) / (float) ah;
                    v1 = (ah - g.atlasBottom()) / (float) ah;
                } else {
                    v0 = g.atlasTop() / (float) ah;
                    v1 = g.atlasBottom() / (float) ah;
                }

                float cos = angleRad == 0.0f ? 1.0f : (float) Math.cos(angleRad);
                float sin = angleRad == 0.0f ? 0.0f : (float) Math.sin(angleRad);

                float tlX = centerX + (-halfW * cos - (-halfH) * sin);
                float tlY = centerY + (-halfW * sin + (-halfH) * cos);
                float blX = centerX + (-halfW * cos - halfH * sin);
                float blY = centerY + (-halfW * sin + halfH * cos);
                float brX = centerX + (halfW * cos - halfH * sin);
                float brY = centerY + (halfW * sin + halfH * cos);
                float trX = centerX + (halfW * cos - (-halfH) * sin);
                float trY = centerY + (halfW * sin + (-halfH) * cos);

                batch.setMsdfPxRange(msdfIcons.getDistanceRange());
                batch.drawMsdfQuad(
                        msdfIcons.getTextureId(),
                        tlX, tlY, blX, blY, brX, brY, trX, trY,
                        u0, v0, u1, v1,
                        msdfIcons.getDistanceRange(),
                        argbColor
                );
                return;
            }
        }

        if (fallbackTexId == 0) {
            createFallbackAtlas();
        }
        if (fallbackTexId != 0) {
            float u0 = icon.fallbackTileX * 0.5f;
            float u1 = u0 + 0.5f;
            float half = size * 0.5f;
            batch.drawTexturedRect(fallbackTexId, centerX - half, centerY - half, size, size, u0, 0.0f, u1, 1.0f, argbColor);
        }
    }

    public static void drawIcon(RenderBatch2D batch, Icon icon, float centerX, float centerY, float size, int argbColor) {
        drawIcon(batch, icon, centerX, centerY, size, 0.0f, argbColor);
    }

    private static void createFallbackAtlas() {
        int w = 128;
        int h = 64;
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int tileX = x / 64;
                    int lx = x % 64;
                    int ly = y;
                    float dx = Math.abs(lx - 31.5f);
                    float dy = Math.abs(ly - 31.5f);

                    boolean horizBar = dx <= 16.0f && dy <= 3.0f;
                    boolean vertBar = tileX == 0 && dy <= 16.0f && dx <= 3.0f;
                    byte a = (horizBar || vertBar) ? (byte) 255 : (byte) 0;
                    buf.put((byte) 255).put((byte) 255).put((byte) 255).put(a);
                }
            }
            buf.flip();

            fallbackTexId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackTexId);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    public static synchronized void cleanup() {
        if (msdfIcons != null) {
            msdfIcons.close();
            msdfIcons = null;
        }
        if (fallbackTexId != 0) {
            GL11.glDeleteTextures(fallbackTexId);
            fallbackTexId = 0;
        }
        initialized = false;
    }
}
