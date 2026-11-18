package me.itzmr.nebulavisuals.render.engine2d.batch;

import me.itzmr.nebulavisuals.render.engine2d.shader.ShaderProgram;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine2d.util.GlStateTracker;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * Высокопроизводительный Off-Heap 2D-батчер для интерфейса Nebula Visuals.
 * 
 * Особенности архитектуры:
 * - Выделение нативной памяти через MemoryUtil.memAlloc (полное отсутствие GC аллокаций на кадр).
 * - Единый убер-шейдер nebula_2d и единый VertexLayout для текста, форм, теней и текстур.
 * - Статический предзагруженный EBO в GPU памяти для 16 384 четырехугольников.
 * - Автоматический flush() при смене текстуры или заполнении вершинного буфера.
 * - Встроенное сохранение и восстановление контекста OpenGL.
 */
public class RenderBatch2D implements AutoCloseable {
    private static final int MAX_QUADS = 16384;
    private static final int MAX_VERTICES = MAX_QUADS * 4;
    private static final int MAX_INDICES = MAX_QUADS * 6;
    private static final int BUFFER_CAPACITY = MAX_VERTICES * VertexLayout.STRIDE;

    private static final Logger LOGGER = LoggerFactory.getLogger("NebulaBatch2D");
    private static long lastErrorLogTime = 0L;

    private final GlStateTracker stateTracker = new GlStateTracker();
    private final ShaderProgram shader;

    private int vao;
    private int vbo;
    private int ebo;
    private int whiteTextureId;
    private int targetFbo;

    private ByteBuffer nativeBuffer;
    private long nativeAddress; // прямой адрес для мгновенной записи без проверок границ

    private int vertexCount = 0;
    private int frameVertexOffset = 0;
    private int currentTextureId = -1;
    private boolean isDrawing = false;
    private float currentUiScale = 1.0f;
    private float currentPxRange = 4.0f;
    private float boundPxRange = -1.0f;
    private final java.util.Deque<int[]> scissorStack = new java.util.ArrayDeque<>();

    public RenderBatch2D() {
        this.shader = ShaderProgram.fromResources(
                "/assets/nebulavisuals/shaders/nebula_2d.vsh",
                "/assets/nebulavisuals/shaders/nebula_2d.fsh"
        );
        initGlBuffers();
        initWhiteTexture();
    }

    private void initGlBuffers() {
        // Выделение памяти вне кучи Java
        nativeBuffer = MemoryUtil.memAlloc(BUFFER_CAPACITY);
        nativeAddress = MemoryUtil.memAddress(nativeBuffer);

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();
        targetFbo = GL30.glGenFramebuffers();

        GL30.glBindVertexArray(vao);

        // VBO динамический
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, BUFFER_CAPACITY, GL15.GL_STREAM_DRAW);

        // EBO статический с шаблоном [0, 1, 2, 2, 3, 0]
        ShortBuffer indices = MemoryUtil.memAllocShort(MAX_INDICES);
        try {
            for (int i = 0, v = 0; i < MAX_INDICES; i += 6, v += 4) {
                indices.put((short) v);
                indices.put((short) (v + 1));
                indices.put((short) (v + 2));
                indices.put((short) (v + 2));
                indices.put((short) (v + 3));
                indices.put((short) v);
            }
            indices.flip();

            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(indices);
        }

        VertexLayout.setupAttributes();

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private void initWhiteTexture() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        whiteTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTextureId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pixel = stack.malloc(4);
            pixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    /**
     * Начало кадра батчера с виртуальным разрешением и единым uiScale = 1.0.
     */
    public void begin(float viewportWidth, float viewportHeight) {
        begin(viewportWidth, viewportHeight, 1.0f);
    }

