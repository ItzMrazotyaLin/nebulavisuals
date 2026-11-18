package me.itzmr.nebulavisuals.render.engine2d.font;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine2d.util.Texture2D;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Загрузчик и рендерер MSDF (Multi-channel Signed Distance Field) шрифтов.
 * 
 * Полная совместимость с JSON форматом генератора `msdf-atlas-gen`.
 * Поддерживает:
 * - Субпиксельное сглаживание и четкость текста при любом масштабе.
 * - Кернинг пар символов.
 * - Быструю таблицу поиска ASCII (0..255) + расширенную таблицу Unicode/Кириллицы.
 * - Расчет размеров строк, автоматическую обрезку с многоточием (...) и центрирование.
 */
public class MsdfFont implements AutoCloseable {
    private final Texture2D atlasTexture;
    private final int textureId;
    private final int atlasWidth;
    private final int atlasHeight;
    private final float distanceRange;
    private final boolean yOriginBottom;

    private final float emSize;
    private final float lineHeight;
    private final float ascender;
    private final float descender;

    // Быстрый кэш глифов для ASCII
    private final MsdfGlyph[] asciiGlyphs = new MsdfGlyph[256];
    private final Map<Integer, MsdfGlyph> extendedGlyphs = new HashMap<>();
    private final Map<Long, Float> kerningTable = new HashMap<>();

    public MsdfFont(String jsonResourcePath, String pngResourcePath) {
        String jsonContent = loadResourceString(jsonResourcePath);
        JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();

        // 1. Чтение секции atlas
        JsonObject atlasObj = root.getAsJsonObject("atlas");
        this.atlasWidth = atlasObj.get("width").getAsInt();
        this.atlasHeight = atlasObj.get("height").getAsInt();
        this.distanceRange = atlasObj.has("distanceRange") ? atlasObj.get("distanceRange").getAsFloat() : 4.0f;
        String yOrigin = atlasObj.has("yOrigin") ? atlasObj.get("yOrigin").getAsString() : "bottom";
        this.yOriginBottom = "bottom".equalsIgnoreCase(yOrigin);

        // 2. Чтение секции metrics
        JsonObject metricsObj = root.getAsJsonObject("metrics");
        this.emSize = metricsObj.get("emSize").getAsFloat();
        this.lineHeight = metricsObj.get("lineHeight").getAsFloat();
        this.ascender = metricsObj.get("ascender").getAsFloat();
        this.descender = metricsObj.get("descender").getAsFloat();

        // 3. Загрузка глифов
        JsonArray glyphsArray = root.getAsJsonArray("glyphs");
        for (JsonElement elem : glyphsArray) {
            JsonObject gObj = elem.getAsJsonObject();
            int unicode = gObj.get("unicode").getAsInt();
            float advance = gObj.get("advance").getAsFloat();

            boolean hasPlane = gObj.has("planeBounds");
            float pl = 0, pb = 0, pr = 0, pt = 0;
            if (hasPlane) {
                JsonObject pbObj = gObj.getAsJsonObject("planeBounds");
                pl = pbObj.get("left").getAsFloat();
                pb = pbObj.get("bottom").getAsFloat();
                pr = pbObj.get("right").getAsFloat();
                pt = pbObj.get("top").getAsFloat();
            }

            boolean hasAtlas = gObj.has("atlasBounds");
            float al = 0, ab = 0, ar = 0, at = 0;
            if (hasAtlas) {
                JsonObject abObj = gObj.getAsJsonObject("atlasBounds");
                al = abObj.get("left").getAsFloat();
                ab = abObj.get("bottom").getAsFloat();
                ar = abObj.get("right").getAsFloat();
                at = abObj.get("top").getAsFloat();
            }

            MsdfGlyph glyph = new MsdfGlyph(unicode, advance, hasPlane, pl, pb, pr, pt, hasAtlas, al, ab, ar, at);
            if (unicode >= 0 && unicode < 256) {
                asciiGlyphs[unicode] = glyph;
            } else {
                extendedGlyphs.put(unicode, glyph);
            }
        }

        // 4. Загрузка таблицы кернинга
        if (root.has("kerning")) {
            JsonArray kerningArray = root.getAsJsonArray("kerning");
            for (JsonElement elem : kerningArray) {
                JsonObject kObj = elem.getAsJsonObject();
                int u1 = kObj.get("unicode1").getAsInt();
                int u2 = kObj.get("unicode2").getAsInt();
                float advance = kObj.get("advance").getAsFloat();
                kerningTable.put((((long) u1) << 32) | (u2 & 0xFFFFFFFFL), advance);
            }
        }

        // 5. Загрузка PNG текстуры атласа в OpenGL
        this.atlasTexture = Texture2D.fromResource(pngResourcePath);
        this.textureId = atlasTexture.getTextureId();

        System.out.println("[NebulaVisuals] MSDF font successfully loaded: " + glyphsArray.size() + " glyphs (ASCII + Extended=" + extendedGlyphs.size() + "), atlas=" + atlasWidth + "x" + atlasHeight + ", distanceRange=" + distanceRange + ", yOriginBottom=" + yOriginBottom + ", texID=" + textureId);
    }

