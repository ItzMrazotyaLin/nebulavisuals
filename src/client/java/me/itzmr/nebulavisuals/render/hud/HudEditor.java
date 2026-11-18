package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.config.ConfigManager;
import me.itzmr.nebulavisuals.config.LocalizationManager;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.HudModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Редактор перетаскивания и масштабирования HUD-элементов при открытом ChatScreen.
 *
 * Особенности UX:
 * - В спокойном состоянии HUD-элементы не имеют постоянных рамок;
 * - При наведении появляется компактная стеклянная мини-кнопка {@code Resize};
 * - После клика {@code Resize} включается режим масштабирования (акцентная рамка 1px,
 *   угловой ползунок, поддержка колеса мыши и кнопки {@code Apply} / {@code Cancel}).
 */
public final class HudEditor {
    private static final float SAFE_EDGE_MARGIN = 8.0f;
    private static final float SNAP_THRESHOLD = 5.0f;

    private static final float RESIZE_BTN_W = 46.0f;
    private static final float RESIZE_BTN_H = 16.0f;
    private static final float ACTION_BTN_W = 48.0f;
    private static final float ACTION_BTN_H = 18.0f;
    private static final float ACTION_BTN_GAP = 6.0f;
    private static final float CORNER_HANDLE_SIZE = 7.0f;
    private static final float CORNER_HIT_PAD = 3.0f;

    private static final double[] CURSOR_X_BUF = new double[1];
    private static final double[] CURSOR_Y_BUF = new double[1];

    private static HudModule draggingModule = null;
    private static float grabOffsetX = 0.0f;
    private static float grabOffsetY = 0.0f;

    // Активный режим редактирования масштаба (Resize -> Apply / Cancel)
    private static HudModule activeResizeModule = null;
    private static float originalResizeScale = 1.0f;
    private static boolean draggingResizeHandle = false;
    private static float resizeStartMouseX = 0.0f;
    private static float resizeStartMouseY = 0.0f;
    private static float resizeStartScale = 1.0f;
    private static float resizeBaseWidth = 100.0f;
    private static float resizeBaseHeight = 28.0f;

    // Плавное появление кнопки Resize при наведении в обычном состоянии
    private static HudModule hoveredResizeTarget = null;
    private static float resizeHoverAnim = 0.0f;
    private static long lastOverlayNs = 0L;

    private static Float activeSnapLineX = null;
    private static Float activeSnapLineY = null;

    private HudEditor() {}

    public static boolean isDragging() {
        return draggingModule != null;
    }

    public static boolean isDragging(HudModule module) {
        return draggingModule != null && draggingModule == module;
    }

    public static boolean isResizing() {
        return draggingResizeHandle || activeResizeModule != null;
    }

    public static boolean isResizing(HudModule module) {
        return activeResizeModule != null && activeResizeModule == module;
    }

