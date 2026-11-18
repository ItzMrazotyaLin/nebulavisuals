package me.itzmr.nebulavisuals.render.engine2d.screen;

import me.itzmr.nebulavisuals.alt.AltManager;
import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.Animation;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.Easing;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine2d.util.IconAtlas;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Минималистичный экран управления оффлайн-аккаунтами Nebula Visuals.
 *
 * <p>Реализован полностью на кастомном 2D-движке ({@link RenderBatch2D}, {@link KawaseBlur},
 * {@code Medeo Light}, {@link IconAtlas}) с поддержкой {@code glScissor}-скроллинга,
 * текстового поля с выделением/буфером обмена ({@code Ctrl+V}) и генератором ников.</p>
 */
public class AltManagerScreen extends NebulaScreen {
    private static final float MAX_WIN_WIDTH = 540.0f;
    private static final float MAX_WIN_HEIGHT = 480.0f;
    private static final float WIN_RADIUS = 18.0f;
    private static final float PAD_X = 22.0f;
    private static final float PAD_TOP = 20.0f;
    private static final float PAD_BOTTOM = 18.0f;

    private static final int BLUR_PASSES = 4;
    private static final float BLUR_OFFSET = 2.4f;
    private static final int MAX_USERNAME_LEN = 16;

    @Nullable
    private final Screen parentScreen;

    private final Animation openAnim = new Animation(0.20f, 0.0f, 1.0f, Easing.EASE_OUT_CUBIC);
    private boolean closing = false;
    private boolean sessionOpened = false;

    // Состояние текстового поля
    private final StringBuilder inputText = new StringBuilder();
    private int cursorIndex = 0;
    private int selectionAnchor = 0;
    private boolean inputFocused = true;
    private boolean draggingTextSelection = false;
    private float caretTimer = 0.0f;
    private long lastClickTimeMs = 0L;
    private int clickStreak = 0;

    // Сообщение статуса/валидации под строкой ввода
    private String statusText = "";
    private boolean statusIsError = false;
    private float statusTimer = 0.0f;

    // Скролл списка сохранённых аккаунтов
    private float scrollOffset = 0.0f;
    private float targetScroll = 0.0f;
    private float maxScroll = 0.0f;

    // Плавный hover для кнопок и карточек
    private float backHover = 0.0f;
    private float randomHover = 0.0f;
    private float loginHover = 0.0f;
    private final Map<String, Float> cardHoverAnim = new HashMap<>();
    private final Map<String, Float> deleteHoverAnim = new HashMap<>();

    public AltManagerScreen(@Nullable Screen parentScreen) {
        super(Text.literal("Nebula Alt Manager"));
        this.parentScreen = parentScreen;
        this.enableBackgroundBlur = false;
    }

