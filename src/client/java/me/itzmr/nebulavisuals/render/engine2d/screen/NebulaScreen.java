package me.itzmr.nebulavisuals.render.engine2d.screen;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Базовый класс для экранов Nebula Visuals (Главное меню, ClickGUI, HUD редакторы).
 *
 * Особенности:
 * - Математически строгий Uniform-адаптив (uiScale от базового 1920x1080 и физических пикселей фреймбуфера),
 *   гарантирующий соотношение сторон 1:1 на любых разрешениях (4:3, 16:9, 21:9, 32:9, малые окна).
 * - Пересчёт координат курсора напрямую через GLFW / uiScale без погрешностей ванильного guiScale.
 * - Прямая интеграция с батчером RenderBatch2D и Dual Kawase Blur.
 */
public abstract class NebulaScreen extends Screen {
    protected float uiScale = 1.0f;
    protected float canvasWidth = 1920.0f;
    protected float canvasHeight = 1080.0f;
    protected int framebufferWidth = 1920;
    protected int framebufferHeight = 1080;

    // Алиасы для совместимости с наследниками
    protected float virtualWidth = 1920.0f;
    protected float virtualHeight = 1080.0f;

    private final double[] cursorXBuf = new double[1];
    private final double[] cursorYBuf = new double[1];

    protected boolean enableBackgroundBlur = true;
    protected int blurIterations = 4;
    protected float blurOffset = 2.0f;
    protected int blurTint = 0x88000000;

    protected NebulaScreen(Text title) {
        super(title);
    }

    protected NebulaScreen() {
        this(Text.literal("Nebula Visuals"));
    }

    @Override
    protected void init() {
        updateViewportMetrics();
        super.init();
        onInit();
    }

    @Override
    public void resize(int width, int height) {
        updateViewportMetrics();
        if (enableBackgroundBlur && client != null && client.getWindow() != null) {
            KawaseBlur blur = NebulaRenderEngine.getBlur();
            if (blur != null) {
                blur.ensureCapacity(framebufferWidth, framebufferHeight, blurIterations);
            }
        }
        super.resize(width, height);
    }

    /**
     * Базовый адаптивный масштаб для HUD-элементов (зависит только от разрешения экрана и изолирован от ClickGUI Scale).
     */
    public static float computeHudBaseScale(int fbW, int fbH) {
        float scaleX = fbW / 1920.0f;
        float scaleY = fbH / 1080.0f;
        return Math.max(0.55f, Math.min(scaleX, scaleY));
    }

    /**
     * Единый адаптивный масштаб для экранов ClickGUI с учётом настройки ClickGUI Scale.
     */
    public static float computeEffectiveUiScale(int fbW, int fbH, boolean clampForModalWindow) {
        float baseScale = computeHudBaseScale(fbW, fbH);
        float desiredScale = baseScale * ClientSettings.getClickGuiScale();
        if (clampForModalWindow) {
            float maxSafeScale = Math.max(0.55f, Math.min(fbW / 460.0f, fbH / 340.0f));
            return Math.min(desiredScale, maxSafeScale);
        }
        return desiredScale;
    }

    /**
     * Обновляет единый uiScale и размеры адаптивного холста с квадратными пикселями (1:1).
     */
    protected void updateViewportMetrics() {
        if (client == null || client.getWindow() == null) return;
        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        this.framebufferWidth = fbW;
        this.framebufferHeight = fbH;

        this.uiScale = computeEffectiveUiScale(fbW, fbH, true);
        this.canvasWidth = fbW / this.uiScale;
        this.canvasHeight = fbH / this.uiScale;
        this.virtualWidth = this.canvasWidth;
        this.virtualHeight = this.canvasHeight;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Подавляем ванильный фон, чтобы GuiRenderState не перекрывал наш 2D батчер.
    }

    @Override
    public void renderInGameBackground(DrawContext context) {
        // Пустая реализация
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

        int blurredTex = 0;
        if (enableBackgroundBlur && client != null && client.getFramebuffer() != null) {
            int fbTexture = 0;
            if (client.getFramebuffer().getColorAttachment() instanceof GlTexture glTexture) {
                fbTexture = glTexture.getGlId();
            }

            if (fbTexture != 0) {
                blurredTex = blur.renderBlur(fbTexture, framebufferWidth, framebufferHeight, blurIterations, blurOffset);
            }
        }

        batch.begin(canvasWidth, canvasHeight, uiScale);

        if (blurredTex != 0) {
            batch.drawTexturedRect(blurredTex, 0, 0, canvasWidth, canvasHeight, 0.0f, 1.0f, 1.0f, 0.0f, blurTint);
        }

        onRender(batch, vMouseX, vMouseY, dt);

        batch.end();
    }

