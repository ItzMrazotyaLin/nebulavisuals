package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.KeystrokesModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.ClickGuiScreen;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.texture.GlTexture;
import org.lwjgl.glfw.GLFW;

/**
 * Рендерер HUD-модуля {@link KeystrokesModule}: стеклянные карточки W/A/S/D, LMB/RMB (с CPS) и SPACE
 * с плавной подсветкой нажатия и поддержкой перетаскивания в чате.
 */
public final class KeystrokesRenderer {
    private static final float MARGIN_EDGE = 8.0f;
    private static final float KEY_SIZE = 34.0f;
    private static final float KEY_GAP = 4.0f;
    private static final float MOUSE_HEIGHT = 30.0f;
    private static final float SPACE_HEIGHT = 22.0f;
    private static final float CARD_RADIUS = 8.0f;
    private static final float TOTAL_WIDTH = KEY_SIZE * 3.0f + KEY_GAP * 2.0f; // 110.0f

    private static final long ONE_SECOND_NS = 1_000_000_000L;
    private static final int CPS_BUF_SIZE = 128;

    private static final long[] LMB_TIMES = new long[CPS_BUF_SIZE];
    private static final long[] RMB_TIMES = new long[CPS_BUF_SIZE];
    private static int lmbCursor = 0;
    private static int rmbCursor = 0;

    // Индексы: 0=W, 1=A, 2=S, 3=D, 4=LMB, 5=RMB, 6=SPACE
    private static final float[] KEY_PRESS_ANIM = new float[7];

    private static long lastTimeNs = 0L;
    private static float fadeAlpha = 0.0f;
    private static float smoothX = -1.0f;
    private static float smoothY = -1.0f;

    private KeystrokesRenderer() {}

