package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.WatermarkModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.ClickGuiScreen;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.texture.GlTexture;
import org.itzmr.nebulavisuals.mixin.client.BossBarHudAccessor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Рендерер HUD-модуля Watermark (RenderBatch2D + KawaseBlur + Medeo Light)
 * с плавным изменением ширины и умным обходом активных BossBar'ов.
 */
public final class WatermarkRenderer {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final float CAPSULE_HEIGHT = 28.0f;
    private static final float CAPSULE_RADIUS = 10.0f;
    private static final float MARGIN_EDGE = 8.0f;
    private static final float PAD_X = 12.0f;
    private static final float FONT_SIZE = 13.0f;
    private static final float SEP_GAP = 8.0f;
    private static final float SEP_DOT_SIZE = 3.0f;

    private static final List<String> SEGMENTS = new ArrayList<>(6);

    private static long lastTimeNs = 0L;
    private static float smoothWidth = -1.0f;
    private static float lastTargetScaledW = -1.0f;
    private static float smoothX = -1.0f;
    private static float smoothY = -1.0f;
    private static float fadeAlpha = 1.0f;

    private WatermarkRenderer() {}

    public static void render(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || client.getWindow() == null) {
            return;
        }
        if (client.options.hudHidden) {
            return;
        }
        // Рендерим на чистом HUD, в ChatScreen (режим перетаскивания) и поверх фона ClickGuiScreen
        if (client.currentScreen != null
                && !(client.currentScreen instanceof ChatScreen)
                && !(client.currentScreen instanceof ClickGuiScreen)) {
            return;
        }

        WatermarkModule module = ModuleManager.getWatermarkModule();
        if (module == null) {
            return;
        }

        float dt = computeHudDelta();
        float targetFade = module.isEnabled() ? 1.0f : 0.0f;
        fadeAlpha = AnimationEngine.expLerp(fadeAlpha, targetFade, 16.0f, dt);
        if (!module.isEnabled() && fadeAlpha <= 0.01f) {
            return;
        }

        if (!NebulaRenderEngine.isInitialized()) {
            NebulaRenderEngine.init();
        }
        if (!FontRenderer.hasDefaultFont()) {
            return;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        buildSegments(client, module);

        String brand = "NebulaVisuals";
        float brandW = FontRenderer.getWidth(brand, FONT_SIZE);
        float sepTotalW = SEP_GAP * 2.0f + SEP_DOT_SIZE;
        float contentW = brandW;
        for (String seg : SEGMENTS) {
            contentW += sepTotalW + FontRenderer.getWidth(seg, FONT_SIZE);
        }
        float targetWidth = Math.max(80.0f, contentW + PAD_X * 2.0f);
        if (smoothWidth < 0.0f) {
            smoothWidth = targetWidth;
        } else {
            smoothWidth = AnimationEngine.expLerp(smoothWidth, targetWidth, 14.0f, dt);
        }

        float hudScale = module.getHudScale();
        float targetScaledW = targetWidth * hudScale;
        float scaledW = smoothWidth * hudScale;
        float scaledH = CAPSULE_HEIGHT * hudScale;
        float scaledRadius = CAPSULE_RADIUS * hudScale;

        boolean dragging = HudEditor.isDragging(module) || HudEditor.isResizing(module);
        float maxTargetX = Math.max(MARGIN_EDGE, canvasW - targetScaledW - MARGIN_EDGE);

        // Умная якорная привязка (Left / Center / Right) по текущей трети экрана, чтобы текст не дёргался при изменении FPS/Ping
        float anchorX = module.getHudX();
        float refW = (lastTargetScaledW > 0.0f && !dragging) ? lastTargetScaledW : targetScaledW;
        float centerRatio = (anchorX + refW * 0.5f) / Math.max(1.0f, canvasW);

        if (!dragging && lastTargetScaledW > 0.0f) {
            float deltaW = targetScaledW - lastTargetScaledW;
            if (Math.abs(deltaW) > 0.01f) {
                float shiftedX = anchorX;
                if (centerRatio >= 0.35f && centerRatio <= 0.65f) {
                    shiftedX = anchorX - deltaW * 0.5f;
                } else if (centerRatio > 0.65f) {
                    shiftedX = anchorX - deltaW;
                }
                shiftedX = Math.clamp(shiftedX, MARGIN_EDGE, maxTargetX);
                module.setHudPositionSilently(shiftedX, module.getHudY());
                anchorX = shiftedX;
            }
        }
        lastTargetScaledW = targetScaledW;

        float maxX = Math.max(MARGIN_EDGE, canvasW - scaledW - MARGIN_EDGE);
        float maxY = Math.max(MARGIN_EDGE, canvasH - scaledH - MARGIN_EDGE);
        float targetX;
        if (!dragging && centerRatio >= 0.35f && centerRatio <= 0.65f) {
            float anchoredCenter = Math.clamp(anchorX, MARGIN_EDGE, maxTargetX) + targetScaledW * 0.5f;
            targetX = Math.clamp(anchoredCenter - scaledW * 0.5f, MARGIN_EDGE, maxX);
        } else if (!dragging && centerRatio > 0.65f) {
            float anchoredRight = Math.clamp(anchorX, MARGIN_EDGE, maxTargetX) + targetScaledW;
            targetX = Math.clamp(anchoredRight - scaledW, MARGIN_EDGE, maxX);
        } else {
            targetX = Math.clamp(anchorX, MARGIN_EDGE, maxX);
        }

        float baseY = Math.clamp(module.getHudY(), MARGIN_EDGE, maxY);
        float targetY = baseY;
        if (!dragging && module.avoidBossBars.getValue()) {
            targetY = computeBossBarSafeY(client, uiScale, fbW, fbH, targetX, baseY, scaledW, scaledH, canvasH);
        }

        if (dragging || smoothX < 0.0f || smoothY < 0.0f) {
            smoothX = targetX;
            smoothY = targetY;
        } else {
            // При якорном расширении удерживаем опорную точку без дёрганий
            smoothX = targetX;
            smoothY = AnimationEngine.expLerp(smoothY, targetY, 12.0f, dt);
        }

        module.updateRenderedBounds(smoothX, smoothY, scaledW, scaledH);
        if (client.currentScreen instanceof ClickGuiScreen clickGui
                && clickGui.isRectOverlappingWindow(smoothX, smoothY, scaledW, scaledH)) {
            return;
        }

        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        KawaseBlur blur = NebulaRenderEngine.getBlur();

        int blurredTex = 0;
        float blurStrength = ClientSettings.getBlurStrength();
        float blurFade = blurStrength <= 0.01f ? 0.0f : Math.clamp(blurStrength / 0.25f, 0.0f, 1.0f);
        if (blurFade > 0.001f && client.getFramebuffer() != null
                && client.getFramebuffer().getColorAttachment() instanceof GlTexture glTexture) {
            int fbTex = glTexture.getGlId();
            if (fbTex > 0) {
                float offset = Math.clamp(1.6f * blurStrength, 0.25f, 4.0f);
                blurredTex = blur.renderBlur(fbTex, fbW, fbH, 3, offset);
            }
        }

        int accent = ClientSettings.getAccentColor();
        int brandAccent = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.25f);
        int borderColor = ColorUtil.withAlpha(accent, 0.45f * fadeAlpha);
        float uiOpacity = ClientSettings.getUiOpacity();
        int bgColor = ColorUtil.argb((int) (235.0f * uiOpacity * fadeAlpha), 13, 15, 18);

