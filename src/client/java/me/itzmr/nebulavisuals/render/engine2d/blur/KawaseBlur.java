package me.itzmr.nebulavisuals.render.engine2d.blur;

import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.shader.ShaderProgram;
import me.itzmr.nebulavisuals.render.engine2d.util.GlStateTracker;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * Высокопроизводительный Dual Kawase Blur пайплайн.
 * 
 * В разы быстрее и качественнее классического двухпроходного Гаусса (Gaussian Blur).
 * Использует пирамиду FBO с последовательным Downsampling и Upsampling
 * с кастомными 5- и 8-таповыми фильтрами выборки.
 */
public class KawaseBlur implements AutoCloseable {
    private static final int SCALE_DIVISOR = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger("NebulaBlur");

    private final ShaderProgram downsampleShader;
    private final ShaderProgram upsampleShader;
    private final GlStateTracker stateTracker = new GlStateTracker();

    private int quadVao;
    private int quadVbo;
    private int linearSampler;
    private boolean loggedBlurDiag = false;

    private int width = 0;
    private int height = 0;
    private int fboWidth = 0;
    private int fboHeight = 0;

    // Строго 2 статических ping-pong FBO (/4 от экрана) + readFbo для аппаратного glBlitFramebuffer
    private int blitReadFbo = 0;
    private int boundReadTex = 0;
    private int pingFbo = 0;
    private int pingTex = 0;
    private int pongFbo = 0;
    private int pongTex = 0;
    private int lastLightTex = 0;

    public KawaseBlur() {
        this.downsampleShader = ShaderProgram.fromResources(
                "/assets/nebulavisuals/shaders/fullscreen.vsh",
                "/assets/nebulavisuals/shaders/kawase_down.fsh"
        );
        this.upsampleShader = ShaderProgram.fromResources(
                "/assets/nebulavisuals/shaders/fullscreen.vsh",
                "/assets/nebulavisuals/shaders/kawase_up.fsh"
        );
        initQuad();
        initSampler();
    }