    // =========================================================================
    // Хуки жизненного цикла экрана
    // =========================================================================

    protected void onInit() {}

    protected abstract void onRender(RenderBatch2D batch, float mouseX, float mouseY, float dt);

    protected void onTickAnimations(float dt) {}

    // =========================================================================
    // Виртуальные координаты и физический ввод мыши (GLFW -> canvas / uiScale)
    // =========================================================================

    protected float getCursorCanvasX(double fallbackScreenX) {
        if (client != null && client.getWindow() != null) {
            long handle = client.getWindow().getHandle();
            if (handle != 0L) {
                GLFW.glfwGetCursorPos(handle, cursorXBuf, cursorYBuf);
                double fbScaleX = (double) framebufferWidth / Math.max(1, client.getWindow().getWidth());
                return (float) ((cursorXBuf[0] * fbScaleX) / uiScale);
            }
        }
        return toVirtualX(fallbackScreenX);
    }

    protected float getCursorCanvasY(double fallbackScreenY) {
        if (client != null && client.getWindow() != null) {
            long handle = client.getWindow().getHandle();
            if (handle != 0L) {
                GLFW.glfwGetCursorPos(handle, cursorXBuf, cursorYBuf);
                double fbScaleY = (double) framebufferHeight / Math.max(1, client.getWindow().getHeight());
                return (float) ((cursorYBuf[0] * fbScaleY) / uiScale);
            }
        }
        return toVirtualY(fallbackScreenY);
    }

    public float toVirtualX(double screenX) {
        return (float) (screenX * (canvasWidth / Math.max(1.0, this.width)));
    }

    public float toVirtualY(double screenY) {
        return (float) (screenY * (canvasHeight / Math.max(1.0, this.height)));
    }

    public float getUiScale() {
        return uiScale;
    }

    public float getVirtualWidth() {
        return canvasWidth;
    }

    public float getVirtualHeight() {
        return canvasHeight;
    }

    public void setVirtualResolution(float virtualWidth, float virtualHeight) {
        this.canvasWidth = Math.max(1.0f, virtualWidth);
        this.canvasHeight = Math.max(1.0f, virtualHeight);
        this.virtualWidth = this.canvasWidth;
        this.virtualHeight = this.canvasHeight;
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        updateViewportMetrics();
        float vx = getCursorCanvasX(click.x());
        float vy = getCursorCanvasY(click.y());
        if (onMouseClicked(vx, vy, click.button())) {
            return true;
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseReleased(Click click) {
        updateViewportMetrics();
        float vx = getCursorCanvasX(click.x());
        float vy = getCursorCanvasY(click.y());
        if (onMouseReleased(vx, vy, click.button())) {
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        updateViewportMetrics();
        float scaleX = (float) (canvasWidth / Math.max(1.0, this.width));
        float scaleY = (float) (canvasHeight / Math.max(1.0, this.height));
        float vx = getCursorCanvasX(click.x());
        float vy = getCursorCanvasY(click.y());
        if (onMouseDragged(vx, vy, click.button(), deltaX * scaleX, deltaY * scaleY)) {
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        updateViewportMetrics();
        if (onMouseScrolled(getCursorCanvasX(mouseX), getCursorCanvasY(mouseY), horizontalAmount, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (onKeyPressed(keyInput.key(), keyInput.scancode(), keyInput.modifiers())) {
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public boolean keyReleased(KeyInput keyInput) {
        if (onKeyReleased(keyInput.key(), keyInput.scancode(), keyInput.modifiers())) {
            return true;
        }
        return super.keyReleased(keyInput);
    }

    @Override
    public boolean charTyped(CharInput charInput) {
        if (onCharTyped((char) charInput.codepoint(), charInput.modifiers())) {
            return true;
        }
        return super.charTyped(charInput);
    }

    protected boolean onMouseClicked(float mouseX, float mouseY, int button) { return false; }
    protected boolean onMouseReleased(float mouseX, float mouseY, int button) { return false; }
    protected boolean onMouseDragged(float mouseX, float mouseY, int button, double deltaX, double deltaY) { return false; }
    protected boolean onMouseScrolled(float mouseX, float mouseY, double horizontalAmount, double verticalAmount) { return false; }
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    protected boolean onKeyReleased(int keyCode, int scanCode, int modifiers) { return false; }
    protected boolean onCharTyped(char chr, int modifiers) { return false; }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