    public MsdfGlyph getGlyph(int unicode) {
        if (unicode >= 0 && unicode < 256) {
            return asciiGlyphs[unicode];
        }
        return extendedGlyphs.get(unicode);
    }

    public float getKerning(int u1, int u2) {
        if (kerningTable.isEmpty()) return 0.0f;
        long key = (((long) u1) << 32) | (u2 & 0xFFFFFFFFL);
        Float val = kerningTable.get(key);
        return val != null ? val : 0.0f;
    }

    // =========================================================================
    // Расчет метрик и геометрии
    // =========================================================================

    public float getStringWidth(CharSequence text, float size) {
        if (text == null || text.isEmpty()) return 0.0f;
        float scale = size / emSize;
        float width = 0.0f;
        int prev = -1;

        for (int i = 0; i < text.length(); i++) {
            int cp = Character.codePointAt(text, i);
            if (Character.isSupplementaryCodePoint(cp)) i++;

            if (prev != -1) {
                width += getKerning(prev, cp) * scale;
            }
            MsdfGlyph glyph = getGlyph(cp);
            if (glyph != null) {
                width += glyph.advance() * scale;
            }
            prev = cp;
        }
        return width;
    }

    public float getStringHeight(float size) {
        return lineHeight * (size / emSize);
    }

    public String trimToWidth(CharSequence text, float maxWidth, float size, boolean ellipsis) {
        if (text == null || text.isEmpty()) return "";
        float currentWidth = getStringWidth(text, size);
        if (currentWidth <= maxWidth) return text.toString();

        float ellipsisWidth = ellipsis ? getStringWidth("...", size) : 0.0f;
        float availableWidth = Math.max(0.0f, maxWidth - ellipsisWidth);

        float width = 0.0f;
        int prev = -1;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            int cp = Character.codePointAt(text, i);
            int charCount = Character.charCount(cp);

            float kern = (prev != -1) ? getKerning(prev, cp) * (size / emSize) : 0.0f;
            MsdfGlyph glyph = getGlyph(cp);
            float adv = (glyph != null ? glyph.advance() : 0.0f) * (size / emSize);

            if (width + kern + adv > availableWidth) {
                break;
            }

            width += kern + adv;
            sb.appendCodePoint(cp);
            prev = cp;
            if (charCount > 1) i++;
        }