    private void initSampler() {
        linearSampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(linearSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(linearSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(linearSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(linearSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private void initQuad() {
        quadVao = GL30.glGenVertexArrays();
        quadVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(quadVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);

        // Полноэкранный квад: позиция (x, y) + UV (u, v)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer vertices = stack.floats(
                    -1.0f, -1.0f,  0.0f, 0.0f,
                     1.0f, -1.0f,  1.0f, 0.0f,
                     1.0f,  1.0f,  1.0f, 1.0f,
                    -1.0f, -1.0f,  0.0f, 0.0f,
                     1.0f,  1.0f,  1.0f, 1.0f,
                    -1.0f,  1.0f,  0.0f, 1.0f
            );
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        }

        // 0: vec2 aPos
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);

        // 1: vec2 aTexCoord
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Выделяет строго 2 статических ping-pong FBO (/4 от разрешения экрана) и read-FBO для блита.
     * Пересоздаёт буферы ТОЛЬКО при реальном изменении {@code screenWidth} или {@code screenHeight}.
     */
    public void ensureCapacity(int screenWidth, int screenHeight, int iterations) {
        if (screenWidth <= 0 || screenHeight <= 0) return;
        if (this.width == screenWidth && this.height == screenHeight
                && pingFbo != 0 && pongFbo != 0 && blitReadFbo != 0) {
            return;
        }

        deleteFbos();

        this.width = screenWidth;
        this.height = screenHeight;
        this.fboWidth = Math.max(1, screenWidth / SCALE_DIVISOR);
        this.fboHeight = Math.max(1, screenHeight / SCALE_DIVISOR);

        blitReadFbo = GL30.glGenFramebuffers();
        boundReadTex = 0;

        pingFbo = GL30.glGenFramebuffers();
        pingTex = GL11.glGenTextures();
        setupColorTarget(pingFbo, pingTex, fboWidth, fboHeight, "ping");

        pongFbo = GL30.glGenFramebuffers();
        pongTex = GL11.glGenTextures();
        setupColorTarget(pongFbo, pongTex, fboWidth, fboHeight, "pong");

        lastLightTex = pongTex;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void setupColorTarget(int fbo, int tex, int w, int h, String label) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Kawase FBO creation failed at " + label + " with status: " + status);
        }
    }

    /**
     * Выполняет быстрое ping-pong размытие на /4 разрешении через 2 статических FBO.
     */
    public int renderBlur(int sourceTextureId, int screenWidth, int screenHeight, int iterations, float offset) {
        return renderDualBlur(sourceTextureId, screenWidth, screenHeight, 0, 0.0f, iterations, offset);
    }

    /**
     * Захватывает экран аппаратным {@code glBlitFramebuffer} в {@code pingFbo} (w/2 x h/2)
     * и выполняет 3 лёгких Kawase-прохода (downsample + intermediate offset 1.5 + upsample),
     * полностью убирая пиксельные лесенки при сохранении высокого FPS.
     */
    public int renderDualBlur(int sourceTextureId, int screenWidth, int screenHeight,
                              int lightIterations, float lightOffset,
                              int deepIterations, float deepOffset) {
        if (screenWidth <= 0 || screenHeight <= 0 || sourceTextureId <= 0) return sourceTextureId;

        stateTracker.push();
        ensureCapacity(screenWidth, screenHeight, 3);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);

        // 1. Быстрый аппаратный downsample screenWidth x screenHeight -> fboWidth x fboHeight (w/2 x h/2)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, blitReadFbo);
        if (boundReadTex != sourceTextureId) {
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sourceTextureId, 0);
            boundReadTex = sourceTextureId;
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, pingFbo);
        GL30.glBlitFramebuffer(
                0, 0, screenWidth, screenHeight,
                0, 0, fboWidth, fboHeight,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR
        );

        GL11.glViewport(0, 0, fboWidth, fboHeight);
        GL30.glBindVertexArray(quadVao);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL33.glBindSampler(0, linearSampler);

        float halfPixelX = 0.5f / fboWidth;
        float halfPixelY = 0.5f / fboHeight;
        float baseOff = Math.max(0.35f, deepOffset);

        // Проход 1: Downsample (pingTex -> pongFbo)
        downsampleShader.bind();
        downsampleShader.setUniform1i("uTexture", 0);
        downsampleShader.setUniform2f("uHalfPixel", halfPixelX, halfPixelY);
        downsampleShader.setUniform1f("uOffset", Math.max(0.35f, baseOff * 0.65f));

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, pongFbo);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, pingTex);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        // Проход 2: Intermediate Downsample с шагом 1.5 (pongTex -> pingFbo)
        downsampleShader.setUniform1f("uOffset", Math.max(0.55f, baseOff * 1.5f));
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, pingFbo);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, pongTex);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        lastLightTex = pingTex;

        // Проход 3: Upsample (pingTex -> pongFbo)
        upsampleShader.bind();
        upsampleShader.setUniform1i("uTexture", 0);
        upsampleShader.setUniform2f("uHalfPixel", halfPixelX, halfPixelY);
        upsampleShader.setUniform1f("uOffset", baseOff);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, pongFbo);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, pingTex);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

        if (!loggedBlurDiag) {
            loggedBlurDiag = true;
            int err = GL11.glGetError();
            LOGGER.info("[NebulaBlur-Diag] pingPong={}x{}, src={}, out={}, light={}, passes=3, glErr=0x{}",
                    fboWidth, fboHeight, sourceTextureId, pongTex, lastLightTex, Integer.toHexString(err));
        }

        GL30.glBindVertexArray(0);
        stateTracker.pop();

        return pongTex;
    }

    public int getLightTextureId() {
        return lastLightTex != 0 ? lastLightTex : pingTex;
    }

    /**
     * Отрисовка размытого участка интерфейса (Frosted Glass / Эффект матового стекла) через RenderBatch2D.
     */
    public void drawBlurredRect(RenderBatch2D batch, int blurredTextureId,
                                float x, float y, float w, float h,
                                float radius, float viewportW, float viewportH, int tint) {
        // Вычисление UV координат относительно всего экрана
        float u1 = x / viewportW;
        float v1 = 1.0f - (y / viewportH);
        float u2 = (x + w) / viewportW;
        float v2 = 1.0f - ((y + h) / viewportH);

        batch.drawTexturedRoundedRect(blurredTextureId, x, y, w, h, radius, u1, v1, u2, v2, tint);
    }

    private void deleteFbos() {
        if (blitReadFbo != 0) {
            GL30.glDeleteFramebuffers(blitReadFbo);
            blitReadFbo = 0;
        }
        boundReadTex = 0;
        if (pingFbo != 0) {
            GL30.glDeleteFramebuffers(pingFbo);
            pingFbo = 0;
        }
        if (pingTex != 0) {
            GL11.glDeleteTextures(pingTex);
            pingTex = 0;
        }
        if (pongFbo != 0) {
            GL30.glDeleteFramebuffers(pongFbo);
            pongFbo = 0;
        }
        if (pongTex != 0) {
            GL11.glDeleteTextures(pongTex);
            pongTex = 0;
        }
        lastLightTex = 0;
    }

    @Override
    public void close() {
        deleteFbos();
        if (linearSampler != 0) {
            GL33.glDeleteSamplers(linearSampler);
            linearSampler = 0;
        }
        if (quadVao != 0) GL30.glDeleteVertexArrays(quadVao);
        if (quadVbo != 0) GL15.glDeleteBuffers(quadVbo);
        downsampleShader.delete();
        upsampleShader.delete();
    }
}