        batch.begin(canvasW, canvasH, uiScale);

        // Тень + матовое стекло + тёмная подложка (под UI Opacity) + акцентная обводка 1px
        batch.drawShadow(smoothX, smoothY, scaledW, scaledH, scaledRadius, 12.0f * hudScale,
                ColorUtil.argb((int) (90 * fadeAlpha), 0, 0, 0));

        if (blurredTex != 0 && blurFade > 0.001f) {
            blur.drawBlurredRect(
                    batch, blurredTex,
                    smoothX, smoothY, scaledW, scaledH,
                    scaledRadius, canvasW, canvasH,
                    ColorUtil.argb((int) (255.0f * fadeAlpha * blurFade), 255, 255, 255)
            );
        }

        batch.drawRoundedRectWithOutline(
                smoothX, smoothY, scaledW, scaledH,
                scaledRadius, bgColor, 1.0f * hudScale, borderColor
        );

        // Текст и разделители с scissor-клиппингом внутри плавно меняющейся капсулы
        batch.beginScissor(smoothX + 1.0f, smoothY, Math.max(1.0f, scaledW - 2.0f), scaledH, canvasW, canvasH);

        float scaledFont = FONT_SIZE * hudScale;
        float scaledDot = SEP_DOT_SIZE * hudScale;
        float scaledGap = SEP_GAP * hudScale;
        float textY = smoothY + (scaledH - FontRenderer.getHeight(scaledFont)) * 0.5f;
        float dotY = smoothY + (scaledH - scaledDot) * 0.5f;
        float curX = smoothX + PAD_X * hudScale;

        // Акцентный индикатор слева + бренд NebulaVisuals
        FontRenderer.drawText(batch, brand, curX, textY, scaledFont, ColorUtil.withAlpha(brandAccent, fadeAlpha));
        curX += brandW * hudScale;

        int dotColor = ColorUtil.withAlpha(accent, 0.78f * fadeAlpha);
        int infoColor = ColorUtil.argb((int) (238 * fadeAlpha), 229, 231, 235);

