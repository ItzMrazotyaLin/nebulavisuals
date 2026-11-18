package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.MotionBlurModule;
import me.itzmr.nebulavisuals.render.engine2d.shader.ShaderProgram;
import me.itzmr.nebulavisuals.render.engine2d.util.GlStateTracker;
import me.itzmr.nebulavisuals.render.engine3d.Camera3D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Depth-Reprojected Motion Blur рендерер модуля {@link MotionBlurModule}.
 * Восстанавливает camera-relative 3D-позицию по буферу глубины и репроецирует её в предыдущий кадр
 * через {@code uInverseViewProjection} и {@code uPrevViewProjection} с Interleaved Gradient Noise.
 */
public final class MotionBlurRenderer {
    private static final float MATRIX_DIFF_THRESHOLD = 1e-5f;
    private static final double CAM_DELTA_SQ_THRESHOLD = 1e-8;

    private static final GlStateTracker STATE_TRACKER = new GlStateTracker();

    private static final Matrix4f CURR_VIEW_PROJ = new Matrix4f();
    private static final Matrix4f INV_CURR_VIEW_PROJ = new Matrix4f();
    private static final Matrix4f PREV_VIEW_PROJ = new Matrix4f();
    private static final Matrix4f PREV_VP_WITH_DELTA = new Matrix4f();

    private static FloatBuffer invVpBuffer;
    private static FloatBuffer prevVpBuffer;

    private static ShaderProgram shader;
    private static int quadVao = 0;
    private static int quadVbo = 0;
    private static int linearSampler = 0;
    private static int depthSampler = 0;

    private static int curCopyFbo = 0;
    private static int curCopyTex = 0;
    private static int mainReadFbo = 0;
    private static int mainDrawFbo = 0;
    private static int boundMainTexId = 0;

    private static int bufWidth = 0;
    private static int bufHeight = 0;
    private static boolean initialized = false;

    private static boolean hasPrevFrame = false;
    private static double prevCamX = 0.0;
    private static double prevCamY = 0.0;
    private static double prevCamZ = 0.0;

    private MotionBlurRenderer() {}

    public static void resetHistory() {
        hasPrevFrame = false;
    }