    public static boolean onMousePressed(MinecraftClient client, int button) {
        if (client == null || client.getWindow() == null || !(client.currentScreen instanceof ChatScreen) || button != 0) {
            return false;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;
        float mx = getCursorCanvasX(client, fbW, uiScale);
        float my = getCursorCanvasY(client, fbH, uiScale);

        // 1. Если активирован режим Resize для конкретного HUD-элемента: обрабатываем Apply / Defaults / Cancel / угловой ползунок
        if (activeResizeModule != null && activeResizeModule.isEnabled()) {
            float x = activeResizeModule.getLastRenderedX();
            float y = activeResizeModule.getLastRenderedY();
            float w = activeResizeModule.getLastWidth();
            float h = activeResizeModule.getLastHeight();

            ResizeBarLayout bar = computeResizeActionBar(x, y, w, h, activeResizeModule.getHudScale(), canvasW, canvasH);

            // Кнопка Apply
            if (mx >= bar.applyX && mx <= bar.applyX + bar.applyW && my >= bar.barY && my <= bar.barY + bar.btnH) {
                confirmResize();
                return true;
            }
            // Кнопка Defaults
            if (mx >= bar.defaultsX && mx <= bar.defaultsX + bar.defaultsW && my >= bar.barY && my <= bar.barY + bar.btnH) {
                activeResizeModule.setHudScale(activeResizeModule.getDefaultHudScale());
                return true;
            }
            // Кнопка Cancel
            if (mx >= bar.cancelX && mx <= bar.cancelX + bar.cancelW && my >= bar.barY && my <= bar.barY + bar.btnH) {
                cancelResize();
                return true;
            }
            // Угловой ползунок в правом нижнем углу
            if (isOverCornerHandle(mx, my, x, y, w, h)) {
                draggingResizeHandle = true;
                resizeStartMouseX = mx;
                resizeStartMouseY = my;
                resizeStartScale = Math.max(HudModule.MIN_HUD_SCALE, activeResizeModule.getHudScale());
                resizeBaseWidth = Math.max(16.0f, w / resizeStartScale);
                resizeBaseHeight = Math.max(12.0f, h / resizeStartScale);
                return true;
            }
            // Разрешаем свободное перетаскивание активного элемента за его область даже в режиме Resize
            if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
                draggingModule = activeResizeModule;
                grabOffsetX = mx - x;
                grabOffsetY = my - y;
                return true;
            }
            return true;
        }

        // 2. В обычном состоянии: сначала проверяем клик по всплывающей мини-кнопке "Resize"
        if (hoveredResizeTarget != null && hoveredResizeTarget.isEnabled() && resizeHoverAnim > 0.15f) {
            float hx = hoveredResizeTarget.getLastRenderedX();
            float hy = hoveredResizeTarget.getLastRenderedY();
            float hw = hoveredResizeTarget.getLastWidth();
            float hh = hoveredResizeTarget.getLastHeight();
            float resizeBtnW = getResizeBtnW();
            float btnX = computeResizeBtnX(hx, hw, canvasW, resizeBtnW);
            float btnY = computeBottomBarY(hy, hh, RESIZE_BTN_H, 4.0f, canvasH);
            if (mx >= btnX - 2.0f && mx <= btnX + resizeBtnW + 2.0f
                    && my >= btnY - 2.0f && my <= btnY + RESIZE_BTN_H + 2.0f) {
                activeResizeModule = hoveredResizeTarget;
                originalResizeScale = hoveredResizeTarget.getHudScale();
                draggingResizeHandle = false;
                draggingModule = null;
                resizeHoverAnim = 0.0f;
                return true;
            }
        }

        // 3. Иначе перетаскивание любого HUD-элемента зажатием ЛКМ
        List<HudModule> hudModules = ModuleManager.getHudModules();
        for (int i = hudModules.size() - 1; i >= 0; i--) {
            HudModule m = hudModules.get(i);
            if (!m.isEnabled()) continue;

            float x = m.getLastRenderedX();
            float y = m.getLastRenderedY();
            float w = m.getLastWidth();
            float h = m.getLastHeight();
            if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
                draggingModule = m;
                grabOffsetX = mx - x;
                grabOffsetY = my - y;
                return true;
            }
        }
        return false;
    }

    public static boolean onMouseScroll(MinecraftClient client, double vertical) {
        if (client == null || !(client.currentScreen instanceof ChatScreen)
                || activeResizeModule == null || !activeResizeModule.isEnabled()) {
            return false;
        }
        float step = (float) vertical * 0.05f;
        activeResizeModule.setHudScale(activeResizeModule.getHudScale() + step);
        return true;
    }

    public static void onMouseReleased() {
        draggingModule = null;
        draggingResizeHandle = false;
        activeSnapLineX = null;
        activeSnapLineY = null;
    }

    private static void confirmResize() {
        activeResizeModule = null;
        draggingResizeHandle = false;
        ConfigManager.markDirty();
    }

    private static void cancelResize() {
        if (activeResizeModule != null) {
            activeResizeModule.setHudScale(originalResizeScale);
        }
        activeResizeModule = null;
        draggingResizeHandle = false;
    }

    public static void beginFrame(MinecraftClient client) {
        if (client == null || client.getWindow() == null) return;
        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        updateDrag(client, uiScale, fbW / uiScale, fbH / uiScale);
    }

    public static void renderStandaloneOverlays(MinecraftClient client) {
        if (client == null || client.getWindow() == null || !(client.currentScreen instanceof ChatScreen)) {
            return;
        }
        if (!NebulaRenderEngine.isInitialized()) {
            return;
        }
        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        batch.begin(canvasW, canvasH, uiScale);
        renderOverlays(batch, client, uiScale, canvasW, canvasH);
        batch.end();
    }

    public static void updateDrag(MinecraftClient client, float uiScale, float canvasW, float canvasH) {
        if (client == null || client.getWindow() == null) return;
        if (!(client.currentScreen instanceof ChatScreen)) {
            if (activeResizeModule != null) {
                cancelResize();
            }
            hoveredResizeTarget = null;
            resizeHoverAnim = 0.0f;
            onMouseReleased();
            return;
        }

        if (draggingResizeHandle && activeResizeModule != null) {
            long handle = client.getWindow().getHandle();
            if (handle == 0L || GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                draggingResizeHandle = false;
                return;
            }
            int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
            int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
            float mx = getCursorCanvasX(client, fbW, uiScale);
            float my = getCursorCanvasY(client, fbH, uiScale);

            float dx = mx - resizeStartMouseX;
            float dy = my - resizeStartMouseY;
            float diagSq = resizeBaseWidth * resizeBaseWidth + resizeBaseHeight * resizeBaseHeight;
            float deltaScale = (dx * resizeBaseWidth + dy * resizeBaseHeight) / Math.max(1.0f, diagSq);
            activeResizeModule.setHudScale(resizeStartScale + deltaScale);
            activeSnapLineX = null;
            activeSnapLineY = null;
            return;
        }

        if (draggingModule == null) {
            activeSnapLineX = null;
            activeSnapLineY = null;
            return;
        }

        long handle = client.getWindow().getHandle();
        if (handle == 0L || GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            onMouseReleased();
            return;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float mx = getCursorCanvasX(client, fbW, uiScale);
        float my = getCursorCanvasY(client, fbH, uiScale);

        float w = draggingModule.getLastWidth();
        float h = draggingModule.getLastHeight();
        float maxW = Math.max(0.0f, canvasW - w);
        float maxH = Math.max(0.0f, canvasH - h);

        float rawX = Math.clamp(mx - grabOffsetX, 0.0f, maxW);
        float rawY = Math.clamp(my - grabOffsetY, 0.0f, maxH);

        // Поиск магнитных прилипаний по X (центр экрана, безопасные отступы 8px, края других HUD)
        float bestDistX = SNAP_THRESHOLD + 0.001f;
        float snappedX = rawX;
        Float lineX = null;

        float centerScreenX = canvasW * 0.5f;
        float distCenterX = Math.abs((rawX + w * 0.5f) - centerScreenX);
        if (distCenterX < bestDistX) {
            bestDistX = distCenterX;
            snappedX = centerScreenX - w * 0.5f;
            lineX = centerScreenX;
        }

        float distLeftMargin = Math.abs(rawX - SAFE_EDGE_MARGIN);
        if (distLeftMargin < bestDistX) {
            bestDistX = distLeftMargin;
            snappedX = SAFE_EDGE_MARGIN;
            lineX = SAFE_EDGE_MARGIN;
        }

        float safeRightEdge = canvasW - SAFE_EDGE_MARGIN;
        float distRightMargin = Math.abs((rawX + w) - safeRightEdge);
        if (distRightMargin < bestDistX) {
            bestDistX = distRightMargin;
            snappedX = safeRightEdge - w;
            lineX = safeRightEdge;
        }

        // Поиск магнитных прилипаний по Y (центр экрана, безопасные отступы 8px, края других HUD)
        float bestDistY = SNAP_THRESHOLD + 0.001f;
        float snappedY = rawY;
        Float lineY = null;

        float centerScreenY = canvasH * 0.5f;
        float distCenterY = Math.abs((rawY + h * 0.5f) - centerScreenY);
        if (distCenterY < bestDistY) {
            bestDistY = distCenterY;
            snappedY = centerScreenY - h * 0.5f;
            lineY = centerScreenY;
        }

        float distTopMargin = Math.abs(rawY - SAFE_EDGE_MARGIN);
        if (distTopMargin < bestDistY) {
            bestDistY = distTopMargin;
            snappedY = SAFE_EDGE_MARGIN;
            lineY = SAFE_EDGE_MARGIN;
        }

        float safeBottomEdge = canvasH - SAFE_EDGE_MARGIN;
        float distBottomMargin = Math.abs((rawY + h) - safeBottomEdge);
        if (distBottomMargin < bestDistY) {
            bestDistY = distBottomMargin;
            snappedY = safeBottomEdge - h;
            lineY = safeBottomEdge;
        }

        // Магнитное выравнивание по краям/центрам других HUD-элементов
        for (HudModule other : ModuleManager.getHudModules()) {
            if (other == draggingModule || !other.isEnabled()) continue;

            float oX = other.getLastRenderedX();
            float oY = other.getLastRenderedY();
            float oW = other.getLastWidth();
            float oH = other.getLastHeight();
            float oRight = oX + oW;
            float oBottom = oY + oH;
            float oCenterX = oX + oW * 0.5f;
            float oCenterY = oY + oH * 0.5f;

            float d;
            if ((d = Math.abs(rawX - oX)) < bestDistX) { bestDistX = d; snappedX = oX; lineX = oX; }
            if ((d = Math.abs(rawX - oRight)) < bestDistX) { bestDistX = d; snappedX = oRight; lineX = oRight; }
            if ((d = Math.abs((rawX + w) - oX)) < bestDistX) { bestDistX = d; snappedX = oX - w; lineX = oX; }
            if ((d = Math.abs((rawX + w) - oRight)) < bestDistX) { bestDistX = d; snappedX = oRight - w; lineX = oRight; }
            if ((d = Math.abs((rawX + w * 0.5f) - oCenterX)) < bestDistX) { bestDistX = d; snappedX = oCenterX - w * 0.5f; lineX = oCenterX; }

            if ((d = Math.abs(rawY - oY)) < bestDistY) { bestDistY = d; snappedY = oY; lineY = oY; }
            if ((d = Math.abs(rawY - oBottom)) < bestDistY) { bestDistY = d; snappedY = oBottom; lineY = oBottom; }
            if ((d = Math.abs((rawY + h) - oY)) < bestDistY) { bestDistY = d; snappedY = oY - h; lineY = oY; }
            if ((d = Math.abs((rawY + h) - oBottom)) < bestDistY) { bestDistY = d; snappedY = oBottom - h; lineY = oBottom; }
            if ((d = Math.abs((rawY + h * 0.5f) - oCenterY)) < bestDistY) { bestDistY = d; snappedY = oCenterY - h * 0.5f; lineY = oCenterY; }
        }

        draggingModule.setHudPosition(Math.clamp(snappedX, 0.0f, maxW), Math.clamp(snappedY, 0.0f, maxH));
        activeSnapLineX = lineX;
        activeSnapLineY = lineY;
    }

    public static void renderOverlays(RenderBatch2D batch, MinecraftClient client, float uiScale, float canvasW, float canvasH) {
        if (client == null || client.getWindow() == null || !(client.currentScreen instanceof ChatScreen)) {
            return;
        }

        float dt = computeOverlayDelta();
        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float mx = getCursorCanvasX(client, fbW, uiScale);
        float my = getCursorCanvasY(client, fbH, uiScale);

        int accent = ClientSettings.getAccentColor();
        int guideColor = ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFFFFFFFF, 0.35f), 0.65f);

        // 1. Тонкие 1px направляющие линии только во время перетаскивания
        if (draggingModule != null) {
            if (activeSnapLineX != null) {
                batch.drawRect(activeSnapLineX, 0.0f, 1.0f, canvasH, guideColor);
            }
            if (activeSnapLineY != null) {
                batch.drawRect(0.0f, activeSnapLineY, canvasW, 1.0f, guideColor);
            }
        }

        // 2. Активный режим масштабирования (тонкая акцентная рамка 1px + угловой ползунок + Apply | Defaults | Cancel)
        if (activeResizeModule != null && activeResizeModule.isEnabled()) {
            float x = activeResizeModule.getLastRenderedX();
            float y = activeResizeModule.getLastRenderedY();
            float w = activeResizeModule.getLastWidth();
            float h = activeResizeModule.getLastHeight();

            float outlineRadius = h > 40.0f ? 15.0f : 11.0f;
            batch.drawOutlineRoundedRect(x - 2.0f, y - 2.0f, w + 4.0f, h + 4.0f, outlineRadius, 1.0f,
                    ColorUtil.withAlpha(accent, 0.70f));

            // Угловой ползунок в правом нижнем углу
            boolean cornerHover = draggingResizeHandle || isOverCornerHandle(mx, my, x, y, w, h);
            float handleX = x + w - 1.5f;
            float handleY = y + h - 1.5f;
            int handleFill = ColorUtil.withAlpha(accent, cornerHover ? 1.0f : 0.85f);
            int handleBorder = cornerHover ? 0xFFFFFFFF : ColorUtil.argb(200, 255, 255, 255);
            batch.drawRoundedRectWithOutline(handleX, handleY, CORNER_HANDLE_SIZE, CORNER_HANDLE_SIZE,
                    2.5f, handleFill, 1.0f, handleBorder);

            // Кнопки Apply | Defaults | Cancel с пропорциональным масштабом buttonScale
            ResizeBarLayout bar = computeResizeActionBar(x, y, w, h, activeResizeModule.getHudScale(), canvasW, canvasH);
            float radius = 6.0f * bar.buttonScale;

            boolean applyHover = mx >= bar.applyX && mx <= bar.applyX + bar.applyW && my >= bar.barY && my <= bar.barY + bar.btnH;
            int applyFill = ColorUtil.withAlpha(accent, applyHover ? 0.96f : 0.82f);
            int applyStroke = ColorUtil.argb(applyHover ? 185 : 105, 255, 255, 255);
            batch.drawRoundedRectWithOutline(bar.applyX, bar.barY, bar.applyW, bar.btnH, radius, applyFill, 1.0f, applyStroke);

            boolean defHover = mx >= bar.defaultsX && mx <= bar.defaultsX + bar.defaultsW && my >= bar.barY && my <= bar.barY + bar.btnH;
            int defFill = ColorUtil.argb(defHover ? 225 : 190, 14, 17, 22);
            int defStroke = defHover ? ColorUtil.withAlpha(accent, 0.85f) : ColorUtil.argb(62, 255, 255, 255);
            batch.drawRoundedRectWithOutline(bar.defaultsX, bar.barY, bar.defaultsW, bar.btnH, radius, defFill, 1.0f, defStroke);

            boolean cancelHover = mx >= bar.cancelX && mx <= bar.cancelX + bar.cancelW && my >= bar.barY && my <= bar.barY + bar.btnH;
            int cancelFill = ColorUtil.argb(cancelHover ? 225 : 190, 14, 17, 22);
            int cancelStroke = ColorUtil.argb(cancelHover ? 120 : 62, 255, 255, 255);
            batch.drawRoundedRectWithOutline(bar.cancelX, bar.barY, bar.cancelW, bar.btnH, radius, cancelFill, 1.0f, cancelStroke);

            if (FontRenderer.hasDefaultFont()) {
                float textSize = 10.0f * bar.buttonScale;
                float textY = bar.barY + (bar.btnH - FontRenderer.getHeight(textSize)) * 0.5f;
                FontRenderer.drawCenteredText(batch, LocalizationManager.tr("hud_editor.apply"), bar.applyX + bar.applyW * 0.5f, textY, textSize, 0xFFFFFFFF);
                FontRenderer.drawCenteredText(batch, LocalizationManager.tr("hud_editor.defaults"), bar.defaultsX + bar.defaultsW * 0.5f, textY, textSize,
                        defHover ? 0xFFFFFFFF : 0xFFD1D5DB);
                FontRenderer.drawCenteredText(batch, LocalizationManager.tr("hud_editor.cancel"), bar.cancelX + bar.cancelW * 0.5f, textY, textSize,
                        cancelHover ? 0xFFFFFFFF : 0xFFD1D5DB);
            }
            return;
        }

        // 3. Обычное состояние: без постоянных рамок! Только плавная мини-кнопка "Resize" при наведении
        float resizeBtnW = getResizeBtnW();
        HudModule newHover = null;
        if (draggingModule == null) {
            List<HudModule> hudModules = ModuleManager.getHudModules();
            for (int i = hudModules.size() - 1; i >= 0; i--) {
                HudModule m = hudModules.get(i);
                if (!m.isEnabled()) continue;
                float x = m.getLastRenderedX();
                float y = m.getLastRenderedY();
                float w = m.getLastWidth();
                float h = m.getLastHeight();
                boolean overHud = mx >= x && mx <= x + w && my >= y && my <= y + h;

                float btnX = computeResizeBtnX(x, w, canvasW, resizeBtnW);
                float btnY = computeBottomBarY(y, h, RESIZE_BTN_H, 4.0f, canvasH);
                boolean overBtnZone = (m == hoveredResizeTarget && resizeHoverAnim > 0.05f)
                        && mx >= btnX - 4.0f && mx <= btnX + resizeBtnW + 4.0f
                        && my >= Math.min(y + h, btnY) - 4.0f
                        && my <= Math.max(y + h, btnY + RESIZE_BTN_H) + 4.0f;
                if (overHud || overBtnZone) {
                    newHover = m;
                    break;
                }
            }
        }

        if (newHover != null) {
            if (hoveredResizeTarget != newHover) {
                hoveredResizeTarget = newHover;
                resizeHoverAnim = 0.0f;
            }
            resizeHoverAnim = AnimationEngine.expLerp(resizeHoverAnim, 1.0f, 16.0f, dt);
        } else {
            resizeHoverAnim = AnimationEngine.expLerp(resizeHoverAnim, 0.0f, 16.0f, dt);
            if (resizeHoverAnim <= 0.02f) {
                hoveredResizeTarget = null;
            }
        }

        if (hoveredResizeTarget != null && hoveredResizeTarget.isEnabled() && resizeHoverAnim > 0.02f) {
            float x = hoveredResizeTarget.getLastRenderedX();
            float y = hoveredResizeTarget.getLastRenderedY();
            float w = hoveredResizeTarget.getLastWidth();
            float h = hoveredResizeTarget.getLastHeight();

            float btnX = computeResizeBtnX(x, w, canvasW, resizeBtnW);
            float btnY = computeBottomBarY(y, h, RESIZE_BTN_H, 4.0f, canvasH);
            boolean btnHovered = mx >= btnX && mx <= btnX + resizeBtnW && my >= btnY && my <= btnY + RESIZE_BTN_H;

            float slideY = (1.0f - resizeHoverAnim) * 4.0f;
            float drawY = btnY + slideY;
            int bg = ColorUtil.argb((int) ((btnHovered ? 225 : 190) * resizeHoverAnim), 14, 17, 23);
            int stroke = ColorUtil.withAlpha(accent, (btnHovered ? 0.95f : 0.65f) * resizeHoverAnim);
            batch.drawRoundedRectWithOutline(btnX, drawY, resizeBtnW, RESIZE_BTN_H, 6.0f, bg, 1.0f, stroke);

            if (FontRenderer.hasDefaultFont()) {
                float textSize = 9.5f;
                float textY = drawY + (RESIZE_BTN_H - FontRenderer.getHeight(textSize)) * 0.5f;
                FontRenderer.drawCenteredText(
                        batch,
                        LocalizationManager.tr("hud_editor.resize"),
                        btnX + resizeBtnW * 0.5f,
                        textY,
                        textSize,
                        ColorUtil.withAlpha(0xFFFFFFFF, resizeHoverAnim)
                );
            }
        }
    }

    private record ResizeBarLayout(
            float buttonScale,
            float applyX, float applyW,
            float defaultsX, float defaultsW,
            float cancelX, float cancelW,
            float barY, float btnH
    ) {}

    private static float getResizeBtnW() {
        if (!FontRenderer.hasDefaultFont()) {
            return RESIZE_BTN_W;
        }
        return Math.max(RESIZE_BTN_W, FontRenderer.getWidth(LocalizationManager.tr("hud_editor.resize"), 9.5f) + 14.0f);
    }

    private static ResizeBarLayout computeResizeActionBar(float x, float y, float w, float h,
                                                          float hudScale, float canvasW, float canvasH) {
        float buttonScale = Math.clamp(1.0f + (hudScale - 1.0f) * 0.45f, 0.95f, 1.55f);
        float applyBase = FontRenderer.hasDefaultFont()
                ? Math.max(ACTION_BTN_W, FontRenderer.getWidth(LocalizationManager.tr("hud_editor.apply"), 10.0f) + 14.0f)
                : ACTION_BTN_W;
        float defBase = FontRenderer.hasDefaultFont()
                ? Math.max(56.0f, FontRenderer.getWidth(LocalizationManager.tr("hud_editor.defaults"), 10.0f) + 14.0f)
                : 56.0f;
        float cancelBase = FontRenderer.hasDefaultFont()
                ? Math.max(ACTION_BTN_W, FontRenderer.getWidth(LocalizationManager.tr("hud_editor.cancel"), 10.0f) + 14.0f)
                : ACTION_BTN_W;
        float applyW = applyBase * buttonScale;
        float defaultsW = defBase * buttonScale;
        float cancelW = cancelBase * buttonScale;
        float btnH = ACTION_BTN_H * buttonScale;
        float gap = ACTION_BTN_GAP * buttonScale;
        float totalW = applyW + gap + defaultsW + gap + cancelW;
        float barX = Math.clamp(x + w * 0.5f - totalW * 0.5f, 4.0f, Math.max(4.0f, canvasW - totalW - 4.0f));
        float barY = computeBottomBarY(y, h, btnH, 6.0f * buttonScale, canvasH);
        float defaultsX = barX + applyW + gap;
        float cancelX = defaultsX + defaultsW + gap;
        return new ResizeBarLayout(buttonScale, barX, applyW, defaultsX, defaultsW, cancelX, cancelW, barY, btnH);
    }

    private static float computeResizeBtnX(float x, float w, float canvasW, float resizeBtnW) {
        return Math.clamp(x + w - resizeBtnW, 4.0f, Math.max(4.0f, canvasW - resizeBtnW - 4.0f));
    }

    private static float computeBottomBarY(float y, float h, float barH, float gap, float canvasH) {
        float below = y + h + gap;
        if (below + barH <= canvasH - 4.0f) {
            return below;
        }
        return Math.max(4.0f, y - barH - gap);
    }

    private static boolean isOverCornerHandle(float mx, float my, float x, float y, float w, float h) {
        float handleX = x + w - 1.5f;
        float handleY = y + h - 1.5f;
        return mx >= handleX - CORNER_HIT_PAD
                && mx <= handleX + CORNER_HANDLE_SIZE + CORNER_HIT_PAD
                && my >= handleY - CORNER_HIT_PAD
                && my <= handleY + CORNER_HANDLE_SIZE + CORNER_HIT_PAD;
    }

    private static float computeOverlayDelta() {
        long now = System.nanoTime();
        if (lastOverlayNs == 0L) {
            lastOverlayNs = now;
            return 0.016f;
        }
        float dt = (now - lastOverlayNs) * 1e-9f;
        lastOverlayNs = now;
        return Math.clamp(dt, 0.0005f, 0.05f);
    }

    private static float getCursorCanvasX(MinecraftClient client, int fbW, float uiScale) {
        long handle = client.getWindow().getHandle();
        if (handle == 0L) return 0.0f;
        GLFW.glfwGetCursorPos(handle, CURSOR_X_BUF, CURSOR_Y_BUF);
        double fbScaleX = (double) fbW / Math.max(1, client.getWindow().getWidth());
        return (float) ((CURSOR_X_BUF[0] * fbScaleX) / uiScale);
    }

    private static float getCursorCanvasY(MinecraftClient client, int fbH, float uiScale) {
        long handle = client.getWindow().getHandle();
        if (handle == 0L) return 0.0f;
        GLFW.glfwGetCursorPos(handle, CURSOR_X_BUF, CURSOR_Y_BUF);
        double fbScaleY = (double) fbH / Math.max(1, client.getWindow().getHeight());
        return (float) ((CURSOR_Y_BUF[0] * fbScaleY) / uiScale);
    }
}