    @Override
    protected void onInit() {
        AltManager.init();
        if (!sessionOpened) {
            sessionOpened = true;
            closing = false;
            openAnim.reset(0.0f, 1.0f);
            inputFocused = true;
            caretTimer = 0.0f;
            scrollOffset = 0.0f;
            targetScroll = 0.0f;
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (NebulaRenderEngine.isInitialized()) {
            KawaseBlur blur = NebulaRenderEngine.getBlur();
            if (blur != null && framebufferWidth > 0 && framebufferHeight > 0) {
                blur.ensureCapacity(framebufferWidth, framebufferHeight, BLUR_PASSES);
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    public void startClosing() {
        if (closing) return;
        closing = true;
        draggingTextSelection = false;
        openAnim.reset(openAnim.getValue(), 0.0f);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!NebulaRenderEngine.isInitialized()) {
            NebulaRenderEngine.init();
        }
        updateViewportMetrics();

        float dt = AnimationEngine.updateDelta();
        onTickAnimations(dt);

        float vMouseX = getCursorCanvasX(mouseX);
        float vMouseY = getCursorCanvasY(mouseY);

        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        KawaseBlur blur = NebulaRenderEngine.getBlur();

        float progress = Math.clamp(openAnim.getValue(), 0.0f, 1.0f);
        float blurStrength = ClientSettings.getBlurStrength();
        float blurFade = blurStrength <= 0.01f ? 0.0f : Math.clamp(blurStrength / 0.25f, 0.0f, 1.0f);
        boolean inWorld = client != null && client.world != null;

        int blurredTex = 0;
        if (inWorld && progress > 0.005f && blurFade > 0.001f && client.getFramebuffer() != null
                && framebufferWidth > 0 && framebufferHeight > 0) {
            if (client.getFramebuffer().getColorAttachment() instanceof GlTexture glTexture) {
                int fbTexture = glTexture.getGlId();
                if (fbTexture > 0) {
                    float deepOff = Math.clamp(BLUR_OFFSET * blurStrength, 0.35f, 4.5f);
                    blurredTex = blur.renderBlur(fbTexture, framebufferWidth, framebufferHeight, BLUR_PASSES, deepOff);
                }
            }
        }

        batch.begin(canvasWidth, canvasHeight, uiScale);

        if (inWorld) {
            if (blurredTex != 0 && blurFade > 0.001f) {
                batch.drawTexturedRect(
                        blurredTex,
                        0.0f, 0.0f, canvasWidth, canvasHeight,
                        0.0f, 1.0f, 1.0f, 0.0f,
                        ColorUtil.argb((int) (130.0f * progress * blurFade), 255, 255, 255)
                );
            }
            batch.drawRect(0.0f, 0.0f, canvasWidth, canvasHeight,
                    ColorUtil.argb((int) (75.0f * progress), 6, 8, 12));
        } else {
            // На TitleScreen игровой мир не загружен: рисуем глубокий минималистичный градиент без артефактов буфера
            renderMenuBackdrop(batch);
        }

        if (progress > 0.005f) {
            float scale = 0.95f + 0.05f * progress;
            renderWindow(batch, blur, blurredTex, blurFade, vMouseX, vMouseY, scale, progress, dt);
        }

        batch.end();
    }

    private void renderMenuBackdrop(RenderBatch2D batch) {
        int accent = ClientSettings.getAccentColor();
        int topLeft = 0xFF07090E;
        int topRight = 0xFF0A0D16;
        int bottomLeft = ColorUtil.lerp(0xFF090C14, accent, 0.05f);
        int bottomRight = 0xFF0D111B;
        batch.drawGradientRoundedRect(0.0f, 0.0f, canvasWidth, canvasHeight, 0.0f, topLeft, topRight, bottomRight, bottomLeft);
        batch.drawShadow(
                canvasWidth * 0.5f - 280.0f,
                canvasHeight * 0.5f - 240.0f,
                560.0f,
                480.0f,
                24.0f,
                90.0f,
                ColorUtil.withAlpha(accent, 0.08f)
        );
    }

    @Override
    protected void onRender(RenderBatch2D batch, float mouseX, float mouseY, float dt) {
        // Отрисовка выполняется в render(...) для координации с KawaseBlur.
    }

    @Override
    protected void onTickAnimations(float dt) {
        openAnim.update(dt);
        scrollOffset = AnimationEngine.expLerp(scrollOffset, targetScroll, 14.0f, dt);
        caretTimer += dt;
        if (statusTimer > 0.0f) {
            statusTimer = Math.max(0.0f, statusTimer - dt);
        }

        if (closing && (openAnim.isFinished() || openAnim.getValue() <= 0.005f)) {
            closing = false;
            sessionOpened = false;
            if (client != null) {
                client.setScreen(parentScreen);
            }
        }
    }

    private void renderWindow(RenderBatch2D batch, KawaseBlur blur, int blurredTex, float blurFade,
                              float mouseX, float mouseY, float scale, float alpha, float dt) {
        Layout L = computeLayout();
        float cx = canvasWidth * 0.5f;
        float cy = canvasHeight * 0.5f;
        float mx = cx + (mouseX - cx) / scale;
        float my = cy + (mouseY - cy) / scale;

        if (draggingTextSelection) {
            updateCaretFromMouse(L, mx);
        }

        float sx = scaleX(L.winX, cx, scale);
        float sy = scaleY(L.winY, cy, scale);
        float sw = L.winW * scale;
        float sh = L.winH * scale;
        float uiOpacity = ClientSettings.getUiOpacity();
        int accent = ClientSettings.getAccentColor();

        // Тень и матовое стекло
        batch.drawShadow(sx, sy, sw, sh, WIN_RADIUS * scale, 28.0f * scale, ColorUtil.argb((int) (140 * alpha), 0, 0, 0));
        if (blurredTex != 0 && blurFade > 0.001f) {
            blur.drawBlurredRect(
                    batch, blurredTex,
                    sx, sy, sw, sh, WIN_RADIUS * scale,
                    canvasWidth, canvasHeight,
                    ColorUtil.argb((int) (255.0f * alpha * blurFade), 255, 255, 255)
            );
        }

        batch.drawGradientRoundedRect(
                sx, sy, sw, sh, WIN_RADIUS * scale,
                ColorUtil.argb((int) (212.0f * uiOpacity * alpha), 15, 18, 24),
                ColorUtil.argb((int) (216.0f * uiOpacity * alpha), 13, 15, 20),
                ColorUtil.argb((int) (224.0f * uiOpacity * alpha), 11, 13, 17),
                ColorUtil.argb((int) (216.0f * uiOpacity * alpha), 13, 15, 20)
        );
        batch.drawOutlineRoundedRect(sx, sy, sw, sh, WIN_RADIUS * scale, 1.0f * scale,
                ColorUtil.argb((int) (140 * alpha), 58, 62, 74));

        // 1. Заголовок и кнопка Back
        FontRenderer.drawText(
                batch, "Alt Manager",
                scaleX(L.contentX, cx, scale),
                scaleY(L.headerY + 2.0f, cy, scale),
                20.0f * scale,
                ColorUtil.argb((int) (255 * alpha), 255, 255, 255)
        );

        boolean overBack = isInside(mx, my, L.backX, L.backY, L.backW, L.backH);
        backHover = AnimationEngine.expLerp(backHover, overBack ? 1.0f : 0.0f, 16.0f, dt);
        int backBg = ColorUtil.lerp(
                ColorUtil.argb((int) (160.0f * uiOpacity * alpha), 24, 28, 36),
                ColorUtil.argb((int) (210.0f * uiOpacity * alpha), 36, 42, 54),
                backHover
        );
        int backBorder = ColorUtil.lerp(
                ColorUtil.argb((int) (120 * alpha), 55, 62, 78),
                ColorUtil.withAlpha(accent, 0.75f * alpha),
                backHover
        );
        batch.drawRoundedRectWithOutline(
                scaleX(L.backX, cx, scale), scaleY(L.backY, cy, scale),
                L.backW * scale, L.backH * scale, 8.0f * scale,
                backBg, 1.0f * scale, backBorder
        );
        String backLabel = "Back";
        float backFont = 12.5f;
        float backTextX = L.backX + (L.backW - FontRenderer.getWidth(backLabel, backFont)) * 0.5f;
        float backTextY = L.backY + (L.backH - FontRenderer.getHeight(backFont)) * 0.5f;
        FontRenderer.drawText(
                batch, backLabel,
                scaleX(backTextX, cx, scale), scaleY(backTextY, cy, scale),
                backFont * scale,
                ColorUtil.withAlpha(overBack ? 0xFFFFFFFF : 0xFFE5E7EB, alpha)
        );

        // 2. Верхняя панель статуса: Current: <username> с зелёным индикатором
        batch.drawRoundedRectWithOutline(
                scaleX(L.contentX, cx, scale), scaleY(L.statusY, cy, scale),
                L.contentW * scale, L.statusH * scale, 10.0f * scale,
                ColorUtil.argb((int) (165.0f * uiOpacity * alpha), 18, 21, 28),
                1.0f * scale,
                ColorUtil.argb((int) (105 * alpha), 44, 50, 62)
        );

        float dotSize = 8.0f;
        batch.drawRoundedRect(
                scaleX(L.contentX + 12.0f, cx, scale),
                scaleY(L.statusY + (L.statusH - dotSize) * 0.5f, cy, scale),
                dotSize * scale, dotSize * scale, 4.0f * scale,
                ColorUtil.withAlpha(0xFF10B981, alpha)
        );

        String currentUser = AltManager.getCurrentUsername();
        String prefix = "Current: ";
        float curFont = 13.0f;
        float prefixW = FontRenderer.getWidth(prefix, curFont);
        float curTextY = L.statusY + (L.statusH - FontRenderer.getHeight(curFont)) * 0.5f;
        FontRenderer.drawText(
                batch, prefix,
                scaleX(L.contentX + 27.0f, cx, scale), scaleY(curTextY, cy, scale),
                curFont * scale,
                ColorUtil.argb((int) (185 * alpha), 156, 163, 175)
        );
        FontRenderer.drawText(
                batch, currentUser,
                scaleX(L.contentX + 27.0f + prefixW, cx, scale), scaleY(curTextY, cy, scale),
                curFont * scale,
                ColorUtil.argb((int) (255 * alpha), 255, 255, 255)
        );

        List<String> accounts = AltManager.getAccounts();
        String countText = accounts.size() + (accounts.size() == 1 ? " account" : " accounts");
        float countFont = 11.5f;
        float countW = FontRenderer.getWidth(countText, countFont);
        float countY = L.statusY + (L.statusH - FontRenderer.getHeight(countFont)) * 0.5f;
        FontRenderer.drawText(
                batch, countText,
                scaleX(L.contentX + L.contentW - 12.0f - countW, cx, scale), scaleY(countY, cy, scale),
                countFont * scale,
                ColorUtil.argb((int) (160 * alpha), 156, 163, 175)
        );

        // 3. Блок быстрого входа / добавления: TextField + Random + Login
        renderInputRow(batch, L, cx, cy, scale, alpha, uiOpacity, accent, mx, my, dt);

        // Подсказка / ошибка валидации
        if (statusTimer > 0.0f && !statusText.isEmpty()) {
            float feedbackFade = Math.min(1.0f, statusTimer / 0.35f) * alpha;
            int msgColor = statusIsError
                    ? ColorUtil.withAlpha(0xFFF87171, feedbackFade)
                    : ColorUtil.withAlpha(0xFF34D399, feedbackFade);
            FontRenderer.drawText(
                    batch, statusText,
                    scaleX(L.contentX + 2.0f, cx, scale),
                    scaleY(L.inputY + L.inputH + 5.0f, cy, scale),
                    11.0f * scale,
                    msgColor
            );
        }

        // 4. Скроллируемый список сохранённых аккаунтов с glScissor
        renderAccountList(batch, L, accounts, currentUser, cx, cy, scale, alpha, uiOpacity, accent, mx, my, dt);
    }

    private void renderInputRow(RenderBatch2D batch, Layout L,
                                float cx, float cy, float scale, float alpha, float uiOpacity, int accent,
                                float mx, float my, float dt) {
        boolean errorFlash = statusTimer > 0.0f && statusIsError;
        int fieldBorder = errorFlash
                ? ColorUtil.withAlpha(0xFFEF4444, 0.85f * alpha)
                : (inputFocused
                        ? ColorUtil.withAlpha(accent, 0.85f * alpha)
                        : ColorUtil.argb((int) (115 * alpha), 46, 52, 66));

        batch.drawRoundedRectWithOutline(
                scaleX(L.fieldX, cx, scale), scaleY(L.inputY, cy, scale),
                L.fieldW * scale, L.inputH * scale, 9.0f * scale,
                ColorUtil.argb((int) (190.0f * uiOpacity * alpha), 11, 13, 18),
                1.1f * scale, fieldBorder
        );

        float textSize = 13.0f;
        float textPadX = 11.0f;
        float clipX = L.fieldX + textPadX;
        float clipW = Math.max(20.0f, L.fieldW - textPadX * 2.0f);
        float textY = L.inputY + (L.inputH - FontRenderer.getHeight(textSize)) * 0.5f;

        String raw = inputText.toString();
        float cursorOffsetPx = FontRenderer.getWidth(raw.substring(0, Math.min(cursorIndex, raw.length())), textSize);
        float scrollTextX = Math.max(0.0f, cursorOffsetPx - (clipW - 6.0f));
        float drawTextX = clipX - scrollTextX;

        batch.beginScissor(
                scaleX(clipX - 2.0f, cx, scale),
                scaleY(L.inputY + 3.0f, cy, scale),
                (clipW + 4.0f) * scale,
                (L.inputH - 6.0f) * scale,
                canvasWidth, canvasHeight
        );

        if (raw.isEmpty()) {
            FontRenderer.drawText(
                    batch, "Enter username (3-16)...",
                    scaleX(clipX, cx, scale), scaleY(textY, cy, scale),
                    textSize * scale,
                    ColorUtil.argb((int) (125 * alpha), 156, 163, 175)
            );
        } else {
            if (inputFocused && hasSelection()) {
                int sStart = getSelectionStart();
                int sEnd = getSelectionEnd();
                float sx0 = drawTextX + FontRenderer.getWidth(raw.substring(0, sStart), textSize);
                float sx1 = drawTextX + FontRenderer.getWidth(raw.substring(0, sEnd), textSize);
                batch.drawRoundedRect(
                        scaleX(sx0, cx, scale),
                        scaleY(L.inputY + 7.0f, cy, scale),
                        Math.max(2.0f, sx1 - sx0) * scale,
                        (L.inputH - 14.0f) * scale,
                        3.0f * scale,
                        ColorUtil.withAlpha(accent, 0.35f * alpha)
                );
            }

            FontRenderer.drawText(
                    batch, raw,
                    scaleX(drawTextX, cx, scale), scaleY(textY, cy, scale),
                    textSize * scale,
                    ColorUtil.argb((int) (255 * alpha), 243, 244, 246)
            );
        }

        if (inputFocused && (caretTimer % 1.0f) < 0.55f) {
            float caretX = drawTextX + cursorOffsetPx;
            batch.drawRect(
                    scaleX(caretX, cx, scale),
                    scaleY(L.inputY + 8.0f, cy, scale),
                    1.3f * scale,
                    (L.inputH - 16.0f) * scale,
                    ColorUtil.withAlpha(accent, 0.95f * alpha)
            );
        }

        batch.endScissor();

        // Кнопка Random
        boolean overRandom = isInside(mx, my, L.randomX, L.inputY, L.randomW, L.inputH);
        randomHover = AnimationEngine.expLerp(randomHover, overRandom ? 1.0f : 0.0f, 16.0f, dt);
        int rndBg = ColorUtil.lerp(
                ColorUtil.argb((int) (175.0f * uiOpacity * alpha), 24, 28, 36),
                ColorUtil.argb((int) (220.0f * uiOpacity * alpha), 36, 42, 54),
                randomHover
        );
        int rndBorder = ColorUtil.lerp(
                ColorUtil.argb((int) (115 * alpha), 54, 60, 74),
                ColorUtil.withAlpha(accent, 0.70f * alpha),
                randomHover
        );
        batch.drawRoundedRectWithOutline(
                scaleX(L.randomX, cx, scale), scaleY(L.inputY, cy, scale),
                L.randomW * scale, L.inputH * scale, 9.0f * scale,
                rndBg, 1.0f * scale, rndBorder
        );
        float btnFont = 12.5f;
        String rndLabel = "Random";
        float rndTextX = L.randomX + (L.randomW - FontRenderer.getWidth(rndLabel, btnFont)) * 0.5f;
        float btnTextY = L.inputY + (L.inputH - FontRenderer.getHeight(btnFont)) * 0.5f;
        FontRenderer.drawText(
                batch, rndLabel,
                scaleX(rndTextX, cx, scale), scaleY(btnTextY, cy, scale),
                btnFont * scale,
                ColorUtil.withAlpha(overRandom ? 0xFFFFFFFF : 0xFFD1D5DB, alpha)
        );

        // Кнопка Login / Add
        boolean overLogin = isInside(mx, my, L.loginX, L.inputY, L.loginW, L.inputH);
        loginHover = AnimationEngine.expLerp(loginHover, overLogin ? 1.0f : 0.0f, 16.0f, dt);
        int loginBg = ColorUtil.lerp(
                ColorUtil.withAlpha(accent, 0.85f * alpha),
                ColorUtil.withAlpha(ClientSettings.getAccentHoverColor(), 0.96f * alpha),
                loginHover
        );
        batch.drawRoundedRect(
                scaleX(L.loginX, cx, scale), scaleY(L.inputY, cy, scale),
                L.loginW * scale, L.inputH * scale, 9.0f * scale,
                loginBg
        );
        String loginLabel = "Login";
        float loginTextX = L.loginX + (L.loginW - FontRenderer.getWidth(loginLabel, btnFont)) * 0.5f;
        FontRenderer.drawText(
                batch, loginLabel,
                scaleX(loginTextX, cx, scale), scaleY(btnTextY, cy, scale),
                btnFont * scale,
                ColorUtil.argb((int) (255 * alpha), 255, 255, 255)
        );
    }

    private void renderAccountList(RenderBatch2D batch, Layout L, List<String> accounts, String currentUser,
                                   float cx, float cy, float scale, float alpha, float uiOpacity, int accent,
                                   float mx, float my, float dt) {
        // Контейнер списка аккаунтов
        batch.drawRoundedRectWithOutline(
                scaleX(L.listX, cx, scale), scaleY(L.listY, cy, scale),
                L.listW * scale, L.listH * scale, 12.0f * scale,
                ColorUtil.argb((int) (140.0f * uiOpacity * alpha), 10, 12, 16),
                1.0f * scale,
                ColorUtil.argb((int) (95 * alpha), 38, 43, 54)
        );

        float innerPad = 8.0f;
        float cardW = L.listW - innerPad * 2.0f;
        float cardH = 42.0f;
        float cardGap = 6.0f;
        float totalH = accounts.isEmpty() ? 0.0f : accounts.size() * cardH + (accounts.size() - 1) * cardGap;
        float viewportInnerH = L.listH - innerPad * 2.0f;

        maxScroll = Math.max(0.0f, totalH - viewportInnerH);
        targetScroll = Math.clamp(targetScroll, -maxScroll, 0.0f);
        scrollOffset = Math.clamp(scrollOffset, -maxScroll, 0.0f);

        batch.beginScissor(
                scaleX(L.listX + 2.0f, cx, scale),
                scaleY(L.listY + 2.0f, cy, scale),
                (L.listW - 4.0f) * scale,
                (L.listH - 4.0f) * scale,
                canvasWidth, canvasHeight
        );

        if (accounts.isEmpty()) {
            String emptyTitle = "No saved accounts yet";
            String emptySub = "Enter a username above or click Random";
            float tW = FontRenderer.getWidth(emptyTitle, 13.5f);
            float sW = FontRenderer.getWidth(emptySub, 11.5f);
            float midY = L.listY + L.listH * 0.5f;
            FontRenderer.drawText(
                    batch, emptyTitle,
                    scaleX(L.listX + (L.listW - tW) * 0.5f, cx, scale),
                    scaleY(midY - 16.0f, cy, scale),
                    13.5f * scale,
                    ColorUtil.argb((int) (200 * alpha), 209, 213, 219)
            );
            FontRenderer.drawText(
                    batch, emptySub,
                    scaleX(L.listX + (L.listW - sW) * 0.5f, cx, scale),
                    scaleY(midY + 4.0f, cy, scale),
                    11.5f * scale,
                    ColorUtil.argb((int) (145 * alpha), 156, 163, 175)
            );
        } else {
            boolean mouseInList = isInside(mx, my, L.listX, L.listY, L.listW, L.listH);
            float cardX = L.listX + innerPad;
            float curY = L.listY + innerPad + scrollOffset;

            for (String acc : accounts) {
                if (curY + cardH >= L.listY - 4.0f && curY <= L.listY + L.listH + 4.0f) {
                    boolean active = acc.equals(currentUser);
                    float delSize = 24.0f;
                    float delX = cardX + cardW - 10.0f - delSize;
                    float delY = curY + (cardH - delSize) * 0.5f;

                    boolean overDelete = mouseInList && isInside(mx, my, delX, delY, delSize, delSize);
                    boolean overCard = mouseInList && isInside(mx, my, cardX, curY, cardW, cardH) && !overDelete;

                    float hVal = AnimationEngine.expLerp(cardHoverAnim.getOrDefault(acc, 0.0f), overCard ? 1.0f : 0.0f, 16.0f, dt);
                    cardHoverAnim.put(acc, hVal);
                    float dVal = AnimationEngine.expLerp(deleteHoverAnim.getOrDefault(acc, 0.0f), overDelete ? 1.0f : 0.0f, 16.0f, dt);
                    deleteHoverAnim.put(acc, dVal);

                    int bg = active
                            ? ColorUtil.withAlpha(accent, (0.14f + 0.05f * hVal) * alpha)
                            : ColorUtil.lerp(
                                    ColorUtil.argb((int) (145.0f * uiOpacity * alpha), 18, 21, 28),
                                    ColorUtil.argb((int) (195.0f * uiOpacity * alpha), 27, 31, 42),
                                    hVal
                            );
                    int border = active
                            ? ColorUtil.withAlpha(accent, 0.90f * alpha)
                            : ColorUtil.lerp(
                                    ColorUtil.argb((int) (85 * alpha), 44, 50, 62),
                                    ColorUtil.withAlpha(accent, 0.50f * alpha),
                                    hVal
                            );

                    batch.drawRoundedRectWithOutline(
                            scaleX(cardX, cx, scale), scaleY(curY, cy, scale),
                            cardW * scale, cardH * scale, 10.0f * scale,
                            bg, (active ? 1.3f : 1.0f) * scale, border
                    );

                    // Левый индикатор
                    int dotCol = active
                            ? ColorUtil.withAlpha(accent, alpha)
                            : ColorUtil.argb((int) (150 * alpha), 75, 85, 99);
                    batch.drawRoundedRect(
                            scaleX(cardX + 12.0f, cx, scale),
                            scaleY(curY + (cardH - 7.0f) * 0.5f, cy, scale),
                            7.0f * scale, 7.0f * scale, 3.5f * scale, dotCol
                    );

                    // Никнейм
                    float nameFont = 13.5f;
                    float nameY = curY + (cardH - FontRenderer.getHeight(nameFont)) * 0.5f;
                    FontRenderer.drawText(
                            batch, acc,
                            scaleX(cardX + 27.0f, cx, scale), scaleY(nameY, cy, scale),
                            nameFont * scale,
                            ColorUtil.withAlpha(active || overCard ? 0xFFFFFFFF : 0xFFE5E7EB, alpha)
                    );

                    // Бейдж статуса слева от кнопки удаления
                    if (active) {
                        String badge = "Active";
                        float badgeFont = 11.0f;
                        float badgeW = FontRenderer.getWidth(badge, badgeFont);
                        float badgeY = curY + (cardH - FontRenderer.getHeight(badgeFont)) * 0.5f;
                        float badgeX = delX - 12.0f - badgeW;
                        IconAtlas.drawIcon(
                                batch, IconAtlas.Icon.CHECK,
                                scaleX(badgeX - 10.0f, cx, scale),
                                scaleY(curY + cardH * 0.5f, cy, scale),
                                12.0f * scale, 0.0f, ColorUtil.withAlpha(accent, alpha)
                        );
                        FontRenderer.drawText(
                                batch, badge,
                                scaleX(badgeX, cx, scale), scaleY(badgeY, cy, scale),
                                badgeFont * scale, ColorUtil.withAlpha(accent, alpha)
                        );
                    } else if (hVal > 0.05f) {
                        String playHint = "Play";
                        float playFont = 11.0f;
                        float playW = FontRenderer.getWidth(playHint, playFont);
                        float playY = curY + (cardH - FontRenderer.getHeight(playFont)) * 0.5f;
                        FontRenderer.drawText(
                                batch, playHint,
                                scaleX(delX - 12.0f - playW, cx, scale), scaleY(playY, cy, scale),
                                playFont * scale,
                                ColorUtil.argb((int) (185.0f * hVal * alpha), 209, 213, 219)
                        );
                    }

                    // Кнопка удаления (иконка CLOSE из icons.json)
                    if (dVal > 0.02f) {
                        batch.drawRoundedRect(
                                scaleX(delX, cx, scale), scaleY(delY, cy, scale),
                                delSize * scale, delSize * scale, 6.0f * scale,
                                ColorUtil.argb((int) (45.0f * dVal * alpha), 239, 68, 68)
                        );
                    }
                    int delIconCol = ColorUtil.lerp(
                            ColorUtil.argb((int) (150 * alpha), 156, 163, 175),
                            ColorUtil.withAlpha(0xFFEF4444, alpha),
                            dVal
                    );
                    IconAtlas.drawIcon(
                            batch, IconAtlas.Icon.CLOSE,
                            scaleX(delX + delSize * 0.5f, cx, scale),
                            scaleY(delY + delSize * 0.5f, cy, scale),
                            12.0f * scale, 0.0f, delIconCol
                    );
                }

                curY += cardH + cardGap;
            }
        }

        batch.endScissor();

        if (maxScroll > 0.0f) {
            float barH = Math.max(22.0f, viewportInnerH * (viewportInnerH / totalH));
            float barY = L.listY + innerPad + (-scrollOffset / maxScroll) * (viewportInnerH - barH);
            batch.drawRoundedRect(
                    scaleX(L.listX + L.listW - 4.5f, cx, scale),
                    scaleY(barY, cy, scale),
                    2.5f * scale,
                    barH * scale,
                    1.25f * scale,
                    ColorUtil.withAlpha(accent, 0.55f * alpha)
            );
        }
    }

    // ==================== ВВОД МЫШИ И КЛАВИАТУРЫ ====================

    @Override
    protected boolean onMouseClicked(float mouseX, float mouseY, int button) {
        if (closing) return true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;

        Layout L = computeLayout();
        if (!isInside(mouseX, mouseY, L.winX, L.winY, L.winW, L.winH)) {
            inputFocused = false;
            return false;
        }

        if (isInside(mouseX, mouseY, L.backX, L.backY, L.backW, L.backH)) {
            startClosing();
            return true;
        }

        if (isInside(mouseX, mouseY, L.fieldX, L.inputY, L.fieldW, L.inputH)) {
            inputFocused = true;
            caretTimer = 0.0f;

            long now = System.currentTimeMillis();
            if (now - lastClickTimeMs < 350L) {
                clickStreak++;
            } else {
                clickStreak = 1;
            }
            lastClickTimeMs = now;

            if (clickStreak >= 2) {
                selectionAnchor = 0;
                cursorIndex = inputText.length();
                draggingTextSelection = false;
            } else {
                int idx = findCharIndexAtMouse(L, mouseX);
                cursorIndex = idx;
                selectionAnchor = idx;
                draggingTextSelection = true;
            }
            return true;
        }

        inputFocused = false;

        if (isInside(mouseX, mouseY, L.randomX, L.inputY, L.randomW, L.inputH)) {
            String generated = AltManager.generateRandomPvpName();
            inputText.setLength(0);
            inputText.append(generated);
            cursorIndex = inputText.length();
            selectionAnchor = 0;
            inputFocused = true;
            caretTimer = 0.0f;
            setStatus("Generated: " + generated, false);
            return true;
        }

        if (isInside(mouseX, mouseY, L.loginX, L.inputY, L.loginW, L.inputH)) {
            submitLogin();
            return true;
        }

        if (isInside(mouseX, mouseY, L.listX, L.listY, L.listW, L.listH)) {
            List<String> accounts = AltManager.getAccounts();
            float innerPad = 8.0f;
            float cardX = L.listX + innerPad;
            float cardW = L.listW - innerPad * 2.0f;
            float cardH = 42.0f;
            float cardGap = 6.0f;
            float curY = L.listY + innerPad + scrollOffset;

            for (String acc : accounts) {
                if (isInside(mouseX, mouseY, cardX, curY, cardW, cardH)) {
                    float delSize = 24.0f;
                    float delX = cardX + cardW - 10.0f - delSize;
                    float delY = curY + (cardH - delSize) * 0.5f;

                    if (isInside(mouseX, mouseY, delX, delY, delSize, delSize)) {
                        AltManager.removeAccount(acc);
                        setStatus("Removed " + acc, false);
                    } else {
                        if (AltManager.loginOffline(acc)) {
                            setStatus("Switched to " + acc, false);
                        }
                    }
                    return true;
                }
                curY += cardH + cardGap;
            }
        }

        return true;
    }

    @Override
    protected boolean onMouseReleased(float mouseX, float mouseY, int button) {
        if (draggingTextSelection && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            draggingTextSelection = false;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onMouseScrolled(float mouseX, float mouseY, double horizontalAmount, double verticalAmount) {
        if (closing || maxScroll <= 0.0f) return false;
        Layout L = computeLayout();
        if (!isInside(mouseX, mouseY, L.listX, L.listY, L.listW, L.listH)) return false;

        targetScroll += (float) verticalAmount * 36.0f;
        targetScroll = Math.clamp(targetScroll, -maxScroll, 0.0f);
        return true;
    }

    @Override
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            startClosing();
            return true;
        }

        if (!inputFocused) {
            return false;
        }

        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submitLogin();
            return true;
        }

        if (ctrl) {
            if (keyCode == GLFW.GLFW_KEY_A) {
                selectionAnchor = 0;
                cursorIndex = inputText.length();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_C) {
                copySelectionToClipboard();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_X) {
                copySelectionToClipboard();
                deleteSelection();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                pasteFromClipboard();
                return true;
            }
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hasSelection()) {
                    deleteSelection();
                } else if (cursorIndex > 0) {
                    if (ctrl) {
                        inputText.delete(0, cursorIndex);
                        cursorIndex = 0;
                        selectionAnchor = 0;
                    } else {
                        inputText.deleteCharAt(cursorIndex - 1);
                        cursorIndex--;
                        selectionAnchor = cursorIndex;
                    }
                }
                caretTimer = 0.0f;
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (hasSelection()) {
                    deleteSelection();
                } else if (cursorIndex < inputText.length()) {
                    inputText.deleteCharAt(cursorIndex);
                }
                caretTimer = 0.0f;
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (hasSelection() && !shift) {
                    cursorIndex = getSelectionStart();
                    selectionAnchor = cursorIndex;
                } else {
                    cursorIndex = Math.max(0, cursorIndex - 1);
                    if (!shift) selectionAnchor = cursorIndex;
                }
                caretTimer = 0.0f;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (hasSelection() && !shift) {
                    cursorIndex = getSelectionEnd();
                    selectionAnchor = cursorIndex;
                } else {
                    cursorIndex = Math.min(inputText.length(), cursorIndex + 1);
                    if (!shift) selectionAnchor = cursorIndex;
                }
                caretTimer = 0.0f;
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursorIndex = 0;
                if (!shift) selectionAnchor = cursorIndex;
                caretTimer = 0.0f;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursorIndex = inputText.length();
                if (!shift) selectionAnchor = cursorIndex;
                caretTimer = 0.0f;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    protected boolean onCharTyped(char chr, int modifiers) {
        if (!inputFocused || closing) return false;
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) return false;

        if (!isAllowedUsernameChar(chr)) {
            setStatus("Allowed characters: A-Z, 0-9, _", true);
            return true;
        }

        insertCleanText(String.valueOf(chr));
        return true;
    }

    private void submitLogin() {
        String candidate = inputText.toString().trim();
        if (!AltManager.isValidUsername(candidate)) {
            setStatus("Username must be 3-16 chars [a-zA-Z0-9_]", true);
            inputFocused = true;
            return;
        }

        if (AltManager.loginOffline(candidate)) {
            setStatus("Logged in as " + candidate, false);
            inputText.setLength(0);
            cursorIndex = 0;
            selectionAnchor = 0;
        } else {
            setStatus("Failed to apply offline session", true);
        }
    }

    private void setStatus(String message, boolean error) {
        this.statusText = message;
        this.statusIsError = error;
        this.statusTimer = 2.4f;
    }

    // ==================== УТИЛИТЫ ТЕКСТОВОГО ПОЛЯ ====================

    private boolean hasSelection() {
        return cursorIndex != selectionAnchor;
    }

    private int getSelectionStart() {
        return Math.min(cursorIndex, selectionAnchor);
    }

    private int getSelectionEnd() {
        return Math.max(cursorIndex, selectionAnchor);
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int start = getSelectionStart();
        int end = getSelectionEnd();
        inputText.delete(start, end);
        cursorIndex = start;
        selectionAnchor = start;
    }

    private void copySelectionToClipboard() {
        if (!hasSelection() || client == null || client.keyboard == null) return;
        client.keyboard.setClipboard(inputText.substring(getSelectionStart(), getSelectionEnd()));
    }

    private void pasteFromClipboard() {
        if (client == null || client.keyboard == null) return;
        String clip = client.keyboard.getClipboard();
        if (clip == null || clip.isEmpty()) return;
        insertCleanText(clip);
    }

    private void insertCleanText(String rawInsert) {
        StringBuilder filtered = new StringBuilder();
        for (int i = 0; i < rawInsert.length(); i++) {
            char c = rawInsert.charAt(i);
            if (isAllowedUsernameChar(c)) {
                filtered.append(c);
            }
        }
        if (filtered.isEmpty()) {
            setStatus("Allowed characters: A-Z, 0-9, _", true);
            return;
        }

        if (hasSelection()) {
            deleteSelection();
        }

        int remaining = MAX_USERNAME_LEN - inputText.length();
        if (remaining <= 0) {
            setStatus("Max length is 16 characters", true);
            return;
        }

        String toInsert = filtered.length() > remaining ? filtered.substring(0, remaining) : filtered.toString();
        inputText.insert(cursorIndex, toInsert);
        cursorIndex += toInsert.length();
        selectionAnchor = cursorIndex;
        caretTimer = 0.0f;
    }

    private static boolean isAllowedUsernameChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }

    private int findCharIndexAtMouse(Layout L, float mouseX) {
        float textSize = 13.0f;
        float clipX = L.fieldX + 11.0f;
        float clipW = Math.max(20.0f, L.fieldW - 22.0f);
        String raw = inputText.toString();
        float cursorOffsetPx = FontRenderer.getWidth(raw.substring(0, Math.min(cursorIndex, raw.length())), textSize);
        float scrollTextX = Math.max(0.0f, cursorOffsetPx - (clipW - 6.0f));
        float relX = mouseX - (clipX - scrollTextX);

        if (relX <= 0.0f || raw.isEmpty()) return 0;

        float prevW = 0.0f;
        for (int i = 1; i <= raw.length(); i++) {
            float w = FontRenderer.getWidth(raw.substring(0, i), textSize);
            if (relX < (prevW + w) * 0.5f) {
                return i - 1;
            }
            prevW = w;
        }
        return raw.length();
    }

    private void updateCaretFromMouse(Layout L, float mouseX) {
        cursorIndex = findCharIndexAtMouse(L, mouseX);
        caretTimer = 0.0f;
    }

    // ==================== ГЕОМЕТРИЯ ЭКРАНА ====================

    private record Layout(
            float winX, float winY, float winW, float winH,
            float contentX, float contentW,
            float headerY,
            float backX, float backY, float backW, float backH,
            float statusY, float statusH,
            float inputY, float inputH,
            float fieldX, float fieldW,
            float randomX, float randomW,
            float loginX, float loginW,
            float listX, float listY, float listW, float listH
    ) {}

    private Layout computeLayout() {
        float pad = canvasWidth < 400.0f ? 20.0f : 40.0f;
        float winW = Math.min(MAX_WIN_WIDTH, Math.max(280.0f, canvasWidth - pad));
        float winH = Math.min(MAX_WIN_HEIGHT, Math.max(260.0f, canvasHeight - pad));
        float winX = (canvasWidth - winW) * 0.5f;
        float winY = (canvasHeight - winH) * 0.5f;

        float contentX = winX + PAD_X;
        float contentW = winW - PAD_X * 2.0f;

        float headerY = winY + PAD_TOP;
        float backW = 72.0f;
        float backH = 28.0f;
        float backX = contentX + contentW - backW;
        float backY = headerY;

        float statusY = headerY + 38.0f;
        float statusH = 36.0f;

        float inputY = statusY + statusH + 12.0f;
        float inputH = 36.0f;
        float loginW = 76.0f;
        float randomW = 72.0f;
        float btnGap = 8.0f;

        float loginX = contentX + contentW - loginW;
        float randomX = loginX - btnGap - randomW;
        float fieldX = contentX;
        float fieldW = Math.max(90.0f, randomX - btnGap - fieldX);

        float listY = inputY + inputH + 24.0f;
        float listH = Math.max(80.0f, (winY + winH - PAD_BOTTOM) - listY);

        return new Layout(
                winX, winY, winW, winH,
                contentX, contentW,
                headerY,
                backX, backY, backW, backH,
                statusY, statusH,
                inputY, inputH,
                fieldX, fieldW,
                randomX, randomW,
                loginX, loginW,
                contentX, listY, contentW, listH
        );
    }

    private boolean isInside(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private float scaleX(float x, float cx, float scale) {
        return cx + (x - cx) * scale;
    }

    private float scaleY(float y, float cy, float scale) {
        return cy + (y - cy) * scale;
    }
}