    public static void render(MinecraftClient client) {
        if (client == null || client.getWindow() == null || client.gameRenderer == null) {
            return;
        }

        MotionBlurModule module = ModuleManager.getMotionBlurModule();
        if (module == null || !module.isEnabled() || client.world == null || client.currentScreen != null) {
            resetHistory();
            return;
        }

        Camera cam = client.gameRenderer.getCamera();
        if (cam == null || !Camera3D.hasCapturedMatrices()) {
            resetHistory();
            return;
        }

        Vec3d camPos = cam.getCameraPos();
        CURR_VIEW_PROJ.set(Camera3D.getCapturedProjMatrix()).mul(Camera3D.getCapturedViewMatrix());

        if (!hasPrevFrame) {
            PREV_VIEW_PROJ.set(CURR_VIEW_PROJ);
            prevCamX = camPos.x;
            prevCamY = camPos.y;
            prevCamZ = camPos.z;
            hasPrevFrame = true;
            return;
        }

        double dCamX = camPos.x - prevCamX;
        double dCamY = camPos.y - prevCamY;
        double dCamZ = camPos.z - prevCamZ;
        double camDeltaSq = dCamX * dCamX + dCamY * dCamY + dCamZ * dCamZ;
        float maxMatDiff = computeMaxMatrixDiff(CURR_VIEW_PROJ, PREV_VIEW_PROJ);

        // Если камера неподвижна и матрица не изменилась — ранний выход без нагрузки на GPU
        if (maxMatDiff < MATRIX_DIFF_THRESHOLD && camDeltaSq < CAM_DELTA_SQ_THRESHOLD) {
            return;
        }

        if (client.getFramebuffer() == null
                || !(client.getFramebuffer().getColorAttachment() instanceof GlTexture colorGlTex)
                || !(client.getFramebuffer().getDepthAttachment() instanceof GlTexture depthGlTex)) {
            updatePrevState(camPos);
            return;
        }

        int mainColorTex = colorGlTex.getGlId();
        int depthTex = depthGlTex.getGlId();
        int fbW = client.getWindow().getFramebufferWidth();
        int fbH = client.getWindow().getFramebufferHeight();
        if (mainColorTex <= 0 || depthTex <= 0 || fbW <= 0 || fbH <= 0) {
            updatePrevState(camPos);
            return;
        }

        // Переводим deltaCam в prevVP, чтобы одно умножение переводило current relWorld -> prevClip
        CURR_VIEW_PROJ.invert(INV_CURR_VIEW_PROJ);
        PREV_VIEW_PROJ.translate((float) dCamX, (float) dCamY, (float) dCamZ, PREV_VP_WITH_DELTA);

        if (!initialized) {
            initGlResources();
        }

        invVpBuffer.clear();
        INV_CURR_VIEW_PROJ.get(0, invVpBuffer);
        prevVpBuffer.clear();
        PREV_VP_WITH_DELTA.get(0, prevVpBuffer);

        STATE_TRACKER.push();
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try {
            ensureBufferSize(fbW, fbH);

            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);

            if (boundMainTexId != mainColorTex) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainReadFbo);
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, mainColorTex, 0);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainDrawFbo);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, mainColorTex, 0);
                boundMainTexId = mainColorTex;
            }

            // 1. Быстрая аппаратная копия текущего цветового буфера в curCopyFbo
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, curCopyFbo);
            GL30.glBlitFramebuffer(0, 0, fbW, fbH, 0, 0, fbW, fbH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            // 2. Depth-reprojected motion blur с Interleaved Gradient Noise обратно в mainDrawFbo
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainDrawFbo);
            GL11.glViewport(0, 0, fbW, fbH);

            int sampleCount = Math.clamp((int) Math.round(module.samples.getValue()), 8, 24);
            shader.bind();
            shader.setUniform1i("uCurrentTex", 0);
            shader.setUniform1i("uDepthTex", 1);
            shader.setUniformMatrix4fv("uInverseViewProjection", invVpBuffer);
            shader.setUniformMatrix4fv("uPrevViewProjection", prevVpBuffer);
            shader.setUniform1f("uStrength", Math.clamp(module.strength.getValue().floatValue(), 0.05f, 1.25f));
            shader.setUniform1i("uSamples", sampleCount);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, curCopyTex);
            GL33.glBindSampler(0, linearSampler);

            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL33.glBindSampler(1, depthSampler);

            GL30.glBindVertexArray(quadVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            GL30.glBindVertexArray(0);
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
            GL33.glBindSampler(1, prevSampler1);
            STATE_TRACKER.pop();
            updatePrevState(camPos);
        }
    }

    private static void updatePrevState(Vec3d camPos) {
        PREV_VIEW_PROJ.set(CURR_VIEW_PROJ);
        prevCamX = camPos.x;
        prevCamY = camPos.y;
        prevCamZ = camPos.z;
    }

    private static float computeMaxMatrixDiff(Matrix4f a, Matrix4f b) {
        float max = Math.abs(a.m00() - b.m00());
        max = Math.max(max, Math.abs(a.m01() - b.m01()));
        max = Math.max(max, Math.abs(a.m02() - b.m02()));
        max = Math.max(max, Math.abs(a.m03() - b.m03()));
        max = Math.max(max, Math.abs(a.m10() - b.m10()));
        max = Math.max(max, Math.abs(a.m11() - b.m11()));
        max = Math.max(max, Math.abs(a.m12() - b.m12()));
        max = Math.max(max, Math.abs(a.m13() - b.m13()));
        max = Math.max(max, Math.abs(a.m20() - b.m20()));
        max = Math.max(max, Math.abs(a.m21() - b.m21()));
        max = Math.max(max, Math.abs(a.m22() - b.m22()));
        max = Math.max(max, Math.abs(a.m23() - b.m23()));
        max = Math.max(max, Math.abs(a.m30() - b.m30()));
        max = Math.max(max, Math.abs(a.m31() - b.m31()));
        max = Math.max(max, Math.abs(a.m32() - b.m32()));
        max = Math.max(max, Math.abs(a.m33() - b.m33()));
        return max;
    }

    private static void initGlResources() {
        shader = ShaderProgram.fromResources(
                "/assets/nebulavisuals/shaders/motion_blur.vsh",
                "/assets/nebulavisuals/shaders/motion_blur.fsh"
        );

        invVpBuffer = MemoryUtil.memAllocFloat(16);
        prevVpBuffer = MemoryUtil.memAllocFloat(16);

        linearSampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(linearSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(linearSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(linearSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(linearSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        depthSampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        quadVao = GL30.glGenVertexArrays();
        quadVbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(quadVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer vertices = stack.floats(
                    -1.0f, -1.0f, 0.0f, 0.0f,
                     1.0f, -1.0f, 1.0f, 0.0f,
                     1.0f,  1.0f, 1.0f, 1.0f,
                    -1.0f, -1.0f, 0.0f, 0.0f,
                     1.0f,  1.0f, 1.0f, 1.0f,
                    -1.0f,  1.0f, 0.0f, 1.0f
            );
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        }

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        mainReadFbo = GL30.glGenFramebuffers();
        mainDrawFbo = GL30.glGenFramebuffers();
        initialized = true;
    }

    private static void ensureBufferSize(int fbW, int fbH) {
        if (bufWidth == fbW && bufHeight == fbH && curCopyFbo != 0) {
            return;
        }
        deleteFrameBuffer();
        bufWidth = fbW;
        bufHeight = fbH;

        curCopyFbo = GL30.glGenFramebuffers();
        curCopyTex = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, curCopyTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, fbW, fbH, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, curCopyFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, curCopyTex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("MotionBlur curCopyFbo failed: " + status);
        }
    }

    private static void deleteFrameBuffer() {
        if (curCopyFbo != 0) { GL30.glDeleteFramebuffers(curCopyFbo); curCopyFbo = 0; }
        if (curCopyTex != 0) { GL11.glDeleteTextures(curCopyTex); curCopyTex = 0; }
    }

    public static void shutdown() {
        if (!initialized) {
            return;
        }
        deleteFrameBuffer();
        if (mainReadFbo != 0) { GL30.glDeleteFramebuffers(mainReadFbo); mainReadFbo = 0; }
        if (mainDrawFbo != 0) { GL30.glDeleteFramebuffers(mainDrawFbo); mainDrawFbo = 0; }
        boundMainTexId = 0;
        if (linearSampler != 0) { GL33.glDeleteSamplers(linearSampler); linearSampler = 0; }
        if (depthSampler != 0) { GL33.glDeleteSamplers(depthSampler); depthSampler = 0; }
        if (quadVao != 0) { GL30.glDeleteVertexArrays(quadVao); quadVao = 0; }
        if (quadVbo != 0) { GL15.glDeleteBuffers(quadVbo); quadVbo = 0; }
        if (invVpBuffer != null) { MemoryUtil.memFree(invVpBuffer); invVpBuffer = null; }
        if (prevVpBuffer != null) { MemoryUtil.memFree(prevVpBuffer); prevVpBuffer = null; }
        if (shader != null) { shader.delete(); shader = null; }
        bufWidth = 0;
        bufHeight = 0;
        resetHistory();
        initialized = false;
    }
}