    public static void onMouseClick(int button) {
        long now = System.nanoTime();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            LMB_TIMES[lmbCursor] = now;
            lmbCursor = (lmbCursor + 1) % CPS_BUF_SIZE;
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            RMB_TIMES[rmbCursor] = now;
            rmbCursor = (rmbCursor + 1) % CPS_BUF_SIZE;
        }
    }

    private static int countRecentClicks(long[] times, long now) {
        int count = 0;
        for (int i = 0; i < times.length; i++) {
            long t = times[i];
            if (t != 0L && (now - t) <= ONE_SECOND_NS) {
                count++;
            }
        }
        return count;
    }

    public static void render(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || client.getWindow() == null) {
            return;
        }
        if (client.options.hudHidden) {
            return;
        }
        if (client.currentScreen != null
                && !(client.currentScreen instanceof ChatScreen)
                && !(client.currentScreen instanceof ClickGuiScreen)) {
            return;
        }

        KeystrokesModule module = ModuleManager.getKeystrokesModule();
        if (module == null) {
            return;
        }

        float dt = computeDeltaTime();
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

        boolean showMouse = module.showMouse.getValue();
        boolean showSpace = module.showSpace.getValue();
        boolean showCps = module.showCps.getValue();

        float totalH = KEY_SIZE * 2.0f + KEY_GAP;
        if (showMouse) {
            totalH += KEY_GAP + MOUSE_HEIGHT;
        }
        if (showSpace) {
            totalH += KEY_GAP + SPACE_HEIGHT;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        float hudScale = module.getHudScale();
        float scaledW = TOTAL_WIDTH * hudScale;
        float scaledH = totalH * hudScale;

        float maxX = Math.max(MARGIN_EDGE, canvasW - scaledW - MARGIN_EDGE);
        float maxY = Math.max(MARGIN_EDGE, canvasH - scaledH - MARGIN_EDGE);
        float targetX = Math.clamp(module.getHudX(), MARGIN_EDGE, maxX);
        float targetY = Math.clamp(module.getHudY(), MARGIN_EDGE, maxY);

        boolean dragging = HudEditor.isDragging(module) || HudEditor.isResizing(module);
        if (dragging || smoothX < 0.0f || smoothY < 0.0f) {
            smoothX = targetX;
            smoothY = targetY;
        } else {
            smoothX = AnimationEngine.expLerp(smoothX, targetX, 18.0f, dt);
            smoothY = AnimationEngine.expLerp(smoothY, targetY, 18.0f, dt);
        }

        module.updateRenderedBounds(smoothX, smoothY, scaledW, scaledH);
        if (client.currentScreen instanceof ClickGuiScreen clickGui
                && clickGui.isRectOverlappingWindow(smoothX, smoothY, scaledW, scaledH)) {
            return;
        }

        // Обновляем плавные анимации нажатий (при открытом чате / ClickGUI клавиши не подсвечиваются фантомно)
        boolean allowInput = client.currentScreen == null;
        KEY_PRESS_ANIM[0] = AnimationEngine.expLerp(KEY_PRESS_ANIM[0], (allowInput && client.options.forwardKey.isPressed()) ? 1.0f : 0.0f, 22.0f, dt);
        KEY_PRESS_ANIM[1] = AnimationEngine.expLerp(KEY_PRESS_ANIM[1], (allowInput && client.options.leftKey.isPressed()) ? 1.0f : 0.0f, 22.0f, dt);
        KEY_PRESS_ANIM[2] = AnimationEngine.expLerp(KEY_PRESS_ANIM[2], (allowInput && client.options.backKey.isPressed()) ? 1.0f : 0.0f, 22.0f, dt);
        KEY_PRESS_ANIM[3] = AnimationEngine.expLerp(KEY_PRESS_ANIM[3], (allowInput && client.options.rightKey.isPressed()) ? 1.0f : 0.0f, 22.0f, dt);
        KEY_PRESS_ANIM[4] = AnimationEngine.expLerp(KEY_PRESS_ANIM[4], (allowInput && client.options.attackKey.isPressed()) ? 1.0f : 0.0f, 24.0f, dt);
        KEY_PRESS_ANIM[5] = AnimationEngine.expLerp(KEY_PRESS_ANIM[5], (allowInput && client.options.useKey.isPressed()) ? 1.0f : 0.0f, 24.0f, dt);
        KEY_PRESS_ANIM[6] = AnimationEngine.expLerp(KEY_PRESS_ANIM[6], (allowInput && client.options.jumpKey.isPressed()) ? 1.0f : 0.0f, 22.0f, dt);

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
        int accentBright = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.35f);
        float uiOpacity = ClientSettings.getUiOpacity();

        batch.begin(canvasW, canvasH, uiScale);

        float sKey = KEY_SIZE * hudScale;
        float sGap = KEY_GAP * hudScale;
        float sMouseH = MOUSE_HEIGHT * hudScale;
        float sSpaceH = SPACE_HEIGHT * hudScale;

        float row1Y = smoothY;
        float row2Y = smoothY + sKey + sGap;
        float col1X = smoothX;
        float col2X = smoothX + sKey + sGap;
        float col3X = smoothX + (sKey + sGap) * 2.0f;

        drawKeyCard(batch, blur, blurredTex, blurFade, canvasW, canvasH, col2X, row1Y, sKey, sKey, hudScale,
                "W", null, KEY_PRESS_ANIM[0], accent, accentBright, uiOpacity);
        drawKeyCard(batch, blur, blurredTex, blurFade, canvasW, canvasH, col1X, row2Y, sKey, sKey, hudScale,
                "A", null, KEY_PRESS_ANIM[1], accent, accentBright, uiOpacity);
        drawKeyCard(batch, blur, blurredTex, blurFade, canvasW, canvasH, col2X, row2Y, sKey, sKey, hudScale,
                "S", null, KEY_PRESS_ANIM[2], accent, accentBright, uiOpacity);
        drawKeyCard(batch, blur, blurredTex, blurFade, canvasW, canvasH, col3X, row2Y, sKey, sKey, hudScale,
                "D", null, KEY_PRESS_ANIM[3], accent, accentBright, uiOpacity);

        float nextY = row2Y + sKey + sGap;

        if (showMouse) {
            long now = System.nanoTime();
            float mouseW = (scaledW - sGap) * 0.5f;
            String lmbSub = showCps ? (countRecentClicks(LMB_TIMES, now) + " CPS") : null;
            String rmbSub = showCps ? (countRecentClicks(RMB_TIMES, now) + " CPS") : null;

            drawKeyCard(batch, blur, blurredTex, blurFade, canvasW, canvasH, smoothX, nextY, mouseW, sMouseH, hudScale,
                    "LMB", lmbSub, KEY_PRESS_ANIM[4], accent, accentBright, uiOpacity);
            drawKeyCard(batch, blur, blurredTex, blurFade, canvasW, canvasH, smoothX + mouseW + sGap, nextY, mouseW, sMouseH, hudScale,
                    "RMB", rmbSub, KEY_PRESS_ANIM[5], accent, accentBright, uiOpacity);
            nextY += sMouseH + sGap;
        }

        if (showSpace) {
            drawKeyCard(batch, blur, blurredTex, blurFade, canvasW, canvasH, smoothX, nextY, scaledW, sSpaceH, hudScale,
                    "SPACE", null, KEY_PRESS_ANIM[6], accent, accentBright, uiOpacity);
        }

        batch.end();
    }

    private static void drawKeyCard(RenderBatch2D batch, KawaseBlur blur, int blurredTex, float blurFade,
                                    float canvasW, float canvasH,
                                    float x, float y, float w, float h, float hudScale,
                                    String label, String subLabel, float pressAnim,
                                    int accent, int accentBright, float uiOpacity) {
        float cardRadius = CARD_RADIUS * hudScale;
        if (blurredTex != 0 && blurFade > 0.001f) {
            blur.drawBlurredRect(
                    batch, blurredTex,
                    x, y, w, h,
                    cardRadius, canvasW, canvasH,
                    ColorUtil.argb((int) (245.0f * fadeAlpha * blurFade), 255, 255, 255)
            );
        }

        int idleBg = ColorUtil.argb((int) (220.0f * uiOpacity * fadeAlpha), 13, 15, 18);
        int activeBg = ColorUtil.withAlpha(accent, (0.34f + 0.16f * uiOpacity) * fadeAlpha);
        int bgColor = ColorUtil.lerp(idleBg, activeBg, pressAnim);

        int idleBorder = ColorUtil.withAlpha(0x38FFFFFF, fadeAlpha);
        int activeBorder = ColorUtil.withAlpha(accentBright, 0.90f * fadeAlpha);
        int borderColor = ColorUtil.lerp(idleBorder, activeBorder, pressAnim);
        float borderWidth = (1.0f + 0.35f * pressAnim) * hudScale;

        batch.drawRoundedRectWithOutline(x, y, w, h, cardRadius, bgColor, borderWidth, borderColor);

        int textColor = ColorUtil.lerp(
                ColorUtil.withAlpha(0xFFD1D5DB, fadeAlpha),
                ColorUtil.withAlpha(0xFFFFFFFF, fadeAlpha),
                pressAnim
        );

        if (subLabel == null) {
            float fontSize = ("SPACE".equals(label) ? 10.5f : 12.5f) * hudScale;
            float tw = FontRenderer.getWidth(label, fontSize);
            float th = FontRenderer.getHeight(fontSize);
            FontRenderer.drawText(batch, label, x + (w - tw) * 0.5f, y + (h - th) * 0.5f, fontSize, textColor);
        } else {
            float mainSize = 11.0f * hudScale;
            float subSize = 8.5f * hudScale;
            float mainW = FontRenderer.getWidth(label, mainSize);
            float mainH = FontRenderer.getHeight(mainSize);
            float subW = FontRenderer.getWidth(subLabel, subSize);
            float subH = FontRenderer.getHeight(subSize);
            float gap = 1.5f * hudScale;
            float totalTextH = mainH + gap + subH;
            float startY = y + (h - totalTextH) * 0.5f;

            FontRenderer.drawText(batch, label, x + (w - mainW) * 0.5f, startY, mainSize, textColor);
            int subColor = ColorUtil.lerp(
                    ColorUtil.withAlpha(0xFF9CA3AF, fadeAlpha),
                    ColorUtil.withAlpha(accentBright, fadeAlpha),
                    pressAnim
            );
            FontRenderer.drawText(batch, subLabel, x + (w - subW) * 0.5f, startY + mainH + gap, subSize, subColor);
        }
    }

    private static float computeDeltaTime() {
        long now = System.nanoTime();
        if (lastTimeNs == 0L) {
            lastTimeNs = now;
            return 0.016f;
        }
        float dt = (now - lastTimeNs) * 1e-9f;
        lastTimeNs = now;
        return Math.clamp(dt, 0.0f, 0.05f);
    }
}
