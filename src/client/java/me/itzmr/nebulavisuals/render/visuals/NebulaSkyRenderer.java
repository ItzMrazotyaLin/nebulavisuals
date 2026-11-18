package me.itzmr.nebulavisuals.render.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import me.itzmr.nebulavisuals.module.visuals.CustomSkyModule;
import me.itzmr.nebulavisuals.render.engine2d.shader.ShaderProgram;
import me.itzmr.nebulavisuals.render.engine2d.util.GlStateTracker;
import me.itzmr.nebulavisuals.render.engine3d.Camera3D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Процедурный GLSL-рендерер скайбокса для модуля {@link CustomSkyModule}.
 * Рисует полноэкранный квад ДО рендера мира с отключённой записью в буфер глубины ({@code glDepthMask(false)})
 * и восстанавливает мировой луч взгляда через {@code u_InvProj} и {@code u_InvViewRot}.
 */
public final class NebulaSkyRenderer {
    private static final long START_NANOS = System.nanoTime();
    private static final int INDEX_COUNT = 6;

    private static final float[] QUAD_VERTICES = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
             1.0f,  1.0f,
            -1.0f,  1.0f
    };

    private static final int[] QUAD_INDICES = {
            0, 1, 2,
            2, 3, 0
    };

    private static boolean initialized = false;
    private static GlStateTracker stateTracker;
    private static ShaderProgram shader;
    private static FloatBuffer invProjBuffer;
    private static FloatBuffer invViewRotBuffer;
    private static int skyFbo = 0;
    private static int vao = 0;
    private static int vbo = 0;
    private static int ebo = 0;

    private NebulaSkyRenderer() {}

    private static void init() {
        if (initialized) return;
        RenderSystem.assertOnRenderThread();

        stateTracker = new GlStateTracker();
        stateTracker.push();
        try {
            shader = ShaderProgram.fromResources(
                    "/assets/nebulavisuals/shaders/nebula_sky.vsh",
                    "/assets/nebulavisuals/shaders/nebula_sky.fsh"
            );
            invProjBuffer = MemoryUtil.memAllocFloat(16);
            invViewRotBuffer = MemoryUtil.memAllocFloat(9);
            skyFbo = GL30.glGenFramebuffers();

            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            ebo = GL15.glGenBuffers();

            GL30.glBindVertexArray(vao);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer vb = stack.floats(QUAD_VERTICES);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_STATIC_DRAW);

                IntBuffer ib = stack.ints(QUAD_INDICES);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
                GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
            }

            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0L);

            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            initialized = true;
        } finally {
            stateTracker.pop();
        }
    }

    public static void render(CustomSkyModule module) {
        if (module == null || !module.hasCustomAtmosphere()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.getFramebuffer() == null || client.getWindow() == null) {
            return;
        }

        if (!(client.getFramebuffer().getColorAttachment() instanceof GlTexture colorTex)
                || colorTex.isClosed()
                || colorTex.getGlId() <= 0) {
            return;
        }

        if (!initialized) {
            init();
        }

        if (!Camera3D.fillSkyInverseMatrices(invProjBuffer, invViewRotBuffer)) {
            return;
        }

        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        stateTracker.push();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
            int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
            GL11.glViewport(0, 0, fbW, fbH);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, skyFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex.getGlId(), 0);

            if (client.getFramebuffer().getDepthAttachment() instanceof GlTexture depthTex
                    && !depthTex.isClosed()
                    && depthTex.getGlId() > 0) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex.getGlId(), 0);
            } else {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
            }

            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevDrawFbo);
                return;
            }

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glColorMask(true, true, true, true);

            shader.bind();
            shader.setUniformMatrix4fv("u_InvProj", invProjBuffer);
            shader.setUniformMatrix3fv("u_InvViewRot", invViewRotBuffer);
            shader.setUniform1i("u_PatternMode", module.resolvePatternMode());
            shader.setUniform1f("u_Time", (float) ((System.nanoTime() - START_NANOS) * 1.0e-9));
            setRgbUniform("u_ZenithColor", module.resolveZenithColorArgb());
            setRgbUniform("u_HorizonColor", module.resolveHorizonColorArgb());
            setRgbUniform("u_NebulaColor1", module.resolveNebulaColor1Argb());
            setRgbUniform("u_NebulaColor2", module.resolveNebulaColor2Argb());
            shader.setUniform1f("u_StarsIntensity", module.starsIntensity.getValue().floatValue());
            shader.setUniform1f("u_NebulaIntensity", module.nebulaIntensity.getValue().floatValue());

            GL30.glBindVertexArray(vao);
            GL11.glDrawElements(GL11.GL_TRIANGLES, INDEX_COUNT, GL11.GL_UNSIGNED_INT, 0L);
            GL30.glBindVertexArray(0);

            shader.unbind();
        } finally {
            stateTracker.pop();
        }
    }

    private static void setRgbUniform(String name, int argb) {
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        shader.setUniform3f(name, r, g, b);
    }

    public static void shutdown() {
        if (!initialized) return;
        if (shader != null) {
            shader.delete();
            shader = null;
        }
        if (invProjBuffer != null) {
            MemoryUtil.memFree(invProjBuffer);
            invProjBuffer = null;
        }
        if (invViewRotBuffer != null) {
            MemoryUtil.memFree(invViewRotBuffer);
            invViewRotBuffer = null;
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (ebo != 0) {
            GL15.glDeleteBuffers(ebo);
            ebo = 0;
        }
        if (skyFbo != 0) {
            GL30.glDeleteFramebuffers(skyFbo);
            skyFbo = 0;
        }
        stateTracker = null;
        initialized = false;
    }
}
