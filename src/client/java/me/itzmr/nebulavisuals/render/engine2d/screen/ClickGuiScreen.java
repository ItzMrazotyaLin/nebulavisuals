package me.itzmr.nebulavisuals.render.engine2d.screen;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.config.ConfigManager;
import me.itzmr.nebulavisuals.config.LocalizationManager;
import me.itzmr.nebulavisuals.module.*;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.Animation;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.Easing;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.font.MsdfFont;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine2d.util.IconAtlas;
import me.itzmr.nebulavisuals.render.engine2d.util.Texture2D;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClickGUI экран Nebula Visuals в стиле Figma (Glassmorphism, Medeo Light, Flat Dark + Neon Violet #8B5CF6).
 *
 * Особенности:
 * - Рендеринг строго через кастомный 2D-движок (RenderBatch2D, KawaseBlur, MsdfFont, Texture2D).
 * - Защита от дёрганья блюра: фиксированное число проходов KawaseBlur, отсутствие пересоздания FBO при открытии,
 *   единая плавная анимация (0.24s, EASE_OUT_CUBIC) без сброса прогресса при resize/re-init.
 */
public class ClickGuiScreen extends NebulaScreen {
    private static ClickGuiScreen instance;

    public static ClickGuiScreen getInstance() {
        if (instance == null) {
            instance = new ClickGuiScreen();
        }
        return instance;
    }

    private enum UiTab {
        VISUALS("Visuals", IconAtlas.Icon.VISIBILITY),
        HUD("HUD", IconAtlas.Icon.HUD),
        UTILITIES("Utilities", IconAtlas.Icon.PALETTE),
        SETTINGS("Settings", IconAtlas.Icon.SETTINGS),
        OPTIMIZATION("Optimization", IconAtlas.Icon.BOLT),
        CONFIGS("Configs", IconAtlas.Icon.FOLDER),
        ABOUT("About", IconAtlas.Icon.INFO);

        final String label;
        final IconAtlas.Icon icon;

        UiTab(String label, IconAtlas.Icon icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private static final UiTab[] TOP_TABS = {UiTab.VISUALS, UiTab.HUD, UiTab.UTILITIES};
    private static final UiTab[] BOTTOM_TABS = {UiTab.SETTINGS, UiTab.OPTIMIZATION, UiTab.CONFIGS, UiTab.ABOUT};

    // Адаптивная геометрия окна (максимум 760x520 в равномерных canvas-координатах)
    private static final float MAX_WIN_WIDTH = 760.0f;
    private static final float MAX_WIN_HEIGHT = 520.0f;
    private static final float WIN_RADIUS = 20.0f;
    private static final float SIDEBAR_PAD = 12.0f;
    private static final float CONTENT_PAD = 16.0f;

    // Параметры фиксированного Dual Kawase Blur (строго 2 прохода: 1 downsample + 1 upsample на w/4 x h/4 FBO)
    private static final int LIGHT_BLUR_PASSES = 1;
    private static final float LIGHT_BLUR_OFFSET = 0.9f;
    private static final int DEEP_BLUR_PASSES = 2;
    private static final float DEEP_BLUR_OFFSET = 2.8f;

    // Базовые текстовые цвета (акцентные цвета подтягиваются динамически из ClientSettings)
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFD1D5DB;
    private static final int TEXT_MUTED = 0xFF9CA3AF;

    private static final String PROJECT_URL = "http://site.nebulapvp.space";

    // Единая плавная анимация открытия/закрытия (0.24с, масштаб 0.95 -> 1.0, fade 0 -> 1)
    private final Animation openAnim = new Animation(0.24f, 0.0f, 1.0f, Easing.EASE_OUT_CUBIC);
    private boolean openSessionActive = false;
    private boolean closing = false;

    // Активная вкладка (по умолчанию About как в Figma) и плавные состояния кнопок сайдбара
    private UiTab currentTab = UiTab.ABOUT;
    private final Map<UiTab, Float> tabHover = new EnumMap<>(UiTab.class);
    private final Map<UiTab, Float> tabActive = new EnumMap<>(UiTab.class);
    private float linkHoverAnim = 0.0f;
    private float linkFeedbackTimer = 0.0f;
    private long handCursorHandle = 0L;
    private boolean handCursorApplied = false;

    // Состояние скролла, слайдеров и HSB-пикера
    private float scrollOffset = 0.0f;
    private float targetScroll = 0.0f;
    private float maxScroll = 0.0f;
    private SliderSetting activeSlider = null;
    private KeybindSetting listeningKeybind = null;
    private boolean accentPickerExpanded = false;
    private float accentPickerAnim = 0.0f;
    private boolean draggingColorHue = false;

    // Состояние менеджера конфигураций (вкладка Configs)
    private String newConfigName = "";
    private boolean configInputFocused = false;
    private String renamingConfigName = null;
    private String renameBuffer = "";
    private String configStatusText = "";
    private float configStatusTimer = 0.0f;

    // Модальное окно подтверждения для вкладки Configs (Yes / No)
    private enum ConfirmAction {
        OVERWRITE_CONFIG,
        SWITCH_CONFIG,
        RESET_CONFIG
    }
    private ConfirmAction pendingConfirmAction = null;
    private String pendingConfirmTarget = null;
    private float confirmModalAnim = 0.0f;

    // Кэши для zero-allocation рендера (обрезка описаний и списки модулей по вкладкам)
    private record CachedDescTrim(float maxWidth, MsdfFont font, String rawDesc, String trimmed) {}
    private final Map<VisualModule, CachedDescTrim> descTrimCache = new IdentityHashMap<>();
    private final Map<UiTab, List<VisualModule>> tabModulesCache = new EnumMap<>(UiTab.class);

    public ClickGuiScreen() {
        super(Text.literal("NebulaVisuals"));
        this.enableBackgroundBlur = false; // Блюр управляется внутри ClickGuiScreen под стеклянной подложкой
        for (UiTab tab : UiTab.values()) {
            tabHover.put(tab, 0.0f);
            tabActive.put(tab, tab == currentTab ? 1.0f : 0.0f);
        }
    }

    @Override
    protected void onInit() {
        // Не сбрасываем текущую анимацию и состояние, если экран переинициализируется/ресайзится уже открытым.
        if (!openSessionActive) {
            openSessionActive = true;
            closing = false;
            openAnim.reset(0.0f, 1.0f);
            scrollOffset = 0.0f;
            targetScroll = 0.0f;
        }
        ConfigManager.refreshConfigs();
    }

    private void stopListeningKeybind() {
        if (listeningKeybind != null) {
            listeningKeybind.setListening(false);
            listeningKeybind = null;
        }
    }

    @Override
    public void removed() {
        openSessionActive = false;
        closing = false;
        activeSlider = null;
        stopListeningKeybind();
        accentPickerExpanded = false;
        accentPickerAnim = 0.0f;
        draggingColorHue = false;
        linkFeedbackTimer = 0.0f;
        configStatusTimer = 0.0f;
        configInputFocused = false;
        renamingConfigName = null;
        renameBuffer = "";
        pendingConfirmAction = null;
        pendingConfirmTarget = null;
        confirmModalAnim = 0.0f;
        updateLinkCursor(false);
        super.removed();
    }

    public void startClosing() {
        if (closing) return;
        closing = true;
        stopListeningKeybind();
        updateLinkCursor(false);
        openAnim.reset(openAnim.getValue(), 0.0f);
    }

    /**
     * Проверяет, перекрывается ли указанный HUD-элемент открытым окном ClickGUI,
     * чтобы не тратить GPU-ресурсы на повторную отрисовку HUD и KawaseBlur под окном.
     */
    public boolean isRectOverlappingWindow(float x, float y, float w, float h) {
        if (!openSessionActive || openAnim.getValue() <= 0.1f) {
            return false;
        }
        updateViewportMetrics();
        float winW = computeWinWidth();
        float winH = computeWinHeight();
        float winX = (canvasWidth - winW) * 0.5f;
        float winY = (canvasHeight - winH) * 0.5f;
        return x + w > winX && x < winX + winW && y + h > winY && y < winY + winH;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (NebulaRenderEngine.isInitialized()) {
            KawaseBlur blur = NebulaRenderEngine.getBlur();
            if (blur != null && framebufferWidth > 0 && framebufferHeight > 0) {
                blur.ensureCapacity(framebufferWidth, framebufferHeight, DEEP_BLUR_PASSES);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateViewportMetrics();

        float dt = AnimationEngine.updateDelta();
        onTickAnimations(dt);

        float vMouseX = getCursorCanvasX(mouseX);
        float vMouseY = getCursorCanvasY(mouseY);

        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        KawaseBlur blur = NebulaRenderEngine.getBlur();

        float progress = Math.clamp(openAnim.getValue(), 0.0f, 1.0f);
        float scale = 0.95f + 0.05f * progress;

        // 1. Рендер двойного KawaseBlur (2 статических ping-pong FBO 1/4 разрешения + glBlitFramebuffer)
        int deepBlurredTex = 0;
        int lightBlurredTex = 0;
        float blurStrength = ClientSettings.getBlurStrength();
        float blurFade = blurStrength <= 0.01f ? 0.0f : Math.clamp(blurStrength / 0.25f, 0.0f, 1.0f);
        if (progress > 0.005f && blurFade > 0.001f && client != null && client.getFramebuffer() != null
                && framebufferWidth > 0 && framebufferHeight > 0) {
            int fbTexture = 0;
            if (client.getFramebuffer().getColorAttachment() instanceof GlTexture glTexture) {
                fbTexture = glTexture.getGlId();
            }

            if (fbTexture != 0) {
                float lightOff = Math.clamp(LIGHT_BLUR_OFFSET * blurStrength, 0.2f, 2.5f);
                float deepOff = Math.clamp(DEEP_BLUR_OFFSET * blurStrength, 0.35f, 5.5f);
                deepBlurredTex = blur.renderDualBlur(
                        fbTexture, framebufferWidth, framebufferHeight,
                        LIGHT_BLUR_PASSES, lightOff,
                        DEEP_BLUR_PASSES, deepOff
                );
                lightBlurredTex = blur.getLightTextureId();
            }
        }

        // 2. Отрисовка фонового лёгкого блюра, затем стеклянного окна и интерфейса в равномерном canvas
        batch.begin(canvasWidth, canvasHeight, uiScale);

        if (progress > 0.005f) {
            if (lightBlurredTex != 0 && blurFade > 0.001f) {
                float lightAlpha = progress * Math.min(1.0f, blurStrength) * blurFade;
                batch.drawTexturedRect(
                        lightBlurredTex,
                        0.0f, 0.0f, canvasWidth, canvasHeight,
                        0.0f, 1.0f, 1.0f, 0.0f,
                        ColorUtil.argb((int) (185 * lightAlpha), 255, 255, 255)
                );
            }
            float uiOpacity = ClientSettings.getUiOpacity();
            batch.drawRect(0.0f, 0.0f, canvasWidth, canvasHeight,
                    ColorUtil.argb((int) (40.0f * progress * Math.clamp(0.4f + 0.6f * blurFade * uiOpacity, 0.35f, 1.0f)), 6, 8, 12));

            renderGlassWindow(batch, blur, deepBlurredTex, blurFade, vMouseX, vMouseY, scale, progress);
        }

        batch.end();
    }

    @Override
    protected void onTickAnimations(float dt) {
        openAnim.update(dt);
        accentPickerAnim = AnimationEngine.expLerp(accentPickerAnim, accentPickerExpanded ? 1.0f : 0.0f, 14.0f, dt);
        confirmModalAnim = AnimationEngine.expLerp(confirmModalAnim, pendingConfirmAction != null ? 1.0f : 0.0f, 18.0f, dt);

        if (linkFeedbackTimer > 0.0f) {
            linkFeedbackTimer = Math.max(0.0f, linkFeedbackTimer - dt);
        }
        if (configStatusTimer > 0.0f) {
            configStatusTimer = Math.max(0.0f, configStatusTimer - dt);
        }

        ClientSettings.updateAnimations(dt);
        for (VisualModule m : ModuleManager.getModules()) {
            m.updateAnimations(dt);
        }

        // Пересчитываем допустимую глубину скролла до интерполяции, чтобы при сворачивании настроек контент не улетал вверх
        recomputeScrollBounds();
        scrollOffset = AnimationEngine.expLerp(scrollOffset, targetScroll, 14.0f, dt);
        clampScrollBounds();

        if (closing && (openAnim.isFinished() || openAnim.getValue() <= 0.005f)) {
            closing = false;
            openSessionActive = false;
            if (client != null) {
                client.setScreen(null);
            }
        }
    }

    @Override
    protected void onRender(RenderBatch2D batch, float mouseX, float mouseY, float dt) {
        // Отрисовка выполняется в render() вместе с glassmorphism-блюром.
    }

    private float computeWinWidth() {
        float pad = canvasWidth < 400.0f ? 20.0f : 48.0f;
        return Math.min(MAX_WIN_WIDTH, Math.max(260.0f, canvasWidth - pad));
    }

    private float computeWinHeight() {
        float pad = canvasHeight < 320.0f ? 20.0f : 48.0f;
        return Math.min(MAX_WIN_HEIGHT, Math.max(240.0f, canvasHeight - pad));
    }

    private float computeSidebarWidth(float winW) {
        if (winW < 560.0f) {
            return Math.max(120.0f, winW * 0.28f);
        }
        return Math.clamp(winW * 0.25f, 165.0f, 190.0f);
    }

    private record SidebarLayout(
            float cardX, float cardW,
            float brandY, float brandH,
            float catBoxY, float catBoxH,
            float footBoxY, float footBoxH,
            float btnX, float btnW, float btnH, float btnGap, float boxPadY
    ) {}

    private SidebarLayout computeSidebarLayout(float winX, float winY, float winH, float sideW) {
        float cardX = winX + SIDEBAR_PAD;
        float cardW = sideW - SIDEBAR_PAD * 2.0f;
        float outerPad = winH < 360.0f ? 10.0f : 14.0f;
        float brandY = winY + outerPad;
        float brandH = Math.clamp(winH * 0.135f, 46.0f, 68.0f);
        float sectionGap = winH < 360.0f ? 7.0f : 11.0f;
        float boxPadY = winH < 360.0f ? 6.0f : 11.0f;
        float btnGap = winH < 360.0f ? 4.0f : 7.0f;

        // 7 кнопок (3 в верхнем блоке, 4 в нижнем блоке)
        float availForBtns = winH - outerPad * 2.0f - brandH - sectionGap * 2.0f - boxPadY * 4.0f - btnGap * 5.0f;
        float btnH = Math.clamp(availForBtns / 7.0f, 18.0f, 35.0f);

        float footBoxH = boxPadY * 2.0f + btnH * BOTTOM_TABS.length + btnGap * (BOTTOM_TABS.length - 1);
        float footBoxY = winY + winH - outerPad - footBoxH;
        float catBoxY = brandY + brandH + sectionGap;
        float minCatBoxH = boxPadY * 2.0f + btnH * TOP_TABS.length + btnGap * (TOP_TABS.length - 1);
        float catBoxH = Math.max(minCatBoxH, footBoxY - sectionGap - catBoxY);

        float btnX = cardX + 10.0f;
        float btnW = cardW - 20.0f;
        return new SidebarLayout(cardX, cardW, brandY, brandH, catBoxY, catBoxH, footBoxY, footBoxH, btnX, btnW, btnH, btnGap, boxPadY);
    }

    private float computeAboutLinkY(float zoneY, float zoneW, float zoneH) {
        boolean compact = zoneH < 340.0f || zoneW < 380.0f;
        float topY = zoneY + (compact ? 22.0f : 34.0f);
        float infoY = topY + (compact ? 58.0f : 86.0f);
        return infoY + (compact ? 62.0f : 86.0f);
    }

    private void renderGlassWindow(RenderBatch2D batch, KawaseBlur blur, int blurredTex, float blurFade,
                                   float mouseX, float mouseY, float scale, float alpha) {
        float cx = canvasWidth * 0.5f;
        float cy = canvasHeight * 0.5f;
        float mx = cx + (mouseX - cx) / scale;
        float my = cy + (mouseY - cy) / scale;

        float winW = computeWinWidth();
        float winH = computeWinHeight();
        float sideW = computeSidebarWidth(winW);
        float winX = cx - (winW * 0.5f);
        float winY = cy - (winH * 0.5f);

        updateActiveSlider(mx);
        updateActiveColorPicker(mx, winX, winY, winW, winH, sideW);
        float sx = scaleX(winX, cx, scale);
        float sy = scaleY(winY, cy, scale);
        float sw = winW * scale;
        float sh = winH * scale;
        float uiOpacity = ClientSettings.getUiOpacity();

        // 1. Внешняя мягкая тень строго по периметру окна (внутри окна 0% через SDF)
        batch.drawShadow(sx, sy, sw, sh, WIN_RADIUS * scale, 32.0f * scale, ColorUtil.argb((int) (135 * alpha), 0, 0, 0));

        // 2. Размытие игрового мира строго внутри скругленного окна (эффект матового стекла)
        if (blurredTex != 0 && blurFade > 0.001f) {
            blur.drawBlurredRect(
                    batch,
                    blurredTex,
                    sx, sy, sw, sh,
                    WIN_RADIUS * scale,
                    canvasWidth,
                    canvasHeight,
                    ColorUtil.argb((int) (255.0f * alpha * blurFade), 255, 255, 255)
            );
        }

        // 3. Основная темная космическая подложка (прозрачность зависит от UI Opacity)
        float splitX = scaleX(winX + sideW, cx, scale);
        float rightW = (winW - sideW) * scale;
        batch.drawGradientRoundedRect(
                splitX, sy, rightW, sh,
                0.0f, WIN_RADIUS * scale, WIN_RADIUS * scale, 0.0f,
                ColorUtil.argb((int) (200.0f * uiOpacity * alpha), 15, 18, 23),
                ColorUtil.argb((int) (206.0f * uiOpacity * alpha), 13, 15, 18),
                ColorUtil.argb((int) (214.0f * uiOpacity * alpha), 11, 13, 16),
                ColorUtil.argb((int) (206.0f * uiOpacity * alpha), 13, 15, 18)
        );

        // 4. Левый сайдбар (#0A0B0E под UI Opacity) + 1px вертикальный разделитель
        renderSidebar(batch, winX, winY, winH, sideW, cx, cy, scale, alpha, uiOpacity, mx, my);

        // Внешний тонкий полупрозрачный бордер 1px (#3A3E4A) поверх всего окна
        batch.drawOutlineRoundedRect(
                sx, sy, sw, sh,
                WIN_RADIUS * scale,
                1.0f * scale,
                ColorUtil.argb((int) (135 * alpha), 58, 62, 74)
        );

        // 5. Правая контент-зона с логотипом планеты в правом нижнем углу
        renderContentZone(batch, winX, winY, winW, winH, sideW, cx, cy, scale, alpha, mx, my);
    }

    private void renderSidebar(RenderBatch2D batch, float winX, float winY, float winH, float sideW,
                               float cx, float cy, float scale, float alpha, float uiOpacity, float mx, float my) {
        float sideX = winX;
        float sideY = winY;

        // Подложка сайдбара #0A0B0E (alpha под UI Opacity)
        batch.drawRoundedRect(
                scaleX(sideX, cx, scale),
                scaleY(sideY, cy, scale),
                sideW * scale,
                winH * scale,
                WIN_RADIUS * scale, 0.0f, 0.0f, WIN_RADIUS * scale,
                ColorUtil.argb((int) (180.0f * uiOpacity * alpha), 10, 11, 14)
        );

        // Вертикальный 1px разделитель между сайдбаром и контентом
        batch.drawRect(
                scaleX(winX + sideW, cx, scale),
                scaleY(winY + 12.0f, cy, scale),
                1.0f * scale,
                (winH - 24.0f) * scale,
                ColorUtil.argb((int) (24 * alpha), 255, 255, 255)
        );

        SidebarLayout layout = computeSidebarLayout(winX, winY, winH, sideW);

        // Блок 1: Чистый минималистичный заголовок NebulaVisuals и версия
        drawGlassSection(batch, layout.cardX, layout.brandY, layout.cardW, layout.brandH, 16.0f, cx, cy, scale, alpha);

        float titleFontSize = sideW < 155.0f ? 15.0f : 18.0f;
        float subFontSize = sideW < 155.0f ? 11.0f : 12.0f;
        FontRenderer.drawText(
                batch,
                "NebulaVisuals",
                scaleX(layout.cardX + 14.0f, cx, scale),
                scaleY(layout.brandY + layout.brandH * 0.22f, cy, scale),
                titleFontSize * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );
        FontRenderer.drawText(
                batch,
                "ver. " + org.itzmr.nebulavisuals.Nebulavisuals.MOD_VERSION,
                scaleX(layout.cardX + 14.0f, cx, scale),
                scaleY(layout.brandY + layout.brandH * 0.58f, cy, scale),
                subFontSize * scale,
                ColorUtil.withAlpha(TEXT_MUTED, alpha)
        );

        // Блок 2: Верхний контейнер ("Visuals", "HUD", "Utilities")
        drawGlassSection(batch, layout.cardX, layout.catBoxY, layout.cardW, layout.catBoxH, 16.0f, cx, cy, scale, alpha);

        float btnY = layout.catBoxY + layout.boxPadY;
        for (UiTab tab : TOP_TABS) {
            renderSidebarPill(batch, tab, layout.btnX, btnY, layout.btnW, layout.btnH, cx, cy, scale, alpha, mx, my);
            btnY += layout.btnH + layout.btnGap;
        }

        // Блок 3: Нижний контейнер ("Settings", "Optimization", "Configs", "About")
        drawGlassSection(batch, layout.cardX, layout.footBoxY, layout.cardW, layout.footBoxH, 16.0f, cx, cy, scale, alpha);

        float footBtnY = layout.footBoxY + layout.boxPadY;
        for (UiTab tab : BOTTOM_TABS) {
            renderSidebarPill(batch, tab, layout.btnX, footBtnY, layout.btnW, layout.btnH, cx, cy, scale, alpha, mx, my);
            footBtnY += layout.btnH + layout.btnGap;
        }
    }

    private void drawGlassSection(RenderBatch2D batch, float x, float y, float w, float h, float radius,
                                  float cx, float cy, float scale, float alpha) {
        float uiOpacity = ClientSettings.getUiOpacity();
        batch.drawRoundedRectWithOutline(
                scaleX(x, cx, scale),
                scaleY(y, cy, scale),
                w * scale,
                h * scale,
                radius * scale,
                ColorUtil.argb((int) (12.0f * uiOpacity * alpha), 255, 255, 255),
                1.0f * scale,
                ColorUtil.argb((int) (16 * alpha), 255, 255, 255)
        );
    }

    private void renderSidebarPill(RenderBatch2D batch, UiTab tab, float x, float y, float w, float h,
                                   float cx, float cy, float scale, float alpha, float mx, float my) {
        boolean active = (currentTab == tab);
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y + h;

        float dt = AnimationEngine.getDeltaTime();
        float hAnim = AnimationEngine.expLerp(tabHover.getOrDefault(tab, 0.0f), hovered ? 1.0f : 0.0f, 14.0f, dt);
        float aAnim = AnimationEngine.expLerp(tabActive.getOrDefault(tab, 0.0f), active ? 1.0f : 0.0f, 14.0f, dt);
        tabHover.put(tab, hAnim);
        tabActive.put(tab, aAnim);

        int accent = ClientSettings.getAccentColor();
        int accentHover = ClientSettings.getAccentHoverColor();

        // Неактивные: rgba(255,255,255,0.03) -> hover rgba(255,255,255,0.08); активная: динамический акцент + 1.5px рамка
        int idleFill = ColorUtil.argb((int) (8 * alpha), 255, 255, 255);
        int hoverFill = ColorUtil.argb((int) (21 * alpha), 255, 255, 255);
        int neutralFill = ColorUtil.lerp(idleFill, hoverFill, hAnim);
        int activeFill = ColorUtil.withAlpha(accent, ((32.0f + 12.0f * hAnim) / 255.0f) * alpha);
        int fillColor = ColorUtil.lerp(neutralFill, activeFill, aAnim);

        float sx = scaleX(x, cx, scale);
        float sy = scaleY(y, cy, scale);
        if (aAnim > 0.02f) {
            int activeOutline = ColorUtil.withAlpha(accent, aAnim * alpha);
            batch.drawRoundedRectWithOutline(sx, sy, w * scale, h * scale, 14.0f * scale, fillColor, 1.5f * scale, activeOutline);
        } else {
            batch.drawRoundedRect(sx, sy, w * scale, h * scale, 14.0f * scale, fillColor);
        }

        int inactiveColor = ColorUtil.lerp(
                ColorUtil.withAlpha(0xFF9CA3AF, alpha),
                ColorUtil.withAlpha(TEXT_WHITE, alpha),
                hAnim
        );
        int activeColor = ColorUtil.withAlpha(ColorUtil.lerp(TEXT_WHITE, accentHover, 0.55f), alpha);
        int contentColor = ColorUtil.lerp(inactiveColor, activeColor, aAnim);

        float padLeft = w < 125.0f ? 10.0f : 14.0f;
        float iconSize = h < 26.0f ? 12.5f : (h < 31.0f ? 14.5f : 15.5f);
        float iconGap = w < 120.0f ? 7.0f : 10.0f;
        float pillFontSize = h < 26.0f ? 11.5f : (h < 31.0f ? 12.8f : 14.0f);

        String tabText = LocalizationManager.tabLabel(tab.label);
        float textStartX = x + padLeft + iconSize + iconGap;
        float maxLabelW = Math.max(20.0f, (x + w - 8.0f) - textStartX);
        float rawLabelW = FontRenderer.getWidth(tabText, pillFontSize);
        if (rawLabelW > maxLabelW && rawLabelW > 1.0f) {
            pillFontSize = Math.max(9.5f, pillFontSize * (maxLabelW / rawLabelW));
        }

        float iconCenterX = scaleX(x + padLeft + iconSize * 0.5f, cx, scale);
        float iconCenterY = scaleY(y + h * 0.5f, cy, scale);
        IconAtlas.drawIcon(batch, tab.icon, iconCenterX, iconCenterY, iconSize * scale, contentColor);

        float textOffsetY = Math.max(2.0f, (h - FontRenderer.getHeight(pillFontSize)) * 0.5f);
        FontRenderer.drawText(
                batch,
                tabText,
                scaleX(textStartX, cx, scale),
                scaleY(y + textOffsetY, cy, scale),
                pillFontSize * scale,
                contentColor
        );
    }

    private void renderContentZone(RenderBatch2D batch, float winX, float winY, float winW, float winH, float sideW,
                                   float cx, float cy, float scale, float alpha, float mx, float my) {
        float zoneX = winX + sideW + CONTENT_PAD;
        float zoneY = winY + CONTENT_PAD;
        float zoneW = winW - sideW - CONTENT_PAD * 2.0f;
        float zoneH = winH - CONTENT_PAD * 2.0f;
        float panelRadius = 18.0f;

        float sx = scaleX(zoneX, cx, scale);
        float sy = scaleY(zoneY, cy, scale);
        float sw = zoneW * scale;
        float sh = zoneH * scale;

        // Внутренняя стеклянная подложка контент-зоны (как в Figma, прозрачность под UI Opacity)
        float uiOpacity = ClientSettings.getUiOpacity();
        batch.drawRoundedRectWithOutline(
                sx, sy, sw, sh,
                panelRadius * scale,
                ColorUtil.argb((int) (68.0f * uiOpacity * alpha), 6, 7, 10),
                1.0f * scale,
                ColorUtil.argb((int) (14 * alpha), 255, 255, 255)
        );

        // Scissor-клиппинг для скроллируемого содержимого и подложки About
        batch.beginScissor(sx, sy, sw, sh, canvasWidth, canvasHeight);

        if (currentTab == UiTab.ABOUT) {
            renderAboutPlanetLogo(batch, zoneX, zoneY, zoneW, zoneH, panelRadius, cx, cy, scale, alpha);
            renderAboutTab(batch, zoneX, zoneY, zoneW, zoneH, cx, cy, scale, alpha, mx, my);
        } else {
            updateLinkCursor(false);
            switch (currentTab) {
                case SETTINGS -> renderSettingsTab(batch, zoneX, zoneY, zoneW, zoneH, cx, cy, scale, alpha, mx, my);
                case CONFIGS -> renderConfigsTab(batch, zoneX, zoneY, zoneW, zoneH, cx, cy, scale, alpha, mx, my);
                case VISUALS, HUD, UTILITIES, OPTIMIZATION -> renderModulesTab(batch, zoneX, zoneY, zoneW, zoneH, cx, cy, scale, alpha, mx, my);
                default -> {}
            }
        }

        batch.endScissor();

        if (currentTab == UiTab.CONFIGS && (pendingConfirmAction != null || confirmModalAnim > 0.01f)) {
            renderConfirmModal(batch, zoneX, zoneY, zoneW, zoneH, panelRadius, cx, cy, scale, alpha * confirmModalAnim, mx, my);
        }
    }

    /**
     * Переиспользуемый метод отрисовки логотипа Nebula в текущем {@code Accent Color}
     * вокруг геометрического центра {@code (centerX, centerY)}.
     */
    public void renderAccentLogo(float centerX, float centerY, float size, float rotationDegrees, float alpha) {
        if (NebulaRenderEngine.isInitialized()) {
            NebulaRenderEngine.renderAccentLogo(NebulaRenderEngine.getBatch(), centerX, centerY, size, rotationDegrees, alpha);
        }
    }

    private void renderAboutPlanetLogo(RenderBatch2D batch, float zoneX, float zoneY, float zoneW, float zoneH,
                                       float panelRadius, float cx, float cy, float scale, float alpha) {
        Texture2D logoTex = NebulaRenderEngine.getLogoTexture();
        if (logoTex == null || logoTex.getTextureId() <= 0) return;

        // Крупная буква N под -45° в правом нижнем углу: нижний правый край уходит за рамку и ровно обрезается по скруглённой карточке
        float logoSize = Math.min(460.0f, Math.max(250.0f, Math.min(zoneW * 0.86f, zoneH * 0.92f)));
        float logoCenterX = (zoneX + zoneW) - logoSize * 0.16f;
        float logoCenterY = (zoneY + zoneH) - logoSize * 0.14f;

        float sx = scaleX(zoneX, cx, scale);
        float sy = scaleY(zoneY, cy, scale);
        float sw = zoneW * scale;
        float sh = zoneH * scale;

        NebulaRenderEngine.renderAccentLogoClipped(
                batch,
                scaleX(logoCenterX, cx, scale),
                scaleY(logoCenterY, cy, scale),
                logoSize * scale,
                -45.0f,
                0.52f * alpha,
                sx, sy, sw, sh,
                panelRadius * scale, panelRadius * scale, panelRadius * scale, panelRadius * scale
        );
    }

    private void renderAboutTab(RenderBatch2D batch, float zoneX, float zoneY, float zoneW, float zoneH,
                                float cx, float cy, float scale, float alpha, float mx, float my) {
        boolean compact = zoneH < 340.0f || zoneW < 380.0f;
        float textX = zoneX + (compact ? 20.0f : 28.0f);
        float topY = zoneY + (compact ? 22.0f : 34.0f);
        float titleSize = zoneW < 360.0f ? 28.0f : 38.0f;
        float subSize = zoneW < 360.0f ? 12.5f : 14.0f;

        FontRenderer.drawText(
                batch,
                LocalizationManager.tr("about.welcome"),
                scaleX(textX, cx, scale),
                scaleY(topY, cy, scale),
                subSize * scale,
                ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
        );

        FontRenderer.drawText(
                batch,
                "NebulaVisuals",
                scaleX(textX, cx, scale),
                scaleY(topY + 20.0f, cy, scale),
                titleSize * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );

        float infoY = topY + (compact ? 58.0f : 86.0f);
        FontRenderer.drawText(
                batch,
                LocalizationManager.tr("about.dedicated"),
                scaleX(textX, cx, scale),
                scaleY(infoY, cy, scale),
                subSize * scale,
                ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
        );

        FontRenderer.drawText(
                batch,
                LocalizationManager.tr("about.creator") + " itzmr (made with Gemini 4 Hydrogen, lol)",
                scaleX(textX, cx, scale),
                scaleY(infoY + (compact ? 18.0f : 22.0f), cy, scale),
                subSize * scale,
                ColorUtil.withAlpha(TEXT_MUTED, alpha)
        );

        FontRenderer.drawText(
                batch,
                "Version: " + org.itzmr.nebulavisuals.Nebulavisuals.MOD_VERSION,
                scaleX(textX, cx, scale),
                scaleY(infoY + (compact ? 36.0f : 44.0f), cy, scale),
                subSize * scale,
                ColorUtil.withAlpha(TEXT_MUTED, alpha)
        );

        // Кликабельная ссылка на сайт проекта с акцентным подчёркиванием
        String linkText = LocalizationManager.tr("about.website");
        float linkSize = subSize;
        float linkY = computeAboutLinkY(zoneY, zoneW, zoneH);
        float linkW = FontRenderer.getWidth(linkText, linkSize);
        float linkH = Math.max(18.0f, FontRenderer.getHeight(linkSize));
        boolean linkHovered = !closing && mx >= textX && mx <= textX + linkW && my >= linkY - 2.0f && my <= linkY + linkH + 4.0f;
        updateLinkCursor(linkHovered);

        linkHoverAnim = AnimationEngine.expLerp(linkHoverAnim, linkHovered ? 1.0f : 0.0f, 14.0f, AnimationEngine.getDeltaTime());
        int accent = ClientSettings.getAccentColor();
        int accentHover = ClientSettings.getAccentHoverColor();
        int linkColor = ColorUtil.lerp(
                ColorUtil.withAlpha(accent, alpha),
                ColorUtil.withAlpha(accentHover, alpha),
                linkHoverAnim
        );

        float scaledLinkX = scaleX(textX, cx, scale);
        float scaledLinkY = scaleY(linkY, cy, scale);
        FontRenderer.drawText(batch, linkText, scaledLinkX, scaledLinkY, linkSize * scale, linkColor);

        // Подчеркивание ссылки
        float scaledLinkW = FontRenderer.getWidth(linkText, linkSize * scale);
        batch.drawRect(
                scaledLinkX,
                scaledLinkY + 17.5f * scale,
                scaledLinkW,
                1.0f * scale,
                linkColor
        );

        if (linkFeedbackTimer > 0.0f) {
            float fbAlpha = Math.min(1.0f, linkFeedbackTimer / 0.4f) * alpha;
            FontRenderer.drawText(
                    batch,
                    PROJECT_URL + " " + LocalizationManager.tr("about.opened"),
                    scaledLinkX + scaledLinkW + 12.0f * scale,
                    scaledLinkY + 1.0f * scale,
                    11.0f * scale,
                    ColorUtil.withAlpha(accentHover, 0.72f * fbAlpha)
            );
        }
    }

    private record ConfigsGeometry(
            float x, float y, float w,
            float autoSaveX, float autoSaveY, float autoSaveW, float autoSaveH,
            float inputX, float inputY, float inputW, float actionH,
            float createX, float createW,
            float folderX, float refreshX, float iconBtnW,
            float listY, float listH
    ) {}

    private record ConfigCardButtons(
            float loadX, float saveX, float renX, float resetX, float delX,
            float leftMostX, float btnY, float btnSize
    ) {}

    private ConfigCardButtons computeConfigCardButtons(ConfigsGeometry g, float cardY, float cardH, boolean isDefault) {
        float btnSize = 26.0f;
        float btnGap = 6.0f;
        float rightPad = g.x + g.w - 14.0f;
        float btnY = cardY + (cardH - btnSize) * 0.5f;
        if (isDefault) {
            float resetX = rightPad - btnSize;
            float saveX = resetX - btnGap - btnSize;
            float loadX = saveX - btnGap - btnSize;
            return new ConfigCardButtons(loadX, saveX, -1.0f, resetX, -1.0f, loadX, btnY, btnSize);
        }
        float delX = rightPad - btnSize;
        float resetX = delX - btnGap - btnSize;
        float renX = resetX - btnGap - btnSize;
        float saveX = renX - btnGap - btnSize;
        float loadX = saveX - btnGap - btnSize;
        return new ConfigCardButtons(loadX, saveX, renX, resetX, delX, loadX, btnY, btnSize);
    }

    private ConfigsGeometry computeConfigsGeometry(float zoneX, float zoneY, float zoneW, float zoneH) {
        float x = zoneX + 24.0f;
        float y = zoneY + 22.0f;
        float w = Math.max(240.0f, zoneW - 48.0f);
        String autoSaveLabel = LocalizationManager.tr("configs.auto_save");
        float autoSaveW = FontRenderer.hasDefaultFont()
                ? Math.max(104.0f, FontRenderer.getWidth(autoSaveLabel, 11.5f) + 38.0f)
                : 104.0f;
        float autoSaveH = 24.0f;
        float autoSaveX = x + w - autoSaveW;
        float autoSaveY = y + 4.0f;
        float actionY = y + 48.0f;
        float actionH = 32.0f;
        float iconBtnW = 32.0f;
        float refreshX = x + w - iconBtnW;
        float folderX = refreshX - 8.0f - iconBtnW;
        String createLabel = LocalizationManager.tr("configs.create");
        float createW = FontRenderer.hasDefaultFont()
                ? Math.max(92.0f, FontRenderer.getWidth(createLabel, 12.0f) + 42.0f)
                : 92.0f;
        float createX = folderX - 8.0f - createW;
        float inputW = Math.max(80.0f, createX - 8.0f - x);
        float listY = actionY + actionH + 14.0f;
        float listH = Math.max(60.0f, (zoneY + zoneH) - listY - 26.0f);
        return new ConfigsGeometry(
                x, y, w,
                autoSaveX, autoSaveY, autoSaveW, autoSaveH,
                x, actionY, inputW, actionH,
                createX, createW, folderX, refreshX, iconBtnW, listY, listH
        );
    }

    private void renderConfigsTab(RenderBatch2D batch, float zoneX, float zoneY, float zoneW, float zoneH,
                                  float cx, float cy, float scale, float alpha, float mx, float my) {
        String pendingNotice = ConfigManager.consumeLastStatusNotice();
        if (!pendingNotice.isEmpty()) {
            setConfigStatus(pendingNotice, 4.5f);
        }

        ConfigsGeometry g = computeConfigsGeometry(zoneX, zoneY, zoneW, zoneH);
        int accent = ClientSettings.getAccentColor();
        int accentHover = ClientSettings.getAccentHoverColor();
        float uiOpacity = ClientSettings.getUiOpacity();
        boolean caretVisible = ((System.currentTimeMillis() / 500L) & 1L) == 0L;

        FontRenderer.drawText(
                batch,
                LocalizationManager.tr("configs.title"),
                scaleX(g.x, cx, scale),
                scaleY(g.y, cy, scale),
                22.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );

        String dirHint = FontRenderer.getDefaultFont() != null
                ? FontRenderer.getDefaultFont().trimToWidth(ConfigManager.getConfigsDirDisplay(), Math.max(80.0f, g.w - g.autoSaveW - 14.0f), 11.0f, true)
                : ConfigManager.getConfigsDirDisplay();
        FontRenderer.drawText(
                batch,
                dirHint,
                scaleX(g.x, cx, scale),
                scaleY(g.y + 26.0f, cy, scale),
                11.0f * scale,
                ColorUtil.withAlpha(TEXT_MUTED, alpha)
        );

        // Чекбокс Auto-save для текущего активного конфига
        boolean autoSaveOn = ConfigManager.isAutoSaveEnabled();
        boolean autoSaveHovered = isInside(mx, my, g.autoSaveX, g.autoSaveY, g.autoSaveW, g.autoSaveH);
        int asBg = autoSaveOn
                ? ColorUtil.withAlpha(accent, (autoSaveHovered ? 0.28f : 0.18f) * alpha)
                : ColorUtil.argb((int) ((autoSaveHovered ? 22 : 12) * alpha), 255, 255, 255);
        int asBorder = autoSaveOn
                ? ColorUtil.withAlpha(accent, (autoSaveHovered ? 0.95f : 0.72f) * alpha)
                : ColorUtil.argb((int) ((autoSaveHovered ? 52 : 30) * alpha), 255, 255, 255);
        batch.drawRoundedRectWithOutline(
                scaleX(g.autoSaveX, cx, scale),
                scaleY(g.autoSaveY, cy, scale),
                g.autoSaveW * scale,
                g.autoSaveH * scale,
                8.0f * scale,
                asBg,
                1.0f * scale,
                asBorder
        );
        float cbSize = 13.0f;
        float cbX = g.autoSaveX + 8.0f;
        float cbY = g.autoSaveY + (g.autoSaveH - cbSize) * 0.5f;
        int cbFill = autoSaveOn
                ? ColorUtil.withAlpha(accent, 0.95f * alpha)
                : ColorUtil.argb((int) (18 * alpha), 255, 255, 255);
        int cbBorder = autoSaveOn
                ? ColorUtil.withAlpha(accent, alpha)
                : ColorUtil.argb((int) (75 * alpha), 255, 255, 255);
        batch.drawRoundedRectWithOutline(
                scaleX(cbX, cx, scale),
                scaleY(cbY, cy, scale),
                cbSize * scale,
                cbSize * scale,
                4.0f * scale,
                cbFill,
                1.0f * scale,
                cbBorder
        );
        if (autoSaveOn) {
            IconAtlas.drawIcon(
                    batch,
                    IconAtlas.Icon.CHECK,
                    scaleX(cbX + cbSize * 0.5f, cx, scale),
                    scaleY(cbY + cbSize * 0.5f, cy, scale),
                    10.0f * scale,
                    ColorUtil.withAlpha(TEXT_WHITE, alpha)
            );
        }
        FontRenderer.drawText(
                batch,
                LocalizationManager.tr("configs.auto_save"),
                scaleX(cbX + cbSize + 7.0f, cx, scale),
                scaleY(g.autoSaveY + (g.autoSaveH - FontRenderer.getHeight(11.5f)) * 0.5f, cy, scale),
                11.5f * scale,
                ColorUtil.withAlpha(autoSaveOn ? TEXT_WHITE : TEXT_SECONDARY, alpha)
        );

        // 1. Поле ввода имени нового конфига
        boolean inputHovered = isInside(mx, my, g.inputX, g.inputY, g.inputW, g.actionH);
        int inputBg = ColorUtil.argb((int) ((configInputFocused ? 22.0f : (inputHovered ? 16.0f : 11.0f)) * uiOpacity * alpha), 255, 255, 255);
        int inputOutline = configInputFocused
                ? ColorUtil.withAlpha(accent, 0.90f * alpha)
                : ColorUtil.argb((int) ((inputHovered ? 38 : 22) * alpha), 255, 255, 255);
        batch.drawRoundedRectWithOutline(
                scaleX(g.inputX, cx, scale),
                scaleY(g.inputY, cy, scale),
                g.inputW * scale,
                g.actionH * scale,
                10.0f * scale,
                inputBg,
                1.0f * scale,
                inputOutline
        );

        String displayInput = newConfigName.isEmpty() && !configInputFocused ? LocalizationManager.tr("configs.new_placeholder") : newConfigName;
        if (configInputFocused && caretVisible) {
            displayInput = displayInput + "|";
        }
        String trimmedInput = FontRenderer.getDefaultFont() != null
                ? FontRenderer.getDefaultFont().trimToWidth(displayInput, g.inputW - 20.0f, 12.0f, true)
                : displayInput;
        int inputTextCol = (newConfigName.isEmpty() && !configInputFocused) ? TEXT_MUTED : TEXT_WHITE;
        FontRenderer.drawText(
                batch,
                trimmedInput,
                scaleX(g.inputX + 12.0f, cx, scale),
                scaleY(g.inputY + 9.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(inputTextCol, alpha)
        );

        // 2. Кнопка Create (0xE145)
        boolean createHovered = isInside(mx, my, g.createX, g.inputY, g.createW, g.actionH);
        int createBg = ColorUtil.withAlpha(accent, (createHovered ? 0.36f : 0.24f) * alpha);
        int createOutline = ColorUtil.withAlpha(accent, (createHovered ? 0.96f : 0.72f) * alpha);
        batch.drawRoundedRectWithOutline(
                scaleX(g.createX, cx, scale),
                scaleY(g.inputY, cy, scale),
                g.createW * scale,
                g.actionH * scale,
                10.0f * scale,
                createBg,
                1.0f * scale,
                createOutline
        );
        IconAtlas.drawIcon(
                batch,
                IconAtlas.Icon.PLUS,
                scaleX(g.createX + 18.0f, cx, scale),
                scaleY(g.inputY + g.actionH * 0.5f, cy, scale),
                13.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );
        FontRenderer.drawText(
                batch,
                LocalizationManager.tr("configs.create"),
                scaleX(g.createX + 32.0f, cx, scale),
                scaleY(g.inputY + 9.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );

        // 3. Кнопки Open Folder (0xE2C7) и Refresh (0xE5D5)
        renderConfigIconBtn(batch, IconAtlas.Icon.FOLDER, g.folderX, g.inputY, g.iconBtnW, g.actionH,
                isInside(mx, my, g.folderX, g.inputY, g.iconBtnW, g.actionH), false, cx, cy, scale, alpha, uiOpacity, accent);
        renderConfigIconBtn(batch, IconAtlas.Icon.REFRESH, g.refreshX, g.inputY, g.iconBtnW, g.actionH,
                isInside(mx, my, g.refreshX, g.inputY, g.iconBtnW, g.actionH), false, cx, cy, scale, alpha, uiOpacity, accent);

        // 4. Скроллируемый список .json конфигов
        List<String> configNames = ConfigManager.getConfigNames();
        float cardH = 52.0f;
        float totalH = configNames.size() * (cardH + 10.0f);
        maxScroll = Math.max(0.0f, totalH - g.listH);
        clampScrollBounds();

        batch.beginScissor(
                scaleX(g.x, cx, scale),
                scaleY(g.listY, cy, scale),
                g.w * scale,
                g.listH * scale,
                canvasWidth,
                canvasHeight
        );

        String activeName = ConfigManager.getActiveConfigName();
        float curY = g.listY + scrollOffset;
        float btnSize = 26.0f;

        for (String name : configNames) {
            boolean isDefault = ConfigManager.DEFAULT_CONFIG.equals(name);
            boolean isActive = name.equals(activeName);
            boolean isRenaming = name.equals(renamingConfigName);
            boolean hovered = mx >= g.x && mx <= g.x + g.w && my >= curY && my <= curY + cardH
                    && my >= g.listY && my <= g.listY + g.listH;

            int cardBg = isActive
                    ? ColorUtil.lerp(
                            ColorUtil.argb((int) (14.0f * uiOpacity * alpha), 255, 255, 255),
                            ColorUtil.withAlpha(accent, 0.14f * alpha),
                            0.65f)
                    : ColorUtil.argb((int) ((hovered ? 18.0f : 10.0f) * uiOpacity * alpha), 255, 255, 255);
            int cardBorder = isActive
                    ? ColorUtil.withAlpha(accent, 0.90f * alpha)
                    : ColorUtil.argb((int) ((hovered ? 32 : 18) * alpha), 255, 255, 255);

            batch.drawRoundedRectWithOutline(
                    scaleX(g.x, cx, scale),
                    scaleY(curY, cy, scale),
                    g.w * scale,
                    cardH * scale,
                    14.0f * scale,
                    cardBg,
                    1.0f * scale,
                    cardBorder
            );

            // Позиции кнопок справа (включая Reset 0xE8B3 для каждого конфига, включая Default)
            ConfigCardButtons btns = computeConfigCardButtons(g, curY, cardH, isDefault);
            float btnY = btns.btnY;
            float leftMostBtnX = btns.leftMostX;

            renderConfigIconBtn(batch, IconAtlas.Icon.LOAD, btns.loadX, btnY, btnSize, btnSize,
                    isInside(mx, my, btns.loadX, btnY, btnSize, btnSize), isActive, cx, cy, scale, alpha, uiOpacity, accent);
            renderConfigIconBtn(batch, IconAtlas.Icon.SAVE, btns.saveX, btnY, btnSize, btnSize,
                    isInside(mx, my, btns.saveX, btnY, btnSize, btnSize), false, cx, cy, scale, alpha, uiOpacity, accent);
            if (!isDefault) {
                renderConfigIconBtn(batch, IconAtlas.Icon.RENAME, btns.renX, btnY, btnSize, btnSize,
                        isInside(mx, my, btns.renX, btnY, btnSize, btnSize), isRenaming, cx, cy, scale, alpha, uiOpacity, accent);
            }
            renderConfigIconBtn(batch, IconAtlas.Icon.RESET, btns.resetX, btnY, btnSize, btnSize,
                    isInside(mx, my, btns.resetX, btnY, btnSize, btnSize), false, cx, cy, scale, alpha, uiOpacity, 0xFFF59E0B);
            if (!isDefault) {
                renderConfigIconBtn(batch, IconAtlas.Icon.DELETE, btns.delX, btnY, btnSize, btnSize,
                        isInside(mx, my, btns.delX, btnY, btnSize, btnSize), false, cx, cy, scale, alpha, uiOpacity, 0xFFEF4444);
            }

            // Левая часть карточки (inline-переименование или название + бейджи)
            float textMaxW = Math.max(60.0f, leftMostBtnX - g.x - 28.0f);
            if (isRenaming) {
                float editW = Math.max(80.0f, textMaxW);
                batch.drawRoundedRectWithOutline(
                        scaleX(g.x + 14.0f, cx, scale),
                        scaleY(curY + 11.0f, cy, scale),
                        editW * scale,
                        30.0f * scale,
                        8.0f * scale,
                        ColorUtil.argb((int) (26 * alpha), 255, 255, 255),
                        1.0f * scale,
                        ColorUtil.withAlpha(accent, 0.92f * alpha)
                );
                String renDisplay = renameBuffer + (caretVisible ? "|" : "");
                String renTrim = FontRenderer.getDefaultFont() != null
                        ? FontRenderer.getDefaultFont().trimToWidth(renDisplay, editW - 16.0f, 13.0f, true)
                        : renDisplay;
                FontRenderer.drawText(
                        batch,
                        renTrim,
                        scaleX(g.x + 22.0f, cx, scale),
                        scaleY(curY + 19.0f, cy, scale),
                        13.0f * scale,
                        ColorUtil.withAlpha(TEXT_WHITE, alpha)
                );
            } else {
                String badgeText = isActive
                        ? (ConfigManager.isAutoSaveEnabled(name)
                                ? LocalizationManager.tr("configs.badge.auto_save")
                                : (ConfigManager.hasUnsavedChanges()
                                        ? LocalizationManager.tr("configs.badge.active_dirty")
                                        : LocalizationManager.tr("configs.badge.active")))
                        : (ConfigManager.isAutoSaveEnabled(name)
                                ? LocalizationManager.tr("configs.badge.auto_save")
                                : (isDefault ? LocalizationManager.tr("configs.badge.default") : ""));
                float badgePad = badgeText.isEmpty() ? 0.0f : (FontRenderer.getWidth(badgeText, 10.5f) + 22.0f);
                String nameTrim = FontRenderer.getDefaultFont() != null
                        ? FontRenderer.getDefaultFont().trimToWidth(name, Math.max(40.0f, textMaxW - badgePad), 14.0f, true)
                        : name;
                float nameX = g.x + 16.0f;
                FontRenderer.drawText(
                        batch,
                        nameTrim,
                        scaleX(nameX, cx, scale),
                        scaleY(curY + 18.0f, cy, scale),
                        14.0f * scale,
                        isActive ? ColorUtil.withAlpha(TEXT_WHITE, alpha) : ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
                );

                if (!badgeText.isEmpty()) {
                    float nameW = FontRenderer.getWidth(nameTrim, 14.0f);
                    float badgeX = nameX + nameW + 10.0f;
                    float badgeW = FontRenderer.getWidth(badgeText, 10.5f) + 14.0f;
                    float badgeH = 18.0f;
                    float badgeY = curY + 17.0f;
                    int badgeFill = isActive
                            ? ColorUtil.withAlpha(accent, 0.28f * alpha)
                            : ColorUtil.argb((int) (20 * alpha), 255, 255, 255);
                    int badgeBorder = isActive
                            ? ColorUtil.withAlpha(accent, 0.85f * alpha)
                            : ColorUtil.argb((int) (36 * alpha), 255, 255, 255);
                    batch.drawRoundedRectWithOutline(
                            scaleX(badgeX, cx, scale),
                            scaleY(badgeY, cy, scale),
                            badgeW * scale,
                            badgeH * scale,
                            6.0f * scale,
                            badgeFill,
                            1.0f * scale,
                            badgeBorder
                    );
                    FontRenderer.drawCenteredText(
                            batch,
                            badgeText,
                            scaleX(badgeX + badgeW * 0.5f, cx, scale),
                            scaleY(badgeY + 3.5f, cy, scale),
                            10.5f * scale,
                            ColorUtil.withAlpha(isActive ? accentHover : TEXT_SECONDARY, alpha)
                    );
                }
            }

            curY += cardH + 10.0f;
        }

        batch.endScissor();

        if (maxScroll > 0.0f) {
            float barH = Math.max(24.0f, g.listH * (g.listH / totalH));
            float barY = g.listY + (-scrollOffset / maxScroll) * (g.listH - barH);
            batch.drawRoundedRect(
                    scaleX(g.x + g.w - 3.0f, cx, scale),
                    scaleY(barY, cy, scale),
                    3.0f * scale,
                    barH * scale,
                    1.5f * scale,
                    ColorUtil.withAlpha(accent, 0.57f * alpha)
            );
        }

        if (configStatusTimer > 0.0f && !configStatusText.isEmpty()) {
            float stAlpha = Math.min(1.0f, configStatusTimer / 0.4f) * alpha;
            String trimmedStatus = FontRenderer.getDefaultFont() != null
                    ? FontRenderer.getDefaultFont().trimToWidth(configStatusText, g.w, 11.5f, true)
                    : configStatusText;
            FontRenderer.drawText(
                    batch,
                    trimmedStatus,
                    scaleX(g.x, cx, scale),
                    scaleY(zoneY + zoneH - 22.0f, cy, scale),
                    11.5f * scale,
                    ColorUtil.withAlpha(accentHover, 0.88f * stAlpha)
            );
        }
    }

    private record ConfirmModalGeometry(
            float boxX, float boxY, float boxW, float boxH,
            float yesX, float noX, float btnY, float btnW, float btnH
    ) {}

    private ConfirmModalGeometry computeConfirmModalGeometry(float zoneX, float zoneY, float zoneW, float zoneH) {
        float boxW = Math.min(350.0f, zoneW - 36.0f);
        float boxH = 148.0f;
        float boxX = zoneX + (zoneW - boxW) * 0.5f;
        float boxY = zoneY + (zoneH - boxH) * 0.5f;
        float btnW = 92.0f;
        float btnH = 28.0f;
        float gap = 12.0f;
        float totalBtnsW = btnW * 2.0f + gap;
        float yesX = boxX + (boxW - totalBtnsW) * 0.5f;
        float noX = yesX + btnW + gap;
        float btnY = boxY + boxH - 18.0f - btnH;
        return new ConfirmModalGeometry(boxX, boxY, boxW, boxH, yesX, noX, btnY, btnW, btnH);
    }

    private String getConfirmMessage() {
        if (pendingConfirmAction == ConfirmAction.OVERWRITE_CONFIG) {
            return LocalizationManager.tr("modal.overwrite", pendingConfirmTarget);
        }
        if (pendingConfirmAction == ConfirmAction.RESET_CONFIG) {
            return LocalizationManager.tr("modal.reset", pendingConfirmTarget);
        }
        return LocalizationManager.tr("modal.switch", ConfigManager.getActiveConfigName(), pendingConfirmTarget);
    }

    private List<String> wrapModalText(String text, float maxW, float fontSize) {
        List<String> lines = new ArrayList<>(4);
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String candidate = cur.isEmpty() ? w : (cur + " " + w);
            if (FontRenderer.getWidth(candidate, fontSize) <= maxW || cur.isEmpty()) {
                cur.setLength(0);
                cur.append(candidate);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(w);
            }
        }
        if (!cur.isEmpty()) {
            lines.add(cur.toString());
        }
        return lines;
    }

    private void renderConfirmModal(RenderBatch2D batch, float zoneX, float zoneY, float zoneW, float zoneH,
                                    float panelRadius, float cx, float cy, float scale, float modalAlpha,
                                    float mx, float my) {
        if (modalAlpha <= 0.01f) {
            return;
        }
        batch.drawRoundedRect(
                scaleX(zoneX, cx, scale),
                scaleY(zoneY, cy, scale),
                zoneW * scale,
                zoneH * scale,
                panelRadius * scale,
                ColorUtil.argb((int) (145.0f * modalAlpha), 5, 7, 11)
        );

        ConfirmModalGeometry m = computeConfirmModalGeometry(zoneX, zoneY, zoneW, zoneH);
        int accent = ClientSettings.getAccentColor();
        float uiOpacity = ClientSettings.getUiOpacity();

        batch.drawShadow(
                scaleX(m.boxX, cx, scale),
                scaleY(m.boxY, cy, scale),
                m.boxW * scale,
                m.boxH * scale,
                16.0f * scale,
                18.0f * scale,
                ColorUtil.argb((int) (140.0f * modalAlpha), 0, 0, 0)
        );
        batch.drawRoundedRectWithOutline(
                scaleX(m.boxX, cx, scale),
                scaleY(m.boxY, cy, scale),
                m.boxW * scale,
                m.boxH * scale,
                16.0f * scale,
                ColorUtil.argb((int) (Math.clamp(228.0f * uiOpacity + 24.0f, 180.0f, 248.0f) * modalAlpha), 14, 17, 23),
                1.0f * scale,
                ColorUtil.withAlpha(accent, 0.78f * modalAlpha)
        );

        float textSize = 12.5f;
        List<String> lines = wrapModalText(getConfirmMessage(), m.boxW - 36.0f, textSize);
        float lineH = FontRenderer.getHeight(textSize) + 4.0f;
        float textAreaTop = m.boxY + 22.0f;
        float textAreaH = (m.btnY - 12.0f) - textAreaTop;
        float curTextY = textAreaTop + Math.max(0.0f, (textAreaH - lines.size() * lineH) * 0.5f);

        for (String line : lines) {
            FontRenderer.drawCenteredText(
                    batch,
                    line,
                    scaleX(m.boxX + m.boxW * 0.5f, cx, scale),
                    scaleY(curTextY, cy, scale),
                    textSize * scale,
                    ColorUtil.withAlpha(TEXT_WHITE, modalAlpha)
            );
            curTextY += lineH;
        }

        boolean yesHover = isInside(mx, my, m.yesX, m.btnY, m.btnW, m.btnH);
        int yesFill = ColorUtil.withAlpha(accent, (yesHover ? 0.96f : 0.82f) * modalAlpha);
        int yesStroke = ColorUtil.argb((int) ((yesHover ? 200 : 120) * modalAlpha), 255, 255, 255);
        batch.drawRoundedRectWithOutline(
                scaleX(m.yesX, cx, scale),
                scaleY(m.btnY, cy, scale),
                m.btnW * scale,
                m.btnH * scale,
                8.0f * scale,
                yesFill,
                1.0f * scale,
                yesStroke
        );
        float btnTextSize = 12.0f;
        float btnTextY = m.btnY + (m.btnH - FontRenderer.getHeight(btnTextSize)) * 0.5f;
        FontRenderer.drawCenteredText(
                batch,
                LocalizationManager.tr("modal.yes"),
                scaleX(m.yesX + m.btnW * 0.5f, cx, scale),
                scaleY(btnTextY, cy, scale),
                btnTextSize * scale,
                ColorUtil.withAlpha(TEXT_WHITE, modalAlpha)
        );

        boolean noHover = isInside(mx, my, m.noX, m.btnY, m.btnW, m.btnH);
        int noFill = ColorUtil.argb((int) ((noHover ? 34 : 18) * modalAlpha), 255, 255, 255);
        int noStroke = ColorUtil.argb((int) ((noHover ? 85 : 42) * modalAlpha), 255, 255, 255);
        batch.drawRoundedRectWithOutline(
                scaleX(m.noX, cx, scale),
                scaleY(m.btnY, cy, scale),
                m.btnW * scale,
                m.btnH * scale,
                8.0f * scale,
                noFill,
                1.0f * scale,
                noStroke
        );
        FontRenderer.drawCenteredText(
                batch,
                LocalizationManager.tr("modal.no"),
                scaleX(m.noX + m.btnW * 0.5f, cx, scale),
                scaleY(btnTextY, cy, scale),
                btnTextSize * scale,
                ColorUtil.withAlpha(noHover ? TEXT_WHITE : TEXT_SECONDARY, modalAlpha)
        );
    }

    private void renderConfigIconBtn(RenderBatch2D batch, IconAtlas.Icon icon, float x, float y, float w, float h,
                                     boolean hovered, boolean active, float cx, float cy, float scale, float alpha,
                                     float uiOpacity, int tintColor) {
        int bg = (hovered || active)
                ? ColorUtil.withAlpha(tintColor, (active ? 0.30f : 0.22f) * alpha)
                : ColorUtil.argb((int) (14.0f * uiOpacity * alpha), 255, 255, 255);
        int border = (hovered || active)
                ? ColorUtil.withAlpha(tintColor, 0.86f * alpha)
                : ColorUtil.argb((int) (26 * alpha), 255, 255, 255);
        batch.drawRoundedRectWithOutline(
                scaleX(x, cx, scale),
                scaleY(y, cy, scale),
                w * scale,
                h * scale,
                8.0f * scale,
                bg,
                1.0f * scale,
                border
        );
        IconAtlas.drawIcon(
                batch,
                icon,
                scaleX(x + w * 0.5f, cx, scale),
                scaleY(y + h * 0.5f, cy, scale),
                13.0f * scale,
                ColorUtil.withAlpha((hovered || active) ? TEXT_WHITE : TEXT_SECONDARY, alpha)
        );
    }

    private record SettingsGeometry(
            float listX, float listY, float listW, float listH,
            float accentCardY, float accentCardH, float headerH, float drawerFullH,
            float presetsStartX, float presetsY, float swatchSize, float swatchGap,
            float customX, float customY, float customW, float customH,
            float hueX, float hueY, float hueW, float hueH,
            float satSliderY, float briSliderY,
            float motionCardY, float motionCardH, float slidersStartY,
            float langCardY, float langCardH
    ) {}

    private record LangSegment(
            LocalizationManager.LanguageMode mode,
            String label,
            float x, float y, float w, float h
    ) {}

    private record LangTrackLayout(
            float trackX, float trackY, float trackW, float trackH,
            LangSegment[] segments
    ) {}

    private LangTrackLayout computeLanguageTrackLayout(SettingsGeometry g) {
        String title = LocalizationManager.tr("settings.language");
        float titleRight = g.listX + 16.0f + FontRenderer.getWidth(title, 14.0f) + 12.0f;
        float rightEdge = g.listX + g.listW - 14.0f;
        float trackPad = 3.0f;
        float gap = 3.0f;
        float segH = 22.0f;
        float trackH = segH + trackPad * 2.0f;
        float trackY = g.langCardY + (g.langCardH - trackH) * 0.5f;
        float segY = trackY + trackPad;

        LocalizationManager.LanguageMode[] modes = {
                LocalizationManager.LanguageMode.RU,
                LocalizationManager.LanguageMode.EN
        };
        String[] labels = {
                "Русский",
                "English"
        };
        float[] widths = new float[2];
        float rawSegsW = 0.0f;
        for (int i = 0; i < 2; i++) {
            widths[i] = Math.max(58.0f, FontRenderer.getWidth(labels[i], 11.5f) + 20.0f);
            rawSegsW += widths[i];
        }
        float availTrackW = Math.max(100.0f, rightEdge - titleRight);
        float maxSegsW = Math.max(88.0f, availTrackW - trackPad * 2.0f - gap);
        if (rawSegsW > maxSegsW) {
            float shrink = maxSegsW / rawSegsW;
            rawSegsW = 0.0f;
            for (int i = 0; i < 2; i++) {
                widths[i] *= shrink;
                rawSegsW += widths[i];
            }
        }
        float trackW = rawSegsW + trackPad * 2.0f + gap;
        float trackX = rightEdge - trackW;

        LangSegment[] segments = new LangSegment[2];
        float curX = trackX + trackPad;
        for (int i = 0; i < 2; i++) {
            segments[i] = new LangSegment(modes[i], labels[i], curX, segY, widths[i], segH);
            curX += widths[i] + gap;
        }
        return new LangTrackLayout(trackX, trackY, trackW, trackH, segments);
    }

    private SettingsGeometry computeSettingsGeometry(float zoneX, float zoneY, float zoneW, float zoneH) {
        float listX = zoneX + 24.0f;
        float listY = zoneY + 22.0f + 46.0f;
        float listW = zoneW - 48.0f;
        float listH = zoneH - (listY - zoneY) - 16.0f;

        float innerX = listX + 16.0f;
        float innerW = Math.max(120.0f, listW - 32.0f);

        float accentCardY = listY + scrollOffset;
        float headerH = 44.0f;

        float customW = 28.0f;
        float customH = 20.0f;
        float customX = listX + listW - 16.0f - customW;
        float customY = accentCardY + 12.0f;

        String hex = ClientSettings.getAccentColorSetting().getHex();
        float hexW = FontRenderer.getWidth(hex, 11.0f);
        float hexRight = customX - hexW - 18.0f;
        float labelRight = innerX + FontRenderer.getWidth(LocalizationManager.settingName("Accent Color"), 14.0f) + 12.0f;
        float availPresetsW = Math.max(70.0f, hexRight - labelRight);

        int presetCount = ClientSettings.ACCENT_PRESETS.size();
        float swatchGap = 5.0f;
        float swatchSize = Math.clamp((availPresetsW - swatchGap * (presetCount - 1)) / presetCount, 12.0f, 18.0f);
        float presetsTotalW = presetCount * swatchSize + (presetCount - 1) * swatchGap;
        float presetsStartX = hexRight - presetsTotalW;
        float presetsY = accentCardY + (headerH - swatchSize) * 0.5f;

        float hueX = innerX + 4.0f;
        float hueY = accentCardY + headerH + 10.0f;
        float hueW = Math.max(60.0f, innerW - 8.0f);
        float hueH = 12.0f;
        float satSliderY = hueY + hueH + 10.0f;
        float briSliderY = satSliderY + 42.0f;
        float drawerFullH = (briSliderY + 42.0f + 6.0f) - (accentCardY + headerH);

        float accentCardH = headerH + drawerFullH * accentPickerAnim;

        float motionCardY = accentCardY + accentCardH + 10.0f;
        float slidersStartY = motionCardY + 38.0f;
        float motionCardH = 38.0f + 42.0f * 4.0f + 8.0f;

        float langCardY = motionCardY + motionCardH + 10.0f;
        float langCardH = 44.0f;

        return new SettingsGeometry(
                listX, listY, listW, listH,
                accentCardY, accentCardH, headerH, drawerFullH,
                presetsStartX, presetsY, swatchSize, swatchGap,
                customX, customY, customW, customH,
                hueX, hueY, hueW, hueH,
                satSliderY, briSliderY,
                motionCardY, motionCardH, slidersStartY,
                langCardY, langCardH
        );
    }

    private void renderSettingsTab(RenderBatch2D batch, float zoneX, float zoneY, float zoneW, float zoneH,
                                   float cx, float cy, float scale, float alpha, float mx, float my) {
        float headerX = zoneX + 24.0f;
        float headerY = zoneY + 22.0f;

        FontRenderer.drawText(
                batch,
                LocalizationManager.tabLabel("Settings"),
                scaleX(headerX, cx, scale),
                scaleY(headerY, cy, scale),
                22.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );
        batch.drawRect(
                scaleX(headerX, cx, scale),
                scaleY(headerY + 34.0f, cy, scale),
                (zoneW - 48.0f) * scale,
                1.0f * scale,
                ColorUtil.argb((int) (18 * alpha), 255, 255, 255)
        );

        SettingsGeometry g = computeSettingsGeometry(zoneX, zoneY, zoneW, zoneH);
        float totalH = g.accentCardH + 10.0f + g.motionCardH + 10.0f + g.langCardH + 10.0f;
        maxScroll = Math.max(0.0f, totalH - g.listH);
        clampScrollBounds();

        batch.beginScissor(
                scaleX(g.listX, cx, scale),
                scaleY(g.listY, cy, scale),
                g.listW * scale,
                g.listH * scale,
                canvasWidth,
                canvasHeight
        );

        int accent = ClientSettings.getAccentColor();
        float uiOpacity = ClientSettings.getUiOpacity();
        int cardBg = ColorUtil.argb((int) (12.0f * uiOpacity * alpha), 255, 255, 255);
        int cardBorder = ColorUtil.argb((int) (20 * alpha), 255, 255, 255);

        // Карточка 1: Компактный однострочный Accent Color + выпадающая палитра
        batch.drawRoundedRectWithOutline(
                scaleX(g.listX, cx, scale),
                scaleY(g.accentCardY, cy, scale),
                g.listW * scale,
                g.accentCardH * scale,
                14.0f * scale,
                cardBg,
                1.0f * scale,
                accentPickerAnim > 0.05f ? ColorUtil.lerp(cardBorder, ColorUtil.withAlpha(accent, 0.45f * alpha), accentPickerAnim) : cardBorder
        );

        FontRenderer.drawText(
                batch,
                LocalizationManager.settingName("Accent Color"),
                scaleX(g.listX + 16.0f, cx, scale),
                scaleY(g.accentCardY + 15.0f, cy, scale),
                14.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );

        // Пресеты цвета Nebula в одной строке справа
        float curPresetX = g.presetsStartX;
        for (ClientSettings.AccentPreset preset : ClientSettings.ACCENT_PRESETS) {
            boolean selected = (accent & 0x00FFFFFF) == (preset.argb() & 0x00FFFFFF);
            boolean hovered = isInside(mx, my, curPresetX, g.presetsY, g.swatchSize, g.swatchSize);
            int swBorder = selected
                    ? ColorUtil.withAlpha(TEXT_WHITE, alpha)
                    : (hovered ? ColorUtil.argb((int) (145 * alpha), 255, 255, 255) : ColorUtil.argb((int) (35 * alpha), 255, 255, 255));
            batch.drawRoundedRectWithOutline(
                    scaleX(curPresetX, cx, scale),
                    scaleY(g.presetsY, cy, scale),
                    g.swatchSize * scale,
                    g.swatchSize * scale,
                    5.0f * scale,
                    ColorUtil.withAlpha(preset.argb(), alpha),
                    selected ? 1.5f * scale : 1.0f * scale,
                    swBorder
            );
            curPresetX += g.swatchSize + g.swatchGap;
        }

        // Текущий HEX-код и кнопка-свотч, раскрывающая компактную HSB-палитру
        String hex = ClientSettings.getAccentColorSetting().getHex();
        float hexW = FontRenderer.getWidth(hex, 11.0f);
        FontRenderer.drawText(
                batch,
                hex,
                scaleX(g.customX - hexW - 8.0f, cx, scale),
                scaleY(g.accentCardY + 16.0f, cy, scale),
                11.0f * scale,
                ColorUtil.withAlpha(TEXT_MUTED, alpha)
        );

        boolean customHover = isInside(mx, my, g.customX, g.customY, g.customW, g.customH);
        int customBorder = accentPickerExpanded || customHover
                ? ColorUtil.withAlpha(TEXT_WHITE, alpha)
                : ColorUtil.argb((int) (75 * alpha), 255, 255, 255);
        batch.drawRoundedRectWithOutline(
                scaleX(g.customX, cx, scale),
                scaleY(g.customY, cy, scale),
                g.customW * scale,
                g.customH * scale,
                6.0f * scale,
                ColorUtil.withAlpha(accent, alpha),
                1.2f * scale,
                customBorder
        );

        int r = (accent >> 16) & 0xFF;
        int gr = (accent >> 8) & 0xFF;
        int b = accent & 0xFF;
        float luminance = (0.299f * r + 0.587f * gr + 0.114f * b) / 255.0f;
        int paletteIconColor = ColorUtil.withAlpha(luminance > 0.65f ? 0xFF111827 : 0xFFFFFFFF, 0.92f * alpha);
        IconAtlas.drawIcon(
                batch,
                IconAtlas.Icon.PALETTE,
                scaleX(g.customX + g.customW * 0.5f, cx, scale),
                scaleY(g.customY + g.customH * 0.5f, cy, scale),
                12.0f * scale,
                paletteIconColor
        );

        // Выпадающий ящик компактной HSB-палитры (тонкий Hue + Saturation + Brightness)
        if (accentPickerAnim > 0.01f) {
            float drawerY = g.accentCardY + g.headerH;
            float drawerH = g.drawerFullH * accentPickerAnim;

            batch.drawRect(
                    scaleX(g.listX + 14.0f, cx, scale),
                    scaleY(drawerY, cy, scale),
                    (g.listW - 28.0f) * scale,
                    1.0f * scale,
                    ColorUtil.argb((int) (16 * alpha * accentPickerAnim), 255, 255, 255)
            );

            batch.beginScissor(
                    scaleX(g.listX, cx, scale),
                    scaleY(drawerY, cy, scale),
                    g.listW * scale,
                    drawerH * scale,
                    canvasWidth,
                    canvasHeight
            );

            float drawerAlpha = alpha * accentPickerAnim;

            // Тонкая полоса оттенка (Hue)
            int[] hueStops = {
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
            };
            float segW = g.hueW / 6.0f;
            for (int i = 0; i < 6; i++) {
                int cLeft = ColorUtil.withAlpha(hueStops[i], drawerAlpha);
                int cRight = ColorUtil.withAlpha(hueStops[i + 1], drawerAlpha);
                batch.drawGradientRect(
                        scaleX(g.hueX + segW * i, cx, scale),
                        scaleY(g.hueY, cy, scale),
                        segW * scale + 0.5f,
                        g.hueH * scale,
                        cLeft, cRight, cRight, cLeft
                );
            }
            batch.drawOutlineRoundedRect(
                    scaleX(g.hueX, cx, scale),
                    scaleY(g.hueY, cy, scale),
                    g.hueW * scale,
                    g.hueH * scale,
                    3.0f * scale,
                    1.0f * scale,
                    ColorUtil.argb((int) (40 * drawerAlpha), 255, 255, 255)
            );

            float hueKnobX = g.hueX + ClientSettings.getAccentHue() * g.hueW;
            batch.drawRoundedRectWithOutline(
                    scaleX(hueKnobX - 3.5f, cx, scale),
                    scaleY(g.hueY - 2.0f, cy, scale),
                    7.0f * scale,
                    (g.hueH + 4.0f) * scale,
                    3.5f * scale,
                    ColorUtil.withAlpha(TEXT_WHITE, drawerAlpha),
                    1.0f * scale,
                    ColorUtil.argb((int) (110 * drawerAlpha), 0, 0, 0)
            );

            renderSliderSetting(batch, ClientSettings.getAccentSaturationSetting(),
                    g.listX + 16.0f, g.satSliderY, g.listW - 32.0f, cx, cy, scale, drawerAlpha);
            renderSliderSetting(batch, ClientSettings.getAccentBrightnessSetting(),
                    g.listX + 16.0f, g.briSliderY, g.listW - 32.0f, cx, cy, scale, drawerAlpha);

            batch.endScissor();
        }

        // Карточка 2: Interface & Motion (ClickGUI Scale, UI Opacity, Blur Strength, Animation Speed)
        batch.drawRoundedRectWithOutline(
                scaleX(g.listX, cx, scale),
                scaleY(g.motionCardY, cy, scale),
                g.listW * scale,
                g.motionCardH * scale,
                14.0f * scale,
                cardBg,
                1.0f * scale,
                cardBorder
        );
        FontRenderer.drawText(
                batch,
                LocalizationManager.tr("settings.interface_motion"),
                scaleX(g.listX + 16.0f, cx, scale),
                scaleY(g.motionCardY + 12.0f, cy, scale),
                14.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );

        float curSetY = g.slidersStartY;
        SliderSetting[] globalSliders = {
                ClientSettings.getClickGuiScaleSetting(),
                ClientSettings.getUiOpacitySetting(),
                ClientSettings.getBlurStrengthSetting(),
                ClientSettings.getAnimationSpeedSetting()
        };
        for (SliderSetting ss : globalSliders) {
            renderSliderSetting(batch, ss, g.listX + 16.0f, curSetY, g.listW - 32.0f, cx, cy, scale, alpha);
            curSetY += 42.0f;
        }

        // Карточка 3: Язык интерфейса (Русский / English)
        batch.drawRoundedRectWithOutline(
                scaleX(g.listX, cx, scale),
                scaleY(g.langCardY, cy, scale),
                g.listW * scale,
                g.langCardH * scale,
                14.0f * scale,
                cardBg,
                1.0f * scale,
                cardBorder
        );
        FontRenderer.drawText(
                batch,
                LocalizationManager.tr("settings.language"),
                scaleX(g.listX + 16.0f, cx, scale),
                scaleY(g.langCardY + 15.0f, cy, scale),
                14.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );

        LangTrackLayout langLayout = computeLanguageTrackLayout(g);
        batch.drawRoundedRectWithOutline(
                scaleX(langLayout.trackX, cx, scale),
                scaleY(langLayout.trackY, cy, scale),
                langLayout.trackW * scale,
                langLayout.trackH * scale,
                8.0f * scale,
                ColorUtil.argb((int) (10.0f * uiOpacity * alpha), 255, 255, 255),
                1.0f * scale,
                ColorUtil.argb((int) (24 * alpha), 255, 255, 255)
        );

        LocalizationManager.LanguageMode activeLang = LocalizationManager.getLanguageMode();
        for (LangSegment seg : langLayout.segments) {
            boolean selected = activeLang == seg.mode;
            boolean hovered = isInside(mx, my, seg.x, seg.y, seg.w, seg.h);
            int segFill = selected
                    ? ColorUtil.withAlpha(accent, (hovered ? 0.42f : 0.30f) * alpha)
                    : ColorUtil.argb((int) ((hovered ? 22 : 8) * alpha), 255, 255, 255);
            int segOutline = selected
                    ? ColorUtil.withAlpha(accent, (hovered ? 0.98f : 0.85f) * alpha)
                    : ColorUtil.argb((int) ((hovered ? 45 : 0) * alpha), 255, 255, 255);
            batch.drawRoundedRectWithOutline(
                    scaleX(seg.x, cx, scale),
                    scaleY(seg.y, cy, scale),
                    seg.w * scale,
                    seg.h * scale,
                    6.0f * scale,
                    segFill,
                    1.0f * scale,
                    segOutline
            );

            float lblSize = 11.5f;
            float maxLblW = Math.max(20.0f, seg.w - 8.0f);
            float rawLblW = FontRenderer.getWidth(seg.label, lblSize);
            if (rawLblW > maxLblW && rawLblW > 1.0f) {
                lblSize = Math.max(9.0f, lblSize * (maxLblW / rawLblW));
            }
            float lblY = seg.y + (seg.h - FontRenderer.getHeight(lblSize)) * 0.5f;
            FontRenderer.drawCenteredText(
                    batch,
                    seg.label,
                    scaleX(seg.x + seg.w * 0.5f, cx, scale),
                    scaleY(lblY, cy, scale),
                    lblSize * scale,
                    ColorUtil.withAlpha((selected || hovered) ? TEXT_WHITE : TEXT_SECONDARY, alpha)
            );
        }

        batch.endScissor();

        if (maxScroll > 0.0f) {
            float barH = Math.max(24.0f, g.listH * (g.listH / totalH));
            float barY = g.listY + (-scrollOffset / maxScroll) * (g.listH - barH);
            batch.drawRoundedRect(
                    scaleX(g.listX + g.listW - 3.0f, cx, scale),
                    scaleY(barY, cy, scale),
                    3.0f * scale,
                    barH * scale,
                    1.5f * scale,
                    ColorUtil.withAlpha(accent, 0.57f * alpha)
            );
        }
    }

    private void updateActiveColorPicker(float mx, float winX, float winY, float winW, float winH, float sideW) {
        if (currentTab != UiTab.SETTINGS || !draggingColorHue) return;
        float zoneX = winX + sideW + CONTENT_PAD;
        float zoneY = winY + CONTENT_PAD;
        float zoneW = winW - sideW - CONTENT_PAD * 2.0f;
        float zoneH = winH - CONTENT_PAD * 2.0f;
        SettingsGeometry g = computeSettingsGeometry(zoneX, zoneY, zoneW, zoneH);

        float hue = Math.clamp((mx - g.hueX) / Math.max(1.0f, g.hueW), 0.0f, 1.0f);
        ClientSettings.setAccentHsb(hue, ClientSettings.getAccentSat(), ClientSettings.getAccentBri());
    }

    private void renderModulesTab(RenderBatch2D batch, float zoneX, float zoneY, float zoneW, float zoneH,
                                  float cx, float cy, float scale, float alpha, float mx, float my) {
        float headerX = zoneX + 24.0f;
        float headerY = zoneY + 22.0f;

        FontRenderer.drawText(
                batch,
                LocalizationManager.tabLabel(currentTab.label),
                scaleX(headerX, cx, scale),
                scaleY(headerY, cy, scale),
                22.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );

        batch.drawRect(
                scaleX(headerX, cx, scale),
                scaleY(headerY + 34.0f, cy, scale),
                (zoneW - 48.0f) * scale,
                1.0f * scale,
                ColorUtil.argb((int) (18 * alpha), 255, 255, 255)
        );

        float listX = headerX;
        float listY = headerY + 46.0f;
        float listW = zoneW - 48.0f;
        float listH = zoneH - (listY - zoneY) - 16.0f;

        List<VisualModule> modules = getModulesForCurrentTab();
        float totalH = 0.0f;
        for (VisualModule m : modules) {
            float expProgress = m.getExpandProgress();
            float cardH = 54.0f;
            if (expProgress > 0.001f) {
                cardH += computeSettingsHeight(m) * expProgress;
            }
            totalH += cardH + 10.0f;
        }
        maxScroll = Math.max(0.0f, totalH - listH);
        clampScrollBounds();

        batch.beginScissor(
                scaleX(listX, cx, scale),
                scaleY(listY, cy, scale),
                listW * scale,
                listH * scale,
                canvasWidth,
                canvasHeight
        );

        float curY = listY + scrollOffset;
        float listBottom = listY + listH;
        boolean hasStaticCards = false;
        boolean hasAnimatingCards = false;
        for (VisualModule m : modules) {
            float expProgress = m.getExpandProgress();
            float cardH = 54.0f + (expProgress > 0.001f ? computeSettingsHeight(m) * expProgress : 0.0f);
            if (curY + cardH < listY || curY > listBottom) {
                m.getHoverAnim().setTarget(0.0f);
            } else {
                boolean hovered = mx >= listX && mx <= listX + listW && my >= curY && my <= curY + 54.0f;
                m.getHoverAnim().setTarget(hovered ? 1.0f : 0.0f);
                if (expProgress > 0.001f && expProgress < 0.999f) {
                    hasAnimatingCards = true;
                } else {
                    hasStaticCards = true;
                }
            }
            curY += cardH + 10.0f;
        }

        // Пакетный проход без лишних flush(): сначала все фигуры (whiteTex), затем иконки (IconAtlas), затем текст (MSDF)
        if (hasStaticCards) {
            float uiOpacity = ClientSettings.getUiOpacity();
            curY = listY + scrollOffset;
            for (VisualModule m : modules) {
                float expProgress = m.getExpandProgress();
                float cardH = 54.0f + (expProgress > 0.001f ? computeSettingsHeight(m) * expProgress : 0.0f);
                if (curY + cardH >= listY && curY <= listBottom && !(expProgress > 0.001f && expProgress < 0.999f)) {
                    renderModuleCardShapes(batch, m, listX, curY, listW, cardH, expProgress, listY, listBottom,
                            cx, cy, scale, alpha, uiOpacity, mx, my, expProgress >= 0.999f);
                }
                curY += cardH + 10.0f;
            }

            curY = listY + scrollOffset;
            for (VisualModule m : modules) {
                float expProgress = m.getExpandProgress();
                float cardH = 54.0f + (expProgress > 0.001f ? computeSettingsHeight(m) * expProgress : 0.0f);
                if (curY + cardH >= listY && curY <= listBottom && !(expProgress > 0.001f && expProgress < 0.999f)) {
                    renderModuleCardIcons(batch, m, listX, curY, listW, expProgress, cx, cy, scale, alpha, mx, my);
                }
                curY += cardH + 10.0f;
            }

            curY = listY + scrollOffset;
            for (VisualModule m : modules) {
                float expProgress = m.getExpandProgress();
                float cardH = 54.0f + (expProgress > 0.001f ? computeSettingsHeight(m) * expProgress : 0.0f);
                if (curY + cardH >= listY && curY <= listBottom && !(expProgress > 0.001f && expProgress < 0.999f)) {
                    renderModuleCardTexts(batch, m, listX, curY, listW, expProgress, listY, listBottom,
                            cx, cy, scale, alpha, expProgress >= 0.999f);
                }
                curY += cardH + 10.0f;
            }
        }

        // Карточки в активной анимации аккордеона требуют индивидуального scissor-клиппинга
        if (hasAnimatingCards) {
            curY = listY + scrollOffset;
            for (VisualModule m : modules) {
                float expProgress = m.getExpandProgress();
                float cardH = 54.0f + (expProgress > 0.001f ? computeSettingsHeight(m) * expProgress : 0.0f);
                if (curY + cardH >= listY && curY <= listBottom && expProgress > 0.001f && expProgress < 0.999f) {
                    renderModuleCard(batch, m, listX, curY, listW, cardH, expProgress, listY, listBottom,
                            cx, cy, scale, alpha, mx, my);
                }
                curY += cardH + 10.0f;
            }
        }

        batch.endScissor();

        if (maxScroll > 0.0f) {
            float barH = Math.max(24.0f, listH * (listH / totalH));
            float barY = listY + (-scrollOffset / maxScroll) * (listH - barH);
            batch.drawRoundedRect(
                    scaleX(listX + listW - 3.0f, cx, scale),
                    scaleY(barY, cy, scale),
                    3.0f * scale,
                    barH * scale,
                    1.5f * scale,
                    ColorUtil.withAlpha(ClientSettings.getAccentColor(), 0.57f * alpha)
            );
        }
    }

    private String getTrimmedDescription(VisualModule m, float maxWidth) {
        String raw = LocalizationManager.moduleDescription(m.getDescription());
        MsdfFont font = FontRenderer.getDefaultFont();
        if (font == null) {
            return raw;
        }
        CachedDescTrim cached = descTrimCache.get(m);
        if (cached != null && cached.font == font && cached.rawDesc == raw && Math.abs(cached.maxWidth - maxWidth) < 0.25f) {
            return cached.trimmed;
        }
        String trimmed = font.trimToWidth(raw, maxWidth, 11.0f, true);
        descTrimCache.put(m, new CachedDescTrim(maxWidth, font, raw, trimmed));
        return trimmed;
    }

    private float getSettingRowHeight(Setting<?> s) {
        if (s instanceof BooleanSetting) return 32.0f;
        if (s instanceof SliderSetting) return 42.0f;
        return 34.0f;
    }

    private void renderModuleCard(RenderBatch2D batch, VisualModule m, float x, float y, float w, float h, float expProgress,
                                  float clipTop, float clipBottom,
                                  float cx, float cy, float scale, float alpha, float mx, float my) {
        float uiOpacity = ClientSettings.getUiOpacity();
        renderModuleCardShapes(batch, m, x, y, w, h, expProgress, clipTop, clipBottom, cx, cy, scale, alpha, uiOpacity, mx, my, false);
        renderModuleCardIcons(batch, m, x, y, w, expProgress, cx, cy, scale, alpha, mx, my);
        renderModuleCardTexts(batch, m, x, y, w, expProgress, clipTop, clipBottom, cx, cy, scale, alpha, false);

        float eased = Math.clamp(expProgress, 0.0f, 1.0f);
        float contentAlpha = Math.clamp((eased - 0.12f) / 0.75f, 0.0f, 1.0f);
        if (eased <= 0.001f || contentAlpha <= 0.002f) {
            return;
        }

        float headH = 54.0f;
        float drawerY = y + headH;
        float drawerH = h - headH;
        float innerAlpha = alpha * contentAlpha;
        float slideOffsetY = (1.0f - eased) * -8.0f;

        batch.beginScissor(
                scaleX(x, cx, scale),
                scaleY(drawerY, cy, scale),
                w * scale,
                Math.max(0.0f, drawerH) * scale,
                canvasWidth,
                canvasHeight
        );
        renderModuleSettingsShapes(batch, m, x, drawerY + 8.0f + slideOffsetY, w, clipTop, clipBottom, cx, cy, scale, innerAlpha, mx, my);
        renderModuleSettingsTexts(batch, m, x, drawerY + 8.0f + slideOffsetY, w, clipTop, clipBottom, cx, cy, scale, innerAlpha);
        batch.endScissor();
    }

    private void renderModuleCardShapes(RenderBatch2D batch, VisualModule m, float x, float y, float w, float h, float expProgress,
                                        float clipTop, float clipBottom,
                                        float cx, float cy, float scale, float alpha, float uiOpacity,
                                        float mx, float my, boolean includeDrawerSettings) {
        float hAnim = m.getHoverAnim().getValue();
        float tAnim = m.getToggleAnim().getValue();

        int cardBg = ColorUtil.lerp(
                ColorUtil.argb((int) (10.0f * uiOpacity * alpha), 255, 255, 255),
                ColorUtil.argb((int) (20.0f * uiOpacity * alpha), 255, 255, 255),
                hAnim
        );
        int borderCol = ColorUtil.lerp(
                ColorUtil.argb((int) (18 * alpha), 255, 255, 255),
                ColorUtil.withAlpha(ClientSettings.getAccentColor(), 0.57f * alpha),
                Math.max(hAnim * 0.35f, tAnim * 0.85f)
        );

        batch.drawRoundedRectWithOutline(
                scaleX(x, cx, scale),
                scaleY(y, cy, scale),
                w * scale,
                h * scale,
                14.0f * scale,
                cardBg,
                1.0f * scale,
                borderCol
        );

        float swW = 32.0f;
        float swH = 18.0f;
        float swX = x + w - swW - 16.0f;
        float swY = y + 18.0f;
        renderToggleSwitch(batch, swX, swY, swW, swH, tAnim, cx, cy, scale, alpha);

        if (!m.getSettings().isEmpty()) {
            float chevX = swX - 28.0f;
            float chevY = y + 17.0f;
            boolean chevHover = mx >= chevX && mx <= chevX + 20.0f && my >= chevY && my <= chevY + 20.0f;
            batch.drawRoundedRect(
                    scaleX(chevX, cx, scale),
                    scaleY(chevY, cy, scale),
                    20.0f * scale,
                    20.0f * scale,
                    6.0f * scale,
                    chevHover ? ColorUtil.argb((int) (28 * alpha), 255, 255, 255) : ColorUtil.argb((int) (12 * alpha), 255, 255, 255)
            );
        }

        float eased = Math.clamp(expProgress, 0.0f, 1.0f);
        if (eased <= 0.001f) {
            return;
        }
        float drawerY = y + 54.0f;
        float contentAlpha = Math.clamp((eased - 0.12f) / 0.75f, 0.0f, 1.0f);
        float innerAlpha = alpha * contentAlpha;
        batch.drawRect(
                scaleX(x + 14.0f, cx, scale),
                scaleY(drawerY, cy, scale),
                (w - 28.0f) * scale,
                1.0f * scale,
                ColorUtil.argb((int) (16 * innerAlpha), 255, 255, 255)
        );
        if (includeDrawerSettings && contentAlpha > 0.002f) {
            float slideOffsetY = (1.0f - eased) * -8.0f;
            renderModuleSettingsShapes(batch, m, x, drawerY + 8.0f + slideOffsetY, w, clipTop, clipBottom, cx, cy, scale, innerAlpha, mx, my);
        }
    }

    private void renderModuleCardIcons(RenderBatch2D batch, VisualModule m, float x, float y, float w, float expProgress,
                                       float cx, float cy, float scale, float alpha, float mx, float my) {
        if (m.getSettings().isEmpty()) {
            return;
        }
        float swX = x + w - 32.0f - 16.0f;
        float chevX = swX - 28.0f;
        float chevY = y + 17.0f;
        boolean chevHover = mx >= chevX && mx <= chevX + 20.0f && my >= chevY && my <= chevY + 20.0f;

        float iconCenterX = scaleX(chevX + 10.0f, cx, scale);
        float iconCenterY = scaleY(chevY + 10.0f, cy, scale);
        float iconSize = 11.0f * scale;
        int baseIconCol = chevHover ? TEXT_WHITE : TEXT_MUTED;
        float clampedExp = Math.clamp(expProgress, 0.0f, 1.0f);
        float plusAlpha = (1.0f - clampedExp) * alpha;
        float minusAlpha = clampedExp * alpha;

        if (plusAlpha > 0.02f) {
            IconAtlas.drawIcon(
                    batch,
                    IconAtlas.Icon.PLUS,
                    iconCenterX,
                    iconCenterY,
                    iconSize,
                    (float) (clampedExp * Math.PI * 0.5),
                    ColorUtil.withAlpha(baseIconCol, plusAlpha)
            );
        }
        if (minusAlpha > 0.02f) {
            IconAtlas.drawIcon(
                    batch,
                    IconAtlas.Icon.MINUS,
                    iconCenterX,
                    iconCenterY,
                    iconSize,
                    (float) ((clampedExp - 1.0f) * Math.PI * 0.5),
                    ColorUtil.withAlpha(baseIconCol, minusAlpha)
            );
        }
    }

    private void renderModuleCardTexts(RenderBatch2D batch, VisualModule m, float x, float y, float w, float expProgress,
                                       float clipTop, float clipBottom,
                                       float cx, float cy, float scale, float alpha, boolean includeDrawerSettings) {
        float hAnim = m.getHoverAnim().getValue();
        float tAnim = m.getToggleAnim().getValue();
        int titleCol = ColorUtil.lerp(0xFFE5E7EB, 0xFFFFFFFF, Math.max(hAnim, tAnim));

        FontRenderer.drawText(
                batch,
                m.getName(),
                scaleX(x + 16.0f, cx, scale),
                scaleY(y + 11.0f, cy, scale),
                14.0f * scale,
                ColorUtil.withAlpha(titleCol, alpha)
        );

        FontRenderer.drawText(
                batch,
                getTrimmedDescription(m, w - 120.0f),
                scaleX(x + 16.0f, cx, scale),
                scaleY(y + 30.0f, cy, scale),
                11.0f * scale,
                ColorUtil.argb((int) (175 * alpha), 156, 163, 175)
        );

        if (!includeDrawerSettings) {
            return;
        }
        float eased = Math.clamp(expProgress, 0.0f, 1.0f);
        float contentAlpha = Math.clamp((eased - 0.12f) / 0.75f, 0.0f, 1.0f);
        if (eased <= 0.001f || contentAlpha <= 0.002f) {
            return;
        }
        float innerAlpha = alpha * contentAlpha;
        float slideOffsetY = (1.0f - eased) * -8.0f;
        renderModuleSettingsTexts(batch, m, x, y + 54.0f + 8.0f + slideOffsetY, w, clipTop, clipBottom, cx, cy, scale, innerAlpha);
    }

    private void renderModuleSettingsShapes(RenderBatch2D batch, VisualModule m, float cardX, float startY, float cardW,
                                            float clipTop, float clipBottom,
                                            float cx, float cy, float scale, float innerAlpha, float mx, float my) {
        float curSetY = startY;
        float sx = cardX + 16.0f;
        float sw = cardW - 32.0f;
        for (Setting<?> s : m.getSettings()) {
            if (!s.isVisible()) continue;
            float rowH = getSettingRowHeight(s);
            if (curSetY + rowH >= clipTop && curSetY <= clipBottom) {
                if (s instanceof BooleanSetting bs) {
                    renderBooleanSettingShapes(batch, bs, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof SliderSetting ss) {
                    renderSliderSettingShapes(batch, ss, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof ModeSetting ms) {
                    renderModeSettingShapes(batch, ms, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof KeybindSetting ks) {
                    renderKeybindSettingShapes(batch, ks, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof ActionSetting as) {
                    renderActionSettingShapes(batch, as, sx, curSetY, sw, cx, cy, scale, innerAlpha, mx, my);
                } else if (s instanceof ColorSetting cs) {
                    renderColorSettingShapes(batch, cs, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                }
            }
            curSetY += rowH;
        }
    }

    private void renderModuleSettingsTexts(RenderBatch2D batch, VisualModule m, float cardX, float startY, float cardW,
                                           float clipTop, float clipBottom,
                                           float cx, float cy, float scale, float innerAlpha) {
        float curSetY = startY;
        float sx = cardX + 16.0f;
        float sw = cardW - 32.0f;
        for (Setting<?> s : m.getSettings()) {
            if (!s.isVisible()) continue;
            float rowH = getSettingRowHeight(s);
            if (curSetY + rowH >= clipTop && curSetY <= clipBottom) {
                if (s instanceof BooleanSetting bs) {
                    renderBooleanSettingTexts(batch, bs, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof SliderSetting ss) {
                    renderSliderSettingTexts(batch, ss, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof ModeSetting ms) {
                    renderModeSettingTexts(batch, ms, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof KeybindSetting ks) {
                    renderKeybindSettingTexts(batch, ks, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof ActionSetting as) {
                    renderActionSettingTexts(batch, as, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                } else if (s instanceof ColorSetting cs) {
                    renderColorSettingTexts(batch, cs, sx, curSetY, sw, cx, cy, scale, innerAlpha);
                }
            }
            curSetY += rowH;
        }
    }

    private void renderToggleSwitch(RenderBatch2D batch, float swX, float swY, float swW, float swH, float tAnim,
                                    float cx, float cy, float scale, float alpha) {
        int trackOff = ColorUtil.argb((int) (34 * alpha), 255, 255, 255);
        int trackOn = ColorUtil.withAlpha(ClientSettings.getAccentColor(), 0.96f * alpha);
        int curTrackColor = ColorUtil.lerp(trackOff, trackOn, tAnim);

        batch.drawRoundedRect(
                scaleX(swX, cx, scale),
                scaleY(swY, cy, scale),
                swW * scale,
                swH * scale,
                (swH * 0.5f) * scale,
                curTrackColor
        );

        float knobSize = swH - 4.0f;
        float knobMinX = swX + 2.0f;
        float knobMaxX = swX + swW - knobSize - 2.0f;
        float curKnobX = knobMinX + (knobMaxX - knobMinX) * tAnim;

        batch.drawRoundedRect(
                scaleX(curKnobX, cx, scale),
                scaleY(swY + 2.0f, cy, scale),
                knobSize * scale,
                knobSize * scale,
                (knobSize * 0.5f) * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );
    }

    private void renderBooleanSetting(RenderBatch2D batch, BooleanSetting bs, float x, float y, float w,
                                      float cx, float cy, float scale, float alpha) {
        renderBooleanSettingShapes(batch, bs, x, y, w, cx, cy, scale, alpha);
        renderBooleanSettingTexts(batch, bs, x, y, w, cx, cy, scale, alpha);
    }

    private void renderBooleanSettingShapes(RenderBatch2D batch, BooleanSetting bs, float x, float y, float w,
                                            float cx, float cy, float scale, float alpha) {
        float swW = 28.0f;
        float swH = 16.0f;
        float swX = x + w - swW - 4.0f;
        renderToggleSwitch(batch, swX, y + 6.0f, swW, swH, bs.getToggleAnim().getValue(), cx, cy, scale, alpha);
    }

    private void renderBooleanSettingTexts(RenderBatch2D batch, BooleanSetting bs, float x, float y, float w,
                                           float cx, float cy, float scale, float alpha) {
        FontRenderer.drawText(
                batch,
                LocalizationManager.settingName(bs.getName()),
                scaleX(x + 4.0f, cx, scale),
                scaleY(y + 7.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
        );
    }

    private void renderSliderSetting(RenderBatch2D batch, SliderSetting ss, float x, float y, float w,
                                     float cx, float cy, float scale, float alpha) {
        renderSliderSettingShapes(batch, ss, x, y, w, cx, cy, scale, alpha);
        renderSliderSettingTexts(batch, ss, x, y, w, cx, cy, scale, alpha);
    }

    private void renderSliderSettingShapes(RenderBatch2D batch, SliderSetting ss, float x, float y, float w,
                                           float cx, float cy, float scale, float alpha) {
        float trackX = x + 4.0f;
        float trackY = y + 23.0f;
        float trackW = w - 8.0f;
        float trackH = 4.0f;

        batch.drawRoundedRect(
                scaleX(trackX, cx, scale),
                scaleY(trackY, cy, scale),
                trackW * scale,
                trackH * scale,
                2.0f * scale,
                ColorUtil.argb((int) (32 * alpha), 255, 255, 255)
        );

        float fillNorm = ss.getProgressAnim().getValue();
        float fillW = Math.max(trackH, trackW * fillNorm);
        batch.drawRoundedRect(
                scaleX(trackX, cx, scale),
                scaleY(trackY, cy, scale),
                fillW * scale,
                trackH * scale,
                2.0f * scale,
                ColorUtil.withAlpha(ClientSettings.getAccentColor(), alpha)
        );

        float thumbRadius = 5.0f;
        float thumbX = trackX + trackW * fillNorm;
        batch.drawRoundedRect(
                scaleX(thumbX - thumbRadius, cx, scale),
                scaleY(trackY + 2.0f - thumbRadius, cy, scale),
                thumbRadius * 2.0f * scale,
                thumbRadius * 2.0f * scale,
                thumbRadius * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );
    }

    private void renderSliderSettingTexts(RenderBatch2D batch, SliderSetting ss, float x, float y, float w,
                                          float cx, float cy, float scale, float alpha) {
        FontRenderer.drawText(
                batch,
                LocalizationManager.settingName(ss.getName()),
                scaleX(x + 4.0f, cx, scale),
                scaleY(y + 3.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
        );

        String valStr = ss.getFormattedValue();
        float valW = FontRenderer.getWidth(valStr, 12.0f * scale);
        FontRenderer.drawText(
                batch,
                valStr,
                scaleX(x + w - 4.0f, cx, scale) - valW,
                scaleY(y + 3.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(TEXT_MUTED, alpha)
        );
    }

    private void renderModeSetting(RenderBatch2D batch, ModeSetting ms, float x, float y, float w,
                                   float cx, float cy, float scale, float alpha) {
        renderModeSettingShapes(batch, ms, x, y, w, cx, cy, scale, alpha);
        renderModeSettingTexts(batch, ms, x, y, w, cx, cy, scale, alpha);
    }

    private void renderModeSettingShapes(RenderBatch2D batch, ModeSetting ms, float x, float y, float w,
                                         float cx, float cy, float scale, float alpha) {
        String modeText = ms.getValue();
        float modeW = FontRenderer.getWidth(modeText, 11.0f * scale) + 18.0f * scale;
        float btnW = Math.max(82.0f * scale, modeW);
        float btnH = 20.0f * scale;
        float btnX = scaleX(x + w - 4.0f, cx, scale) - btnW;
        float btnY = scaleY(y + 5.0f, cy, scale);

        batch.drawRoundedRectWithOutline(
                btnX, btnY, btnW, btnH, 6.0f * scale,
                ColorUtil.argb((int) (14 * alpha), 255, 255, 255),
                1.0f * scale,
                ColorUtil.argb((int) (36 * alpha), 255, 255, 255)
        );
    }

    private void renderModeSettingTexts(RenderBatch2D batch, ModeSetting ms, float x, float y, float w,
                                        float cx, float cy, float scale, float alpha) {
        FontRenderer.drawText(
                batch,
                LocalizationManager.settingName(ms.getName()),
                scaleX(x + 4.0f, cx, scale),
                scaleY(y + 7.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
        );

        String modeText = ms.getValue();
        float modeW = FontRenderer.getWidth(modeText, 11.0f * scale) + 18.0f * scale;
        float btnW = Math.max(82.0f * scale, modeW);
        float btnX = scaleX(x + w - 4.0f, cx, scale) - btnW;
        float btnY = scaleY(y + 5.0f, cy, scale);
        FontRenderer.drawCenteredText(
                batch,
                modeText,
                btnX + btnW * 0.5f,
                btnY + 4.0f * scale,
                11.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );
    }

    private String getLocalizedKeyText(KeybindSetting ks) {
        return "[ " + LocalizationManager.keyDisplay(ks.getDisplayName()) + " ]";
    }

    private void renderKeybindSetting(RenderBatch2D batch, KeybindSetting ks, float x, float y, float w,
                                      float cx, float cy, float scale, float alpha) {
        renderKeybindSettingShapes(batch, ks, x, y, w, cx, cy, scale, alpha);
        renderKeybindSettingTexts(batch, ks, x, y, w, cx, cy, scale, alpha);
    }

    private void renderKeybindSettingShapes(RenderBatch2D batch, KeybindSetting ks, float x, float y, float w,
                                            float cx, float cy, float scale, float alpha) {
        boolean listening = ks.isListening();
        String keyText = getLocalizedKeyText(ks);
        float pillW = Math.max(76.0f * scale, FontRenderer.getWidth(keyText, 11.0f * scale) + 18.0f * scale);
        float pillH = 20.0f * scale;
        float pillX = scaleX(x + w - 4.0f, cx, scale) - pillW;
        float pillY = scaleY(y + 5.0f, cy, scale);

        int accent = ClientSettings.getAccentColor();
        int fillColor = listening
                ? ColorUtil.withAlpha(accent, 0.28f * alpha)
                : ColorUtil.argb((int) (14 * alpha), 255, 255, 255);
        int outlineColor = listening
                ? ColorUtil.withAlpha(accent, 0.95f * alpha)
                : ColorUtil.argb((int) (36 * alpha), 255, 255, 255);

        batch.drawRoundedRectWithOutline(
                pillX, pillY, pillW, pillH, 6.0f * scale,
                fillColor,
                1.0f * scale,
                outlineColor
        );
    }

    private void renderKeybindSettingTexts(RenderBatch2D batch, KeybindSetting ks, float x, float y, float w,
                                           float cx, float cy, float scale, float alpha) {
        FontRenderer.drawText(
                batch,
                LocalizationManager.settingName(ks.getName()),
                scaleX(x + 4.0f, cx, scale),
                scaleY(y + 7.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
        );

        String keyText = getLocalizedKeyText(ks);
        float pillW = Math.max(76.0f * scale, FontRenderer.getWidth(keyText, 11.0f * scale) + 18.0f * scale);
        float pillX = scaleX(x + w - 4.0f, cx, scale) - pillW;
        float pillY = scaleY(y + 5.0f, cy, scale);
        FontRenderer.drawCenteredText(
                batch,
                keyText,
                pillX + pillW * 0.5f,
                pillY + 4.0f * scale,
                11.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );
    }

    private void renderColorSetting(RenderBatch2D batch, ColorSetting cs, float x, float y, float w,
                                    float cx, float cy, float scale, float alpha) {
        renderColorSettingShapes(batch, cs, x, y, w, cx, cy, scale, alpha);
        renderColorSettingTexts(batch, cs, x, y, w, cx, cy, scale, alpha);
    }

    private void renderColorSettingShapes(RenderBatch2D batch, ColorSetting cs, float x, float y, float w,
                                          float cx, float cy, float scale, float alpha) {
        float chipW = 38.0f * scale;
        float chipH = 18.0f * scale;
        float chipX = scaleX(x + w - 4.0f, cx, scale) - chipW;
        float chipY = scaleY(y + 6.0f, cy, scale);

        batch.drawRoundedRectWithOutline(
                chipX, chipY, chipW, chipH, 6.0f * scale,
                ColorUtil.withAlpha(cs.getValue(), alpha),
                1.0f * scale,
                ColorUtil.argb((int) (45 * alpha), 255, 255, 255)
        );
    }

    private void renderColorSettingTexts(RenderBatch2D batch, ColorSetting cs, float x, float y, float w,
                                         float cx, float cy, float scale, float alpha) {
        FontRenderer.drawText(
                batch,
                LocalizationManager.settingName(cs.getName()),
                scaleX(x + 4.0f, cx, scale),
                scaleY(y + 7.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
        );
    }

    private void renderActionSetting(RenderBatch2D batch, ActionSetting as, float x, float y, float w,
                                     float cx, float cy, float scale, float alpha, float mx, float my) {
        renderActionSettingShapes(batch, as, x, y, w, cx, cy, scale, alpha, mx, my);
        renderActionSettingTexts(batch, as, x, y, w, cx, cy, scale, alpha);
    }

    private void renderActionSettingShapes(RenderBatch2D batch, ActionSetting as, float x, float y, float w,
                                           float cx, float cy, float scale, float alpha, float mx, float my) {
        String btnLabel = LocalizationManager.actionLabel(as.getButtonLabel());
        float unscaledBtnW = Math.max(76.0f, FontRenderer.getWidth(btnLabel, 11.0f) + 22.0f);
        float unscaledBtnH = 20.0f;
        float unscaledBtnX = x + w - 4.0f - unscaledBtnW;
        float unscaledBtnY = y + 5.0f;

        boolean hovered = mx >= unscaledBtnX && mx <= unscaledBtnX + unscaledBtnW
                && my >= unscaledBtnY && my <= unscaledBtnY + unscaledBtnH;
        float flash = as.getClickFlashProgress();

        float btnW = unscaledBtnW * scale;
        float btnH = unscaledBtnH * scale;
        float btnX = scaleX(unscaledBtnX, cx, scale);
        float btnY = scaleY(unscaledBtnY, cy, scale);

        int accent = ClientSettings.getAccentColor();
        int fillColor = ColorUtil.lerp(
                hovered ? ColorUtil.argb((int) (34 * alpha), 255, 255, 255) : ColorUtil.argb((int) (16 * alpha), 255, 255, 255),
                ColorUtil.withAlpha(accent, 0.58f * alpha),
                flash
        );
        int outlineColor = ColorUtil.lerp(
                hovered ? ColorUtil.withAlpha(accent, 0.70f * alpha) : ColorUtil.argb((int) (42 * alpha), 255, 255, 255),
                ColorUtil.withAlpha(accent, 0.98f * alpha),
                flash
        );

        batch.drawRoundedRectWithOutline(
                btnX, btnY, btnW, btnH, 6.0f * scale,
                fillColor,
                1.0f * scale,
                outlineColor
        );
    }

    private void renderActionSettingTexts(RenderBatch2D batch, ActionSetting as, float x, float y, float w,
                                          float cx, float cy, float scale, float alpha) {
        FontRenderer.drawText(
                batch,
                LocalizationManager.settingName(as.getName()),
                scaleX(x + 4.0f, cx, scale),
                scaleY(y + 7.0f, cy, scale),
                12.0f * scale,
                ColorUtil.withAlpha(TEXT_SECONDARY, alpha)
        );

        String btnLabel = LocalizationManager.actionLabel(as.getButtonLabel());
        float unscaledBtnW = Math.max(76.0f, FontRenderer.getWidth(btnLabel, 11.0f) + 22.0f);
        float btnW = unscaledBtnW * scale;
        float btnX = scaleX(x + w - 4.0f - unscaledBtnW, cx, scale);
        float btnY = scaleY(y + 5.0f, cy, scale);
        FontRenderer.drawCenteredText(
                batch,
                btnLabel,
                btnX + btnW * 0.5f,
                btnY + 4.0f * scale,
                11.0f * scale,
                ColorUtil.withAlpha(TEXT_WHITE, alpha)
        );
    }

    private float computeSettingsHeight(VisualModule m) {
        float h = 8.0f;
        for (Setting<?> s : m.getSettings()) {
            if (!s.isVisible()) continue;
            h += getSettingRowHeight(s);
        }
        return h + 4.0f;
    }

    private List<VisualModule> getModulesForCurrentTab() {
        List<VisualModule> cached = tabModulesCache.get(currentTab);
        if (cached != null) {
            return cached;
        }
        List<VisualModule> result = new ArrayList<>();
        for (VisualModule m : ModuleManager.getModules()) {
            Category c = m.getCategory();
            switch (currentTab) {
                case VISUALS -> {
                    if (c == Category.VISUALS) result.add(m);
                }
                case HUD -> {
                    if (c == Category.HUD || c == Category.COMBAT) result.add(m);
                }
                case UTILITIES -> {
                    if (c == Category.UTILITIES || c == Category.WORLD || c == Category.VIEWMODEL || c == Category.SETTINGS) result.add(m);
                }
                case OPTIMIZATION -> {
                    if (c == Category.OPTIMIZATION) result.add(m);
                }
                default -> {}
            }
        }
        List<VisualModule> immutable = List.copyOf(result);
        tabModulesCache.put(currentTab, immutable);
        return immutable;
    }

    private void updateActiveSlider(float mx) {
        if (activeSlider == null) return;
        if (activeSlider.isDragging()) {
            float sliderTrackX = getSliderTrackX();
            float sliderTrackW = getSliderTrackW();
            activeSlider.setFromNormalized((mx - sliderTrackX) / sliderTrackW);
            if (activeSlider == ClientSettings.getAccentSaturationSetting()
                    || activeSlider == ClientSettings.getAccentBrightnessSetting()) {
                ClientSettings.syncColorFromHsbSliders();
            }
        } else {
            activeSlider = null;
        }
    }

    // =========================================================================
    // Обработка пользовательского ввода
    // =========================================================================

    @Override
    protected boolean onMouseClicked(float mouseX, float mouseY, int button) {
        if (closing || openAnim.getValue() < 0.2f) return false;

        updateViewportMetrics();

        float scale = 0.95f + 0.05f * Math.clamp(openAnim.getValue(), 0.0f, 1.0f);
        float cx = canvasWidth * 0.5f;
        float cy = canvasHeight * 0.5f;
        float mx = cx + (mouseX - cx) / scale;
        float my = cy + (mouseY - cy) / scale;

        KeybindSetting justStoppedListening = null;
        if (listeningKeybind != null) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                listeningKeybind.bindMouse(button);
                stopListeningKeybind();
                return true;
            }
            justStoppedListening = listeningKeybind;
            stopListeningKeybind();
        }

        float winW = computeWinWidth();
        float winH = computeWinHeight();
        float sideW = computeSidebarWidth(winW);
        float winX = cx - (winW * 0.5f);
        float winY = cy - (winH * 0.5f);

        float zoneX = winX + sideW + CONTENT_PAD;
        float zoneY = winY + CONTENT_PAD;
        float zoneW = winW - sideW - CONTENT_PAD * 2.0f;
        float zoneH = winH - CONTENT_PAD * 2.0f;

        if (currentTab == UiTab.CONFIGS && pendingConfirmAction != null && button == 0) {
            ConfirmModalGeometry m = computeConfirmModalGeometry(zoneX, zoneY, zoneW, zoneH);
            if (isInside(mx, my, m.yesX, m.btnY, m.btnW, m.btnH)) {
                executeConfirmedAction();
                return true;
            }
            if (isInside(mx, my, m.noX, m.btnY, m.btnW, m.btnH) || !isInside(mx, my, m.boxX, m.boxY, m.boxW, m.boxH)) {
                pendingConfirmAction = null;
                pendingConfirmTarget = null;
                return true;
            }
            return true;
        }

        // 1. Клик по кнопкам сайдбара (верхний и нижний блоки)
        SidebarLayout layout = computeSidebarLayout(winX, winY, winH, sideW);
        float btnY = layout.catBoxY + layout.boxPadY;
        for (UiTab tab : TOP_TABS) {
            if (isInside(mx, my, layout.btnX, btnY, layout.btnW, layout.btnH)) {
                switchTab(tab);
                return true;
            }
            btnY += layout.btnH + layout.btnGap;
        }

        float footBtnY = layout.footBoxY + layout.boxPadY;
        for (UiTab tab : BOTTOM_TABS) {
            if (isInside(mx, my, layout.btnX, footBtnY, layout.btnW, layout.btnH)) {
                switchTab(tab);
                return true;
            }
            footBtnY += layout.btnH + layout.btnGap;
        }

        // 2. Клики внутри активной контент-зоны

        if (!isInside(mx, my, zoneX, zoneY, zoneW, zoneH)) {
            return false;
        }

        if (currentTab == UiTab.ABOUT) {
            boolean compact = zoneH < 340.0f || zoneW < 380.0f;
            float textX = zoneX + (compact ? 20.0f : 28.0f);
            float linkSize = zoneW < 360.0f ? 12.5f : 14.0f;
            float linkY = computeAboutLinkY(zoneY, zoneW, zoneH);
            float linkW = FontRenderer.getWidth(LocalizationManager.tr("about.website"), linkSize);
            float linkH = Math.max(18.0f, FontRenderer.getHeight(linkSize));
            if (button == 0 && isInside(mx, my, textX, linkY - 2.0f, linkW, linkH + 6.0f)) {
                openProjectWebsite();
                return true;
            }
            return false;
        }

        if (currentTab == UiTab.SETTINGS) {
            return handleSettingsClick(mx, my, button, zoneX, zoneY, zoneW, zoneH);
        }

        if (currentTab == UiTab.CONFIGS) {
            return handleConfigsClick(mx, my, button, zoneX, zoneY, zoneW, zoneH);
        }

        return handleModulesClick(mx, my, button, zoneX, zoneY, zoneW, zoneH, justStoppedListening);
    }

    private boolean handleSettingsClick(float mx, float my, int button, float zoneX, float zoneY, float zoneW, float zoneH) {
        if (button != 0) return false;
        SettingsGeometry g = computeSettingsGeometry(zoneX, zoneY, zoneW, zoneH);
        if (!isInside(mx, my, g.listX, g.listY, g.listW, g.listH)) {
            return false;
        }

        // Компактные пресеты цвета
        float curPresetX = g.presetsStartX;
        for (ClientSettings.AccentPreset preset : ClientSettings.ACCENT_PRESETS) {
            if (isInside(mx, my, curPresetX, g.presetsY, g.swatchSize, g.swatchSize)) {
                ClientSettings.setAccentColor(preset.argb());
                return true;
            }
            curPresetX += g.swatchSize + g.swatchGap;
        }

        // Кнопка-свотч, раскрывающая компактную HSB-палитру
        if (isInside(mx, my, g.customX - 2.0f, g.customY - 2.0f, g.customW + 4.0f, g.customH + 4.0f)) {
            accentPickerExpanded = !accentPickerExpanded;
            return true;
        }

        // Элементы раскрытой HSB-палитры
        if (accentPickerExpanded && accentPickerAnim > 0.2f) {
            if (isInside(mx, my, g.hueX - 2.0f, g.hueY - 4.0f, g.hueW + 4.0f, g.hueH + 8.0f)) {
                draggingColorHue = true;
                float hue = Math.clamp((mx - g.hueX) / Math.max(1.0f, g.hueW), 0.0f, 1.0f);
                ClientSettings.setAccentHsb(hue, ClientSettings.getAccentSat(), ClientSettings.getAccentBri());
                return true;
            }

            float trackX = getSliderTrackX();
            float trackW = getSliderTrackW();

            float satTrackY = g.satSliderY + 23.0f;
            if (my >= satTrackY - 6.0f && my <= satTrackY + 14.0f) {
                SliderSetting ss = ClientSettings.getAccentSaturationSetting();
                ss.setDragging(true);
                ss.setFromNormalized((mx - trackX) / trackW);
                ClientSettings.syncColorFromHsbSliders();
                activeSlider = ss;
                return true;
            }

            float briTrackY = g.briSliderY + 23.0f;
            if (my >= briTrackY - 6.0f && my <= briTrackY + 14.0f) {
                SliderSetting ss = ClientSettings.getAccentBrightnessSetting();
                ss.setDragging(true);
                ss.setFromNormalized((mx - trackX) / trackW);
                ClientSettings.syncColorFromHsbSliders();
                activeSlider = ss;
                return true;
            }
        }

        // Слайдеры ClickGUI Scale, UI Opacity, Blur Strength, Animation Speed
        float curSetY = g.slidersStartY;
        SliderSetting[] globalSliders = {
                ClientSettings.getClickGuiScaleSetting(),
                ClientSettings.getUiOpacitySetting(),
                ClientSettings.getBlurStrengthSetting(),
                ClientSettings.getAnimationSpeedSetting()
        };
        for (SliderSetting ss : globalSliders) {
            float trackY = curSetY + 23.0f;
            if (my >= trackY - 6.0f && my <= trackY + 14.0f) {
                float trackX = getSliderTrackX();
                float trackW = getSliderTrackW();
                ss.setDragging(true);
                ss.setFromNormalized((mx - trackX) / trackW);
                activeSlider = ss;
                return true;
            }
            curSetY += 42.0f;
        }

        // Переключатель языка интерфейса (Авто / Русский / English)
        LangTrackLayout langLayout = computeLanguageTrackLayout(g);
        for (LangSegment seg : langLayout.segments) {
            if (isInside(mx, my, seg.x, seg.y, seg.w, seg.h)) {
                LocalizationManager.setLanguageMode(seg.mode);
                return true;
            }
        }
        return false;
    }

    private void switchTab(UiTab tab) {
        if (currentTab != tab) {
            currentTab = tab;
            scrollOffset = 0.0f;
            targetScroll = 0.0f;
            activeSlider = null;
            stopListeningKeybind();
            draggingColorHue = false;
            configInputFocused = false;
            renamingConfigName = null;
            renameBuffer = "";
            pendingConfirmAction = null;
            pendingConfirmTarget = null;
            if (tab == UiTab.CONFIGS) {
                ConfigManager.refreshConfigs();
            }
        }
    }

    private void updateLinkCursor(boolean shouldShowHand) {
        if (client == null || client.getWindow() == null) return;
        long windowHandle = client.getWindow().getHandle();
        if (windowHandle == 0L) return;

        if (shouldShowHand && !handCursorApplied) {
            if (handCursorHandle == 0L) {
                handCursorHandle = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
            }
            if (handCursorHandle != 0L) {
                GLFW.glfwSetCursor(windowHandle, handCursorHandle);
                handCursorApplied = true;
            }
        } else if (!shouldShowHand && handCursorApplied) {
            GLFW.glfwSetCursor(windowHandle, 0L);
            handCursorApplied = false;
        }
    }

    private void openProjectWebsite() {
        try {
            Util.getOperatingSystem().open(URI.create(PROJECT_URL));
        } catch (Exception ignored) {
            if (client != null && client.keyboard != null) {
                client.keyboard.setClipboard(PROJECT_URL);
            }
        }
        linkFeedbackTimer = 2.0f;
    }

    private void setConfigStatus(String text) {
        setConfigStatus(text, 2.4f);
    }

    private void setConfigStatus(String text, float durationSec) {
        configStatusText = text;
        configStatusTimer = durationSec;
    }

    private void performLoadConfig(String name) {
        if (ConfigManager.loadConfig(name)) {
            String notice = ConfigManager.consumeLastStatusNotice();
            if (!notice.isEmpty()) {
                setConfigStatus(notice, 4.5f);
            } else if (ConfigManager.DEFAULT_CONFIG.equals(name)) {
                setConfigStatus(LocalizationManager.tr("configs.status.loaded_default"));
            } else {
                setConfigStatus(LocalizationManager.tr("configs.status.loaded", name));
            }
        } else {
            setConfigStatus(LocalizationManager.tr("configs.status.load_failed", name));
        }
    }

    private void executeConfirmedAction() {
        ConfirmAction action = pendingConfirmAction;
        String target = pendingConfirmTarget;
        pendingConfirmAction = null;
        pendingConfirmTarget = null;
        if (action == null || target == null) {
            return;
        }
        if (action == ConfirmAction.OVERWRITE_CONFIG) {
            if (ConfigManager.saveConfig(target)) {
                setConfigStatus(LocalizationManager.tr("configs.status.overwritten", target));
            } else {
                setConfigStatus(LocalizationManager.tr("configs.status.save_failed", target));
            }
        } else if (action == ConfirmAction.SWITCH_CONFIG) {
            performLoadConfig(target);
        } else if (action == ConfirmAction.RESET_CONFIG) {
            if (ConfigManager.resetConfig(target)) {
                setConfigStatus(LocalizationManager.tr("configs.status.reset_done", target));
            } else {
                setConfigStatus(LocalizationManager.tr("configs.status.reset_failed", target));
            }
        }
    }

    private void requestSwitchConfig(String targetName) {
        if (targetName == null || targetName.equals(ConfigManager.getActiveConfigName())) {
            return;
        }
        if (ConfigManager.hasUnsavedChanges()) {
            pendingConfirmAction = ConfirmAction.SWITCH_CONFIG;
            pendingConfirmTarget = targetName;
        } else {
            performLoadConfig(targetName);
        }
    }

    private void requestOverwriteConfig(String targetName) {
        pendingConfirmAction = ConfirmAction.OVERWRITE_CONFIG;
        pendingConfirmTarget = targetName;
    }

    private void requestResetConfig(String targetName) {
        pendingConfirmAction = ConfirmAction.RESET_CONFIG;
        pendingConfirmTarget = targetName;
    }

    private void triggerCreateConfigFromInput() {
        String clean = ConfigManager.sanitizeName(newConfigName);
        if (clean.isEmpty()) {
            setConfigStatus(LocalizationManager.tr("configs.status.enter_name"));
            return;
        }
        if (ConfigManager.DEFAULT_CONFIG.equalsIgnoreCase(clean)) {
            setConfigStatus(LocalizationManager.tr("configs.status.reserved_default"));
            return;
        }
        if (ConfigManager.createConfig(clean)) {
            newConfigName = "";
            configInputFocused = false;
            setConfigStatus(LocalizationManager.tr("configs.status.created", clean));
        } else {
            setConfigStatus(LocalizationManager.tr("configs.status.exists", clean));
        }
    }

    private boolean handleConfigsClick(float mx, float my, int button, float zoneX, float zoneY, float zoneW, float zoneH) {
        if (button != 0) {
            return false;
        }

        ConfigsGeometry g = computeConfigsGeometry(zoneX, zoneY, zoneW, zoneH);

        if (isInside(mx, my, g.autoSaveX, g.autoSaveY, g.autoSaveW, g.autoSaveH)) {
            configInputFocused = false;
            renamingConfigName = null;
            boolean nextState = !ConfigManager.isAutoSaveEnabled();
            ConfigManager.setAutoSaveEnabled(nextState);
            setConfigStatus(LocalizationManager.tr(
                    "configs.status.autosave_state",
                    ConfigManager.getActiveConfigName(),
                    LocalizationManager.tr(nextState ? "configs.status.on" : "configs.status.off")
            ));
            return true;
        }

        if (isInside(mx, my, g.inputX, g.inputY, g.inputW, g.actionH)) {
            configInputFocused = true;
            renamingConfigName = null;
            return true;
        }
        configInputFocused = false;

        if (isInside(mx, my, g.createX, g.inputY, g.createW, g.actionH)) {
            renamingConfigName = null;
            triggerCreateConfigFromInput();
            return true;
        }

        if (isInside(mx, my, g.folderX, g.inputY, g.iconBtnW, g.actionH)) {
            renamingConfigName = null;
            ConfigManager.openConfigsFolder();
            setConfigStatus(LocalizationManager.tr("configs.status.opened_folder", ConfigManager.getConfigsDirDisplay()));
            return true;
        }

        if (isInside(mx, my, g.refreshX, g.inputY, g.iconBtnW, g.actionH)) {
            renamingConfigName = null;
            ConfigManager.refreshConfigs();
            setConfigStatus(LocalizationManager.tr("configs.status.refreshed"));
            return true;
        }

        if (!isInside(mx, my, g.x, g.listY, g.w, g.listH)) {
            renamingConfigName = null;
            return false;
        }

        List<String> configNames = ConfigManager.getConfigNames();
        float curY = g.listY + scrollOffset;
        float cardH = 52.0f;

        for (String name : configNames) {
            if (my >= curY && my <= curY + cardH) {
                boolean isDefault = ConfigManager.DEFAULT_CONFIG.equals(name);
                ConfigCardButtons btns = computeConfigCardButtons(g, curY, cardH, isDefault);
                float btnY = btns.btnY;
                float btnSize = btns.btnSize;

                if (isInside(mx, my, btns.loadX, btnY, btnSize, btnSize)) {
                    renamingConfigName = null;
                    requestSwitchConfig(name);
                    return true;
                }
                if (isInside(mx, my, btns.saveX, btnY, btnSize, btnSize)) {
                    renamingConfigName = null;
                    requestOverwriteConfig(name);
                    return true;
                }
                if (isInside(mx, my, btns.resetX, btnY, btnSize, btnSize)) {
                    renamingConfigName = null;
                    requestResetConfig(name);
                    return true;
                }

                if (!isDefault) {
                    if (isInside(mx, my, btns.renX, btnY, btnSize, btnSize)) {
                        if (name.equals(renamingConfigName)) {
                            if (ConfigManager.renameConfig(name, renameBuffer)) {
                                setConfigStatus(LocalizationManager.tr("configs.status.renamed_short", ConfigManager.sanitizeName(renameBuffer)));
                            }
                            renamingConfigName = null;
                        } else {
                            renamingConfigName = name;
                            renameBuffer = name;
                        }
                        return true;
                    }
                    if (isInside(mx, my, btns.delX, btnY, btnSize, btnSize)) {
                        renamingConfigName = null;
                        if (ConfigManager.deleteConfig(name)) {
                            setConfigStatus(LocalizationManager.tr("configs.status.deleted", name));
                        }
                        return true;
                    }
                    if (name.equals(renamingConfigName)) {
                        return true;
                    }
                }

                renamingConfigName = null;
                requestSwitchConfig(name);
                return true;
            }
            curY += cardH + 10.0f;
        }
        renamingConfigName = null;
        return false;
    }

    private boolean handleModulesClick(float mx, float my, int button, float zoneX, float zoneY, float zoneW, float zoneH,
                                       KeybindSetting justStoppedListening) {
        float listX = zoneX + 24.0f;
        float listY = zoneY + 22.0f + 46.0f;
        float listW = zoneW - 48.0f;
        float listH = zoneH - (listY - zoneY) - 16.0f;

        if (!isInside(mx, my, listX, listY, listW, listH)) {
            return false;
        }

        float curY = listY + scrollOffset;
        for (VisualModule m : getModulesForCurrentTab()) {
            float expProgress = m.getExpandProgress();
            float settingsH = computeSettingsHeight(m);
            float cardH = 54.0f + (expProgress > 0.001f ? settingsH * expProgress : 0.0f);

            if (my >= curY && my <= curY + 54.0f) {
                float swW = 32.0f;
                float swX = listX + listW - swW - 16.0f;
                if (mx >= swX - 4.0f && mx <= swX + swW + 4.0f) {
                    m.toggle();
                    return true;
                }

                if (!m.getSettings().isEmpty()) {
                    float chevX = swX - 28.0f;
                    if (mx >= chevX && mx <= chevX + 20.0f) {
                        m.setExpanded(!m.isExpanded());
                        return true;
                    }
                }

                if (button == 0) {
                    m.toggle();
                    return true;
                } else if (button == 1 && !m.getSettings().isEmpty()) {
                    m.setExpanded(!m.isExpanded());
                    return true;
                }
            }

            if (m.isExpanded() && expProgress > 0.25f && my > curY + 54.0f && my <= curY + cardH) {
                float slideOffsetY = (1.0f - expProgress) * -8.0f;
                float curSetY = curY + 54.0f + 8.0f + slideOffsetY;
                for (Setting<?> s : m.getSettings()) {
                    if (!s.isVisible()) continue;

                    if (s instanceof BooleanSetting bs) {
                        if (my >= curSetY && my <= curSetY + 32.0f) {
                            bs.toggle();
                            return true;
                        }
                        curSetY += 32.0f;
                    } else if (s instanceof SliderSetting ss) {
                        float trackY = curSetY + 23.0f;
                        if (my >= trackY - 6.0f && my <= trackY + 14.0f) {
                            float trackX = getSliderTrackX();
                            float trackW = getSliderTrackW();
                            ss.setDragging(true);
                            ss.setFromNormalized((mx - trackX) / trackW);
                            activeSlider = ss;
                            return true;
                        }
                        curSetY += 42.0f;
                    } else if (s instanceof ModeSetting ms) {
                        if (my >= curSetY && my <= curSetY + 34.0f) {
                            if (button == 0) ms.cycle();
                            else if (button == 1) ms.cycleBack();
                            return true;
                        }
                        curSetY += 34.0f;
                    } else if (s instanceof KeybindSetting ks) {
                        String keyText = getLocalizedKeyText(ks);
                        float pillW = Math.max(76.0f, FontRenderer.getWidth(keyText, 11.0f) + 18.0f);
                        float pillX = listX + listW - 20.0f - pillW;
                        if (my >= curSetY && my <= curSetY + 34.0f && mx >= pillX - 6.0f && mx <= pillX + pillW + 6.0f) {
                            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && ks != justStoppedListening) {
                                listeningKeybind = ks;
                                ks.setListening(true);
                            } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                                ks.unbind();
                                stopListeningKeybind();
                            }
                            return true;
                        }
                        curSetY += 34.0f;
                    } else if (s instanceof ActionSetting as) {
                        float btnW = Math.max(76.0f, FontRenderer.getWidth(LocalizationManager.actionLabel(as.getButtonLabel()), 11.0f) + 22.0f);
                        float btnX = listX + listW - 20.0f - btnW;
                        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                                && my >= curSetY + 2.0f && my <= curSetY + 28.0f
                                && mx >= btnX - 4.0f && mx <= btnX + btnW + 4.0f) {
                            as.trigger();
                            return true;
                        }
                        curSetY += 34.0f;
                    } else if (s instanceof ColorSetting cs) {
                        if (my >= curSetY && my <= curSetY + 34.0f) {
                            cs.setRainbow(!cs.isRainbow());
                            return true;
                        }
                        curSetY += 34.0f;
                    }
                }
            }

            curY += cardH + 10.0f;
        }
        return false;
    }

    @Override
    protected boolean onMouseReleased(float mouseX, float mouseY, int button) {
        boolean handled = false;
        if (draggingColorHue) {
            draggingColorHue = false;
            handled = true;
        }
        if (activeSlider != null) {
            activeSlider.setDragging(false);
            activeSlider = null;
            handled = true;
        }
        return handled;
    }

    @Override
    protected boolean onMouseScrolled(float mouseX, float mouseY, double horizontalAmount, double verticalAmount) {
        if (currentTab == UiTab.CONFIGS && pendingConfirmAction != null) {
            return true;
        }
        if (closing || maxScroll <= 0.0f || currentTab == UiTab.ABOUT) {
            return false;
        }
        targetScroll += (float) verticalAmount * 45.0f;
        targetScroll = Math.clamp(targetScroll, -maxScroll, 0.0f);
        return true;
    }

    @Override
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (listeningKeybind != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_DELETE) {
                listeningKeybind.unbind();
            } else if (keyCode != GLFW.GLFW_KEY_UNKNOWN) {
                listeningKeybind.bindKey(keyCode);
            }
            stopListeningKeybind();
            return true;
        }

        if (currentTab == UiTab.CONFIGS && pendingConfirmAction != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                pendingConfirmAction = null;
                pendingConfirmTarget = null;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                executeConfirmedAction();
                return true;
            }
            if (keyCode != GLFW.GLFW_KEY_RIGHT_SHIFT) {
                return true;
            }
        }

        if (currentTab == UiTab.CONFIGS && renamingConfigName != null) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!renameBuffer.isEmpty()) {
                    renameBuffer = renameBuffer.substring(0, renameBuffer.length() - 1);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                String oldName = renamingConfigName;
                String newName = ConfigManager.sanitizeName(renameBuffer);
                renamingConfigName = null;
                if (newName.isEmpty()) {
                    setConfigStatus(LocalizationManager.tr("configs.status.rename_empty"));
                } else if (ConfigManager.renameConfig(oldName, newName)) {
                    setConfigStatus(LocalizationManager.tr("configs.status.renamed_pair", oldName, newName));
                } else {
                    setConfigStatus(LocalizationManager.tr("configs.status.rename_failed", newName));
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                renamingConfigName = null;
                return true;
            }
            if (keyCode != GLFW.GLFW_KEY_RIGHT_SHIFT) {
                return true;
            }
        }

        if (currentTab == UiTab.CONFIGS && configInputFocused) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!newConfigName.isEmpty()) {
                    newConfigName = newConfigName.substring(0, newConfigName.length() - 1);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                triggerCreateConfigFromInput();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                configInputFocused = false;
                return true;
            }
            if (keyCode != GLFW.GLFW_KEY_RIGHT_SHIFT) {
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            startClosing();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onCharTyped(char chr, int modifiers) {
        if (currentTab != UiTab.CONFIGS) {
            return false;
        }
        boolean validChar = Character.isLetterOrDigit(chr) || chr == ' ' || chr == '_' || chr == '-';
        if (!validChar) {
            return renamingConfigName != null || configInputFocused;
        }
        if (renamingConfigName != null) {
            if (renameBuffer.length() < 32) {
                renameBuffer += chr;
            }
            return true;
        }
        if (configInputFocused) {
            if (newConfigName.length() < 32) {
                newConfigName += chr;
            }
            return true;
        }
        return false;
    }

    private void recomputeScrollBounds() {
        if (currentTab == UiTab.ABOUT) {
            maxScroll = 0.0f;
            clampScrollBounds();
            return;
        }
        float winW = computeWinWidth();
        float winH = computeWinHeight();
        float sideW = computeSidebarWidth(winW);
        float cx = canvasWidth * 0.5f;
        float cy = canvasHeight * 0.5f;
        float winX = cx - (winW * 0.5f);
        float winY = cy - (winH * 0.5f);
        float zoneX = winX + sideW + CONTENT_PAD;
        float zoneY = winY + CONTENT_PAD;
        float zoneW = winW - sideW - CONTENT_PAD * 2.0f;
        float zoneH = winH - CONTENT_PAD * 2.0f;

        if (currentTab == UiTab.SETTINGS) {
            SettingsGeometry g = computeSettingsGeometry(zoneX, zoneY, zoneW, zoneH);
            float totalH = g.accentCardH + 10.0f + g.motionCardH + 10.0f;
            maxScroll = Math.max(0.0f, totalH - g.listH);
        } else if (currentTab == UiTab.CONFIGS) {
            ConfigsGeometry g = computeConfigsGeometry(zoneX, zoneY, zoneW, zoneH);
            float totalH = ConfigManager.getConfigNames().size() * 62.0f;
            maxScroll = Math.max(0.0f, totalH - g.listH);
        } else {
            float listH = zoneH - 22.0f - 46.0f - 16.0f;
            float totalH = 0.0f;
            for (VisualModule m : getModulesForCurrentTab()) {
                float expProgress = m.getExpandProgress();
                float cardH = 54.0f;
                if (expProgress > 0.001f) {
                    cardH += computeSettingsHeight(m) * expProgress;
                }
                totalH += cardH + 10.0f;
            }
            maxScroll = Math.max(0.0f, totalH - listH);
        }
        clampScrollBounds();
    }

    private void clampScrollBounds() {
        targetScroll = Math.clamp(targetScroll, -maxScroll, 0.0f);
        scrollOffset = Math.clamp(scrollOffset, -maxScroll, 0.0f);
    }

    private boolean isInside(float px, float py, float x, float y, float w, float h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private float getSliderTrackX() {
        float winW = computeWinWidth();
        float sideW = computeSidebarWidth(winW);
        float cx = canvasWidth * 0.5f;
        float winX = cx - (winW * 0.5f);
        float zoneX = winX + sideW + CONTENT_PAD;
        float listX = zoneX + 24.0f;
        return listX + 16.0f + 4.0f;
    }

    private float getSliderTrackW() {
        float winW = computeWinWidth();
        float sideW = computeSidebarWidth(winW);
        float zoneW = winW - sideW - CONTENT_PAD * 2.0f;
        float listW = zoneW - 48.0f;
        return listW - 32.0f - 8.0f;
    }

    private float scaleX(float x, float cx, float scale) {
        return cx + (x - cx) * scale;
    }

    private float scaleY(float y, float cy, float scale) {
        return cy + (y - cy) * scale;
    }
}