    /**
     * Начало кадра батчера с адаптивным виртуальным холстом и физическим масштабом uiScale.
     */
    public void begin(float viewportWidth, float viewportHeight, float uiScale) {
        if (isDrawing) {
            throw new IllegalStateException("RenderBatch2D.begin() called twice without end()!");
        }
        isDrawing = true;
        this.currentUiScale = Math.max(0.25f, uiScale);

        stateTracker.push();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null && client.getWindow() != null) {
            int fbW = client.getWindow().getFramebufferWidth();
            int fbH = client.getWindow().getFramebufferHeight();
            if (fbW > 0 && fbH > 0) {
                GL11.glViewport(0, 0, fbW, fbH);
            }
        }

        // Перенаправляем рендер напрямую в цветовую текстуру главного Framebuffer Minecraft (1.21.11 GpuTexture),
        // чтобы результат не затирался ванильным blitToScreen() / presentTexture() при выводе кадра в окно GLFW.
        if (client != null && client.getFramebuffer() != null) {
            if (client.getFramebuffer().getColorAttachment() instanceof net.minecraft.client.texture.GlTexture glTexture) {
                int colorTex = glTexture.getGlId();
                if (colorTex > 0) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
                    GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);
                }
            }
        }

        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);

        shader.bind();
        float safeW = Math.max(1.0f, viewportWidth);
        float safeH = Math.max(1.0f, viewportHeight);
        shader.setOrthoProjection("uProjection", 0.0f, safeW, safeH, 0.0f, -1000.0f, 1000.0f);
        shader.setUniform1i("uTexture", 0);
        shader.setUniform1f("u_UiScale", this.currentUiScale);
        shader.setUniform1f("u_PxRange", this.currentPxRange);
        boundPxRange = this.currentPxRange;

        // Один общий динамический VBO на весь кадр (orphan 1 раз в начале кадра вместо каждого flush())
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.nglBufferData(GL15.GL_ARRAY_BUFFER, BUFFER_CAPACITY, 0L, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        frameVertexOffset = 0;
        currentTextureId = whiteTextureId;
        vertexCount = 0;
        nativeBuffer.clear();
    }

    public float getUiScale() {
        return currentUiScale;
    }

    public float snapToPixel(float coord) {
        return Math.round(coord * currentUiScale) / currentUiScale;
    }

    public void setMsdfPxRange(float pxRange) {
        this.currentPxRange = Math.max(1.0f, pxRange);
    }

    /**
     * Проверка на необходимость отправки данных на GPU.
     */
    private void checkFlush(int textureId) {
        if (vertexCount + 4 > MAX_VERTICES || (this.currentTextureId != textureId && vertexCount > 0)) {
            flush();
        }
        this.currentTextureId = textureId;
    }

    /**
     * Быстрая off-heap запись вершины по нативному адресу.
     */
    private void emitVertex(float x, float y, float u, float v, int colorRgba,
                            float lx, float ly, float w, float h,
                            float rTL, float rTR, float rBR, float rBL,
                            float primType, float outlineWidth, float softness, float extra,
                            int outlineRgba) {
        long ptr = nativeAddress + (long) vertexCount * VertexLayout.STRIDE;

        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, u);
        MemoryUtil.memPutFloat(ptr + 12, v);
        MemoryUtil.memPutInt(ptr + 16, colorRgba);
        MemoryUtil.memPutFloat(ptr + 20, lx);
        MemoryUtil.memPutFloat(ptr + 24, ly);
        MemoryUtil.memPutFloat(ptr + 28, w);
        MemoryUtil.memPutFloat(ptr + 32, h);
        MemoryUtil.memPutFloat(ptr + 36, rTL);
        MemoryUtil.memPutFloat(ptr + 40, rTR);
        MemoryUtil.memPutFloat(ptr + 44, rBR);
        MemoryUtil.memPutFloat(ptr + 48, rBL);
        MemoryUtil.memPutFloat(ptr + 52, primType);
        MemoryUtil.memPutFloat(ptr + 56, outlineWidth);
        MemoryUtil.memPutFloat(ptr + 60, softness);
        MemoryUtil.memPutFloat(ptr + 64, extra);
        MemoryUtil.memPutInt(ptr + 68, outlineRgba);

        vertexCount++;
    }

    // =========================================================================
    // Примитивы: Прямоугольники и Скругления (SDF)
    // =========================================================================

    public void drawRect(float x, float y, float w, float h, int argbColor) {
        checkFlush(whiteTextureId);
        int rgba = ColorUtil.argbToRgba(argbColor);

        emitVertex(x,     y,     0, 0, rgba, 0, 0, w, h, 0, 0, 0, 0, 0, 0, 1, 0, 0);
        emitVertex(x,     y + h, 0, 1, rgba, 0, h, w, h, 0, 0, 0, 0, 0, 0, 1, 0, 0);
        emitVertex(x + w, y + h, 1, 1, rgba, w, h, w, h, 0, 0, 0, 0, 0, 0, 1, 0, 0);
        emitVertex(x + w, y,     1, 0, rgba, w, 0, w, h, 0, 0, 0, 0, 0, 0, 1, 0, 0);
    }

    public void drawGradientRect(float x, float y, float w, float h, int cTL, int cTR, int cBR, int cBL) {
        checkFlush(whiteTextureId);
        int rgbaTL = ColorUtil.argbToRgba(cTL);
        int rgbaTR = ColorUtil.argbToRgba(cTR);
        int rgbaBR = ColorUtil.argbToRgba(cBR);
        int rgbaBL = ColorUtil.argbToRgba(cBL);

        emitVertex(x,     y,     0, 0, rgbaTL, 0, 0, w, h, 0, 0, 0, 0, 0, 0, 1, 0, 0);
        emitVertex(x,     y + h, 0, 1, rgbaBL, 0, h, w, h, 0, 0, 0, 0, 0, 0, 1, 0, 0);
        emitVertex(x + w, y + h, 1, 1, rgbaBR, w, h, w, h, 0, 0, 0, 0, 0, 0, 1, 0, 0);
        emitVertex(x + w, y,     1, 0, rgbaTR, w, 0, w, h, 0, 0, 0, 0, 0, 0, 1, 0, 0);
    }

    public void drawRoundedRect(float x, float y, float w, float h, float radius, int argbColor) {
        drawRoundedRect(x, y, w, h, radius, radius, radius, radius, argbColor);
    }

    public void drawRoundedRect(float x, float y, float w, float h, float rTL, float rTR, float rBR, float rBL, int argbColor) {
        checkFlush(whiteTextureId);
        int rgba = ColorUtil.argbToRgba(argbColor);

        emitVertex(x,     y,     0, 0, rgba, 0, 0, w, h, rTL, rTR, rBR, rBL, 1, 0, 1, 0, 0);
        emitVertex(x,     y + h, 0, 1, rgba, 0, h, w, h, rTL, rTR, rBR, rBL, 1, 0, 1, 0, 0);
        emitVertex(x + w, y + h, 1, 1, rgba, w, h, w, h, rTL, rTR, rBR, rBL, 1, 0, 1, 0, 0);
        emitVertex(x + w, y,     1, 0, rgba, w, 0, w, h, rTL, rTR, rBR, rBL, 1, 0, 1, 0, 0);
    }

    public void drawGradientRoundedRect(float x, float y, float w, float h, float radius, int cTL, int cTR, int cBR, int cBL) {
        drawGradientRoundedRect(x, y, w, h, radius, radius, radius, radius, cTL, cTR, cBR, cBL);
    }

    public void drawGradientRoundedRect(float x, float y, float w, float h, float rTL, float rTR, float rBR, float rBL, int cTL, int cTR, int cBR, int cBL) {
        checkFlush(whiteTextureId);
        int rgbaTL = ColorUtil.argbToRgba(cTL);
        int rgbaTR = ColorUtil.argbToRgba(cTR);
        int rgbaBR = ColorUtil.argbToRgba(cBR);
        int rgbaBL = ColorUtil.argbToRgba(cBL);

        emitVertex(x,     y,     0, 0, rgbaTL, 0, 0, w, h, rTL, rTR, rBR, rBL, 1, 0, 1, 0, 0);
        emitVertex(x,     y + h, 0, 1, rgbaBL, 0, h, w, h, rTL, rTR, rBR, rBL, 1, 0, 1, 0, 0);
        emitVertex(x + w, y + h, 1, 1, rgbaBR, w, h, w, h, rTL, rTR, rBR, rBL, 1, 0, 1, 0, 0);
        emitVertex(x + w, y,     1, 0, rgbaTR, w, 0, w, h, rTL, rTR, rBR, rBL, 1, 0, 1, 0, 0);
    }

    public void drawOutlineRoundedRect(float x, float y, float w, float h, float radius, float strokeWidth, int strokeColor) {
        checkFlush(whiteTextureId);
        int strokeRgba = ColorUtil.argbToRgba(strokeColor);

        emitVertex(x,     y,     0, 0, 0, 0, 0, w, h, radius, radius, radius, radius, 1, strokeWidth, 1, 0, strokeRgba);
        emitVertex(x,     y + h, 0, 1, 0, 0, h, w, h, radius, radius, radius, radius, 1, strokeWidth, 1, 0, strokeRgba);
        emitVertex(x + w, y + h, 1, 1, 0, w, h, w, h, radius, radius, radius, radius, 1, strokeWidth, 1, 0, strokeRgba);
        emitVertex(x + w, y,     1, 0, 0, w, 0, w, h, radius, radius, radius, radius, 1, strokeWidth, 1, 0, strokeRgba);
    }

    public void drawRoundedRectWithOutline(float x, float y, float w, float h, float radius, int fillColor, float strokeWidth, int strokeColor) {
        checkFlush(whiteTextureId);
        int fillRgba = ColorUtil.argbToRgba(fillColor);
        int strokeRgba = ColorUtil.argbToRgba(strokeColor);

        emitVertex(x,     y,     0, 0, fillRgba, 0, 0, w, h, radius, radius, radius, radius, 1, strokeWidth, 1, 0, strokeRgba);
        emitVertex(x,     y + h, 0, 1, fillRgba, 0, h, w, h, radius, radius, radius, radius, 1, strokeWidth, 1, 0, strokeRgba);
        emitVertex(x + w, y + h, 1, 1, fillRgba, w, h, w, h, radius, radius, radius, radius, 1, strokeWidth, 1, 0, strokeRgba);
        emitVertex(x + w, y,     1, 0, fillRgba, w, 0, w, h, radius, radius, radius, radius, 1, strokeWidth, 1, 0, strokeRgba);
    }

    /**
     * Отрисовка мягкой тени/свечения для скругленного прямоугольника.
     */
    public void drawShadow(float x, float y, float w, float h, float radius, float blurRadius, int shadowColor) {
        checkFlush(whiteTextureId);
        int shadowRgba = ColorUtil.argbToRgba(shadowColor);

        // Расширяем квад на величину размытия, чтобы тень плавно сошла на нет
        float padding = blurRadius * 1.5f;
        float qx = x - padding;
        float qy = y - padding;
        float qw = w + padding * 2.0f;
        float qh = h + padding * 2.0f;

        emitVertex(qx,      qy,      0, 0, shadowRgba, -padding,     -padding,      w, h, radius, radius, radius, radius, 1, 0, blurRadius, 0, 0);
        emitVertex(qx,      qy + qh, 0, 1, shadowRgba, -padding,     h + padding,   w, h, radius, radius, radius, radius, 1, 0, blurRadius, 0, 0);
        emitVertex(qx + qw, qy + qh, 1, 1, shadowRgba, w + padding,  h + padding,   w, h, radius, radius, radius, radius, 1, 0, blurRadius, 0, 0);
        emitVertex(qx + qw, qy,      1, 0, shadowRgba, w + padding,  -padding,      w, h, radius, radius, radius, radius, 1, 0, blurRadius, 0, 0);
    }

    // =========================================================================
    // Текстурированные элементы
    // =========================================================================

    public void drawTexturedRect(int textureId, float x, float y, float w, float h, float u1, float v1, float u2, float v2, int tintArgb) {
        checkFlush(textureId);
        int rgba = ColorUtil.argbToRgba(tintArgb);

        emitVertex(x,     y,     u1, v1, rgba, 0, 0, w, h, 0, 0, 0, 0, 2, 0, 1, 0, 0);
        emitVertex(x,     y + h, u1, v2, rgba, 0, h, w, h, 0, 0, 0, 0, 2, 0, 1, 0, 0);
        emitVertex(x + w, y + h, u2, v2, rgba, w, h, w, h, 0, 0, 0, 0, 2, 0, 1, 0, 0);
        emitVertex(x + w, y,     u2, v1, rgba, w, 0, w, h, 0, 0, 0, 0, 2, 0, 1, 0, 0);
    }

    /**
     * Отрисовывает повёрнутый текстурированный прямоугольник вокруг его геометрического центра {@code (centerX, centerY)}.
     */
    public void drawRotatedTexturedRect(int textureId, float centerX, float centerY, float w, float h,
                                        float rotationDegrees, float u1, float v1, float u2, float v2, int tintArgb) {
        drawRotatedTexturedRectClipped(
                textureId, centerX, centerY, w, h, rotationDegrees,
                u1, v1, u2, v2, tintArgb,
                0.0f, 0.0f, w, h, 0.0f, 0.0f, 0.0f, 0.0f
        );
    }

    /**
     * Отрисовывает повёрнутый вокруг геометрического центра текстурированный прямоугольник
     * с опциональной SDF-обрезкой по скруглённым границам родительской карточки {@code (clipX, clipY, clipW, clipH)}.
     */
    public void drawRotatedTexturedRectClipped(int textureId, float centerX, float centerY, float w, float h,
                                               float rotationDegrees, float u1, float v1, float u2, float v2, int tintArgb,
                                               float clipX, float clipY, float clipW, float clipH,
                                               float rTL, float rTR, float rBR, float rBL) {
        checkFlush(textureId);
        int rgba = ColorUtil.argbToRgba(tintArgb);

        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        double rad = Math.toRadians(rotationDegrees);
        float cos = rotationDegrees == 0.0f ? 1.0f : (float) Math.cos(rad);
        float sin = rotationDegrees == 0.0f ? 0.0f : (float) Math.sin(rad);

        float tlX = centerX + (-halfW * cos - (-halfH) * sin);
        float tlY = centerY + (-halfW * sin + (-halfH) * cos);
        float blX = centerX + (-halfW * cos - halfH * sin);
        float blY = centerY + (-halfW * sin + halfH * cos);
        float brX = centerX + (halfW * cos - halfH * sin);
        float brY = centerY + (halfW * sin + halfH * cos);
        float trX = centerX + (halfW * cos - (-halfH) * sin);
        float trY = centerY + (halfW * sin + (-halfH) * cos);

        boolean clipped = (rTL > 0.0f || rTR > 0.0f || rBR > 0.0f || rBL > 0.0f) && clipW > 0.0f && clipH > 0.0f;
        if (clipped) {
            emitVertex(tlX, tlY, u1, v1, rgba, tlX - clipX, tlY - clipY, clipW, clipH, rTL, rTR, rBR, rBL, 2, 0, 1, 0, 0);
            emitVertex(blX, blY, u1, v2, rgba, blX - clipX, blY - clipY, clipW, clipH, rTL, rTR, rBR, rBL, 2, 0, 1, 0, 0);
            emitVertex(brX, brY, u2, v2, rgba, brX - clipX, brY - clipY, clipW, clipH, rTL, rTR, rBR, rBL, 2, 0, 1, 0, 0);
            emitVertex(trX, trY, u2, v1, rgba, trX - clipX, trY - clipY, clipW, clipH, rTL, rTR, rBR, rBL, 2, 0, 1, 0, 0);
        } else {
            emitVertex(tlX, tlY, u1, v1, rgba, 0, 0, w, h, 0, 0, 0, 0, 2, 0, 1, 0, 0);
            emitVertex(blX, blY, u1, v2, rgba, 0, h, w, h, 0, 0, 0, 0, 2, 0, 1, 0, 0);
            emitVertex(brX, brY, u2, v2, rgba, w, h, w, h, 0, 0, 0, 0, 2, 0, 1, 0, 0);
            emitVertex(trX, trY, u2, v1, rgba, w, 0, w, h, 0, 0, 0, 0, 2, 0, 1, 0, 0);
        }
    }

    public void drawTexturedRoundedRect(int textureId, float x, float y, float w, float h, float radius, float u1, float v1, float u2, float v2, int tintArgb) {
        drawTexturedRoundedRect(textureId, x, y, w, h, radius, radius, radius, radius, u1, v1, u2, v2, tintArgb);
    }

    public void drawTexturedRoundedRect(int textureId, float x, float y, float w, float h,
                                        float rTL, float rTR, float rBR, float rBL,
                                        float u1, float v1, float u2, float v2, int tintArgb) {
        checkFlush(textureId);
        int rgba = ColorUtil.argbToRgba(tintArgb);

        emitVertex(x,     y,     u1, v1, rgba, 0, 0, w, h, rTL, rTR, rBR, rBL, 2, 0, 1, 0, 0);
        emitVertex(x,     y + h, u1, v2, rgba, 0, h, w, h, rTL, rTR, rBR, rBL, 2, 0, 1, 0, 0);
        emitVertex(x + w, y + h, u2, v2, rgba, w, h, w, h, rTL, rTR, rBR, rBL, 2, 0, 1, 0, 0);
        emitVertex(x + w, y,     u2, v1, rgba, w, 0, w, h, rTL, rTR, rBR, rBL, 2, 0, 1, 0, 0);
    }

    // =========================================================================
    // MSDF Текст
    // =========================================================================

    public void drawMsdfGlyph(int atlasTextureId, float x0, float y0, float x1, float y1,
                              float u0, float v0, float u1, float v1,
                              float pxRange, int argbColor) {
        drawMsdfQuad(atlasTextureId, x0, y0, x0, y1, x1, y1, x1, y0, u0, v0, u1, v1, pxRange, argbColor);
    }

    /**
     * Отрисовка произвольного (в т.ч. повёрнутого) MSDF-глифа или иконки по четырём вершинам (TL, BL, BR, TR).
     */
    public void drawMsdfQuad(int atlasTextureId,
                             float tlX, float tlY, float blX, float blY,
                             float brX, float brY, float trX, float trY,
                             float u0, float v0, float u1, float v1,
                             float pxRange, int argbColor) {
        checkFlush(atlasTextureId);
        int rgba = ColorUtil.argbToRgba(argbColor);
        float w = Math.max(1.0f, Math.abs(trX - tlX));
        float h = Math.max(1.0f, Math.abs(blY - tlY));

        emitVertex(tlX, tlY, u0, v0, rgba, 0, 0, w, h, 0, 0, 0, 0, 3, 0, 1, pxRange, 0);
        emitVertex(blX, blY, u0, v1, rgba, 0, h, w, h, 0, 0, 0, 0, 3, 0, 1, pxRange, 0);
        emitVertex(brX, brY, u1, v1, rgba, w, h, w, h, 0, 0, 0, 0, 3, 0, 1, pxRange, 0);
        emitVertex(trX, trY, u1, v0, rgba, w, 0, w, h, 0, 0, 0, 0, 3, 0, 1, pxRange, 0);
    }

    /**
     * Отправка накопленных вершин на видеокарту одним draw call'ом.
     */
    public void flush() {
        int quads = vertexCount / 4;
        int indicesToDraw = quads * 6;
        if (vertexCount <= 0 || indicesToDraw <= 0) {
            vertexCount = 0;
            nativeBuffer.clear();
            return;
        }

        int bytesToWrite = quads * 4 * VertexLayout.STRIDE;
        if (bytesToWrite <= 0 || bytesToWrite > BUFFER_CAPACITY || vao == 0 || vbo == 0 || ebo == 0) {
            vertexCount = 0;
            nativeBuffer.clear();
            return;
        }

        int texToBind = currentTextureId > 0 ? currentTextureId : whiteTextureId;
        if (texToBind <= 0) {
            vertexCount = 0;
            nativeBuffer.clear();
            return;
        }

        if (boundPxRange != currentPxRange) {
            shader.setUniform1f("u_PxRange", currentPxRange);
            boundPxRange = currentPxRange;
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        // Отвязываем GL33 sampler Minecraft, иначе mipmap-фильтр делает single-mip текстуры (MSDF/FBO) невалидными.
        GL33.glBindSampler(0, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texToBind);

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        if (frameVertexOffset + vertexCount > MAX_VERTICES) {
            GL15.nglBufferData(GL15.GL_ARRAY_BUFFER, BUFFER_CAPACITY, 0L, GL15.GL_STREAM_DRAW);
            frameVertexOffset = 0;
        }

        nativeBuffer.position(0);
        nativeBuffer.limit(bytesToWrite);
        long vboOffsetBytes = (long) frameVertexOffset * VertexLayout.STRIDE;
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, vboOffsetBytes, nativeBuffer);

        GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, indicesToDraw, GL11.GL_UNSIGNED_SHORT, 0L, frameVertexOffset);

        frameVertexOffset += vertexCount;

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        vertexCount = 0;
        nativeBuffer.clear();
    }

    /**
     * Включение Scissor-клиппинга с переводом координат холста в физические пиксели OpenGL (y_gl от низа фреймбуфера)
     * и поддержкой вложенного стека (пересечение с родительским прямоугольником).
     */
    public void beginScissor(float vx, float vy, float vw, float vh, float virtualW, float virtualH) {
        flush();
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return;

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float scaleX = (float) fbW / Math.max(1.0f, virtualW);
        float scaleY = (float) fbH / Math.max(1.0f, virtualH);

        int sx = Math.max(0, Math.round(vx * scaleX));
        int sy = Math.max(0, fbH - Math.round((vy + vh) * scaleY));
        int ex = Math.min(fbW, Math.round((vx + vw) * scaleX));
        int ey = Math.min(fbH, fbH - Math.round(vy * scaleY));

        if (!scissorStack.isEmpty()) {
            int[] parent = scissorStack.peek();
            sx = Math.max(sx, parent[0]);
            sy = Math.max(sy, parent[1]);
            ex = Math.min(ex, parent[0] + parent[2]);
            ey = Math.min(ey, parent[1] + parent[3]);
        }

        int sw = Math.max(0, ex - sx);
        int sh = Math.max(0, ey - sy);
        int[] rect = new int[]{sx, sy, sw, sh};
        scissorStack.push(rect);

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, sw, sh);
    }

    /**
     * Отключение текущего Scissor-клиппинга или возврат к родительскому в стеке.
     */
    public void endScissor() {
        flush();
        if (!scissorStack.isEmpty()) {
            scissorStack.pop();
        }
        if (!scissorStack.isEmpty()) {
            int[] parent = scissorStack.peek();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(parent[0], parent[1], parent[2], parent[3]);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    /**
     * Завершение отрисовки и восстановление состояния GL.
     */
    public void end() {
        if (!isDrawing) return;
        flush();
        scissorStack.clear();
        shader.unbind();
        stateTracker.pop();
        isDrawing = false;
    }

    @Override
    public void close() {
        if (nativeBuffer != null) {
            MemoryUtil.memFree(nativeBuffer);
            nativeBuffer = null;
        }
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        if (ebo != 0) GL15.glDeleteBuffers(ebo);
        if (targetFbo != 0) GL30.glDeleteFramebuffers(targetFbo);
        if (whiteTextureId != 0) GL11.glDeleteTextures(whiteTextureId);
        shader.delete();
    }
}