        for (String seg : SEGMENTS) {
            curX += scaledGap;
            batch.drawRoundedRect(curX, dotY, scaledDot, scaledDot, scaledDot * 0.5f, dotColor);
            curX += scaledDot + scaledGap;

            FontRenderer.drawText(batch, seg, curX, textY, scaledFont, infoColor);
            curX += FontRenderer.getWidth(seg, scaledFont);
        }

        batch.endScissor();
        batch.end();
    }

    private static float computeHudDelta() {
        long now = System.nanoTime();
        double dt = (now - lastTimeNs) / 1_000_000_000.0;
        if (lastTimeNs == 0L || dt <= 0.0 || dt > 0.1) {
            dt = 0.016;
        }
        lastTimeNs = now;
        float rawDt = (float) Math.clamp(dt, 0.0001, 0.05);
        return Math.clamp(rawDt * ClientSettings.getAnimationSpeed(), 0.0001f, 0.1f);
    }

    private static void buildSegments(MinecraftClient client, WatermarkModule module) {
        SEGMENTS.clear();

        if (module.showUsername.getValue()) {
            String username = (client.getSession() != null && client.getSession().getUsername() != null)
                    ? client.getSession().getUsername()
                    : "Player";
            SEGMENTS.add(username);
        }

        if (module.showServer.getValue()) {
            String server = "Singleplayer";
            if (!client.isInSingleplayer()) {
                ServerInfo entry = client.getCurrentServerEntry();
                if (entry != null && entry.address != null && !entry.address.isBlank()) {
                    server = entry.address;
                } else {
                    server = "Multiplayer";
                }
            }
            SEGMENTS.add(server);
        }

        if (module.showFps.getValue()) {
            SEGMENTS.add(client.getCurrentFps() + " FPS");
        }

        if (module.showPing.getValue()) {
            int pingMs = 0;
            if (!client.isInSingleplayer() && client.getNetworkHandler() != null && client.player != null) {
                PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
                if (entry != null) {
                    pingMs = Math.max(0, entry.getLatency());
                }
            }
            SEGMENTS.add(pingMs + " ms");
        }

        if (module.showSystemTime.getValue()) {
            SEGMENTS.add(LocalTime.now().format(TIME_FORMATTER));
        }
    }

    private static float computeBossBarSafeY(MinecraftClient client, float uiScale, int fbW, int fbH,
                                             float wmX, float baseY, float wmW, float wmH, float canvasH) {
        var bossbarChanger = ModuleManager.getBossbarChangerModule();
        if (bossbarChanger != null && bossbarChanger.isEnabled()) {
            // При активном BossbarChanger боссбар сам уступает место Watermark во всех позициях
            return baseY;
        }
        if (client.inGameHud == null) return baseY;
        BossBarHud bossBarHud = client.inGameHud.getBossBarHud();
        if (bossBarHud == null) return baseY;

        Map<UUID, ClientBossBar> bossBars = ((BossBarHudAccessor) bossBarHud).nebulavisuals$getBossBars();
        if (bossBars == null || bossBars.isEmpty()) {
            return baseY;
        }

        int scaledW = Math.max(1, client.getWindow().getScaledWidth());
        int scaledH = Math.max(1, client.getWindow().getScaledHeight());

        // Повторяем геометрию BossBarHud.render(): старт y = 12, шаг = 19px (10 + 9), высота бара = 5px, лимит y < scaledH / 3
        int curScaledY = 12;
        int bottomScaledY = 0;
        int maxHalfWidth = 91;

        for (ClientBossBar bar : bossBars.values()) {
            if (curScaledY >= scaledH / 3) {
                break;
            }
            if (client.textRenderer != null && bar.getName() != null) {
                int titleW = client.textRenderer.getWidth(bar.getName());
                maxHalfWidth = Math.max(maxHalfWidth, titleW / 2);
            }
            bottomScaledY = curScaledY + 5;
            curScaledY += 19;
        }

        if (bottomScaledY <= 0) {
            return baseY;
        }

        float scaleToCanvasX = (float) fbW / (scaledW * uiScale);
        float scaleToCanvasY = (float) fbH / (scaledH * uiScale);

        float barLeftCanvas = (scaledW * 0.5f - maxHalfWidth) * scaleToCanvasX;
        float barRightCanvas = (scaledW * 0.5f + maxHalfWidth) * scaleToCanvasX;
        float bossBarTopCanvas = 6.0f * scaleToCanvasY;
        float bossBarBottomCanvas = bottomScaledY * scaleToCanvasY;

        boolean overlapsX = wmX + wmW + 6.0f > barLeftCanvas && wmX - 6.0f < barRightCanvas;
        boolean overlapsY = baseY < bossBarBottomCanvas + 6.0f && (baseY + wmH) > bossBarTopCanvas;
        if (!overlapsX || !overlapsY) {
            return baseY;
        }

        float maxY = Math.max(MARGIN_EDGE, canvasH - wmH - MARGIN_EDGE);
        return Math.min(maxY, Math.max(baseY, bossBarBottomCanvas + 6.0f));
    }
}