        if (ellipsis) {
            sb.append("...");
        }
        return sb.toString();
    }

    // =========================================================================
    // Отрисовка текста через RenderBatch2D
    // =========================================================================

    public void drawText(RenderBatch2D batch, CharSequence text, float x, float y, float size, int argbColor) {
        if (text == null || text.isEmpty()) return;
        batch.setMsdfPxRange(distanceRange);
        float scale = size / emSize;
        float curX = batch.snapToPixel(x);
        float baselineY = batch.snapToPixel(y) + ascender * scale;
        int prev = -1;

        for (int i = 0; i < text.length(); i++) {
            int cp = Character.codePointAt(text, i);
            if (Character.isSupplementaryCodePoint(cp)) i++;

            if (prev != -1) {
                curX += getKerning(prev, cp) * scale;
            }

            MsdfGlyph g = getGlyph(cp);
            if (g != null) {
                if (g.hasPlaneBounds() && g.hasAtlasBounds()) {
                    float x0 = curX + g.planeLeft() * scale;
                    float x1 = curX + g.planeRight() * scale;
                    float y0 = baselineY - g.planeTop() * scale;
                    float y1 = baselineY - g.planeBottom() * scale;

                    float u0 = g.atlasLeft() / atlasWidth;
                    float u1 = g.atlasRight() / atlasWidth;

                    float v0, v1;
                    if (yOriginBottom) {
                        v0 = (atlasHeight - g.atlasTop()) / (float) atlasHeight;
                        v1 = (atlasHeight - g.atlasBottom()) / (float) atlasHeight;
                    } else {
                        v0 = g.atlasTop() / (float) atlasHeight;
                        v1 = g.atlasBottom() / (float) atlasHeight;
                    }

                    batch.drawMsdfGlyph(textureId, x0, y0, x1, y1, u0, v0, u1, v1, distanceRange, argbColor);
                }
                curX += g.advance() * scale;
            }
            prev = cp;
        }
    }

    public void drawCenteredText(RenderBatch2D batch, CharSequence text, float centerX, float y, float size, int argbColor) {
        float width = getStringWidth(text, size);
        drawText(batch, text, centerX - width * 0.5f, y, size, argbColor);
    }

    public void drawGradientText(RenderBatch2D batch, CharSequence text, float x, float y, float size, int startColor, int endColor) {
        if (text == null || text.isEmpty()) return;
        batch.setMsdfPxRange(distanceRange);
        float totalWidth = getStringWidth(text, size);
        float scale = size / emSize;
        float snappedX = batch.snapToPixel(x);
        float curX = snappedX;
        float baselineY = batch.snapToPixel(y) + ascender * scale;
        int prev = -1;

        for (int i = 0; i < text.length(); i++) {
            int cp = Character.codePointAt(text, i);
            if (Character.isSupplementaryCodePoint(cp)) i++;

            if (prev != -1) {
                curX += getKerning(prev, cp) * scale;
            }

            float t = totalWidth > 0 ? (curX - snappedX) / totalWidth : 0.0f;
            int charColor = ColorUtil.lerp(startColor, endColor, t);

            MsdfGlyph g = getGlyph(cp);
            if (g != null) {
                if (g.hasPlaneBounds() && g.hasAtlasBounds()) {
                    float x0 = curX + g.planeLeft() * scale;
                    float x1 = curX + g.planeRight() * scale;
                    float y0 = baselineY - g.planeTop() * scale;
                    float y1 = baselineY - g.planeBottom() * scale;

                    float u0 = g.atlasLeft() / atlasWidth;
                    float u1 = g.atlasRight() / atlasWidth;

                    float v0, v1;
                    if (yOriginBottom) {
                        v0 = (atlasHeight - g.atlasTop()) / (float) atlasHeight;
                        v1 = (atlasHeight - g.atlasBottom()) / (float) atlasHeight;
                    } else {
                        v0 = g.atlasTop() / (float) atlasHeight;
                        v1 = g.atlasBottom() / (float) atlasHeight;
                    }

                    batch.drawMsdfGlyph(textureId, x0, y0, x1, y1, u0, v0, u1, v1, distanceRange, charColor);
                }
                curX += g.advance() * scale;
            }
            prev = cp;
        }
    }

    // =========================================================================
    // Загрузка ресурсов
    // =========================================================================

    private static String loadResourceString(String path) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Texture2D.openResourceStream(path), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read font json: " + path, e);
        }
    }

    public int getTextureId() {
        return textureId;
    }

    public int getAtlasWidth() {
        return atlasWidth;
    }

    public int getAtlasHeight() {
        return atlasHeight;
    }

    public float getDistanceRange() {
        return distanceRange;
    }

    public boolean isYOriginBottom() {
        return yOriginBottom;
    }

    public float getLineHeight() {
        return lineHeight;
    }

    public float getAscender() {
        return ascender;
    }

    public float getDescender() {
        return descender;
    }

    public float getEmSize() {
        return emSize;
    }

    @Override
    public void close() {
        atlasTexture.close();
    }
}
