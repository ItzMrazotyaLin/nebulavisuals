package me.itzmr.nebulavisuals.render.engine3d;

import com.mojang.blaze3d.systems.RenderSystem;
import me.itzmr.nebulavisuals.render.engine2d.util.GlStateTracker;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Центральный менеджер 3D-рендер движка Nebula Visuals (OpenGL 3.3+).
 *
 * <p>Объединяет аппаратный инстансинг сфер ({@link SphereBatch3D}) и off-heap батчинг
 * линий/колец/дисков/шлейфов ({@link RenderBatch3D}) поверх глубины мира Minecraft 1.21.11,
 * строго восстанавливая OpenGL-состояние через {@link GlStateTracker}.</p>
 */
public final class NebulaRender3DEngine {
    private static final List<Render3DCallback> LISTENERS = new CopyOnWriteArrayList<>();

    private static boolean initialized = false;

    private static GlStateTracker stateTracker;
    private static SphereBatch3D sphereBatch;
    private static RenderBatch3D renderBatch;
    private static int targetFbo = 0;

    private NebulaRender3DEngine() {}

    @FunctionalInterface
    public interface Render3DCallback {
        void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D);
    }

    public static void init() {
        if (initialized) return;
        RenderSystem.assertOnRenderThread();

        stateTracker = new GlStateTracker();
        stateTracker.push();
        try {
            sphereBatch = new SphereBatch3D();
            renderBatch = new RenderBatch3D();
            targetFbo = GL30.glGenFramebuffers();
            initialized = true;
        } finally {
            stateTracker.pop();
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void registerListener(Render3DCallback callback) {
        if (callback != null && !LISTENERS.contains(callback)) {
            LISTENERS.add(callback);
        }
    }

    public static void unregisterListener(Render3DCallback callback) {
        LISTENERS.remove(callback);
    }

    /**
     * Точка входа из {@code WorldRenderEvents.END_MAIN} в конце основного прохода рендера мира.
     */
    public static void onEndMainRender(WorldRenderContext context) {
        if (LISTENERS.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) return;

        if (!initialized) {
            init();
        }

        if (!Camera3D.update(context)) return;

        int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        stateTracker.push();
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            setupWorldRenderState(client, prevDrawFbo);

            renderBatch.begin(BlendMode.ALPHA);
            sphereBatch.begin(BlendMode.ADDITIVE);

            for (Render3DCallback listener : LISTENERS) {
                listener.onRender3D(context, sphereBatch, renderBatch);
            }

            renderBatch.end();
            sphereBatch.end();
        } finally {
            stateTracker.pop();
        }
    }

    private static void setupWorldRenderState(MinecraftClient client, int fallbackDrawFbo) {
        if (client.getWindow() != null) {
            int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
            int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
            GL11.glViewport(0, 0, fbW, fbH);
        }

        if (client.getFramebuffer() != null
                && client.getFramebuffer().getColorAttachment() instanceof GlTexture colorTex
                && !colorTex.isClosed()
                && colorTex.getGlId() > 0) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex.getGlId(), 0);

            if (client.getFramebuffer().getDepthAttachment() instanceof GlTexture depthTex
                    && !depthTex.isClosed()
                    && depthTex.getGlId() > 0) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex.getGlId(), 0);
            } else {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
            }

            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fallbackDrawFbo);
            }
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);

        GL11.glEnable(GL11.GL_BLEND);
        BlendMode.ALPHA.apply();

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glColorMask(true, true, true, true);
    }

    public static void shutdown() {
        if (!initialized) return;
        if (sphereBatch != null) {
            sphereBatch.close();
            sphereBatch = null;
        }
        if (renderBatch != null) {
            renderBatch.close();
            renderBatch = null;
        }
        if (targetFbo != 0) {
            GL30.glDeleteFramebuffers(targetFbo);
            targetFbo = 0;
        }
        Camera3D.shutdown();
        LISTENERS.clear();
        stateTracker = null;
        initialized = false;
    }
}
