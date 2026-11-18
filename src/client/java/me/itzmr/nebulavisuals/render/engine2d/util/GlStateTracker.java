package me.itzmr.nebulavisuals.render.engine2d.util;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Утилита сохранения и восстановления состояния OpenGL 3.3+.
 * Защищает ванильный конвейер Minecraft 1.21.11 (включая GL33 Sampler Objects на unit 0)
 * от рассинхронизации при кастомном рендеринге.
 */
public class GlStateTracker {
    private int savedVao;
    private int savedVbo;
    private int savedEbo;
    private int savedProgram;
    private int savedActiveTexture;
    private int savedTexture2D0;
    private int savedSampler0;
    private int savedDrawFbo;
    private int savedReadFbo;
    private boolean savedBlend;
    private boolean savedDepthTest;
    private boolean savedCullFace;
    private boolean savedScissorTest;
    private boolean savedStencilTest;
    private boolean savedColorLogicOp;
    private boolean savedDepthMask;
    private final int[] savedColorMask = new int[4];
    private int savedBlendSrcRgb;
    private int savedBlendDstRgb;
    private int savedBlendSrcAlpha;
    private int savedBlendDstAlpha;
    private final int[] savedViewport = new int[4];
    private final int[] savedScissorBox = new int[4];

    public void push() {
        savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        savedVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        savedEbo = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        savedActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        // Сохраняем именно unit 0 (текстуру и GL33 Sampler Object, который использует GlCommandEncoder 1.21.11)
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        savedTexture2D0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        savedSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);

        savedDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        savedReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        savedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        savedScissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        savedStencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        savedColorLogicOp = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
        savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, savedColorMask);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissorBox);

        savedBlendSrcRgb = GL11.glGetInteger(GL14_BLEND_SRC_RGB());
        savedBlendDstRgb = GL11.glGetInteger(GL14_BLEND_DST_RGB());
        savedBlendSrcAlpha = GL11.glGetInteger(GL14_BLEND_SRC_ALPHA());
        savedBlendDstAlpha = GL11.glGetInteger(GL14_BLEND_DST_ALPHA());

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
    }

    public void pop() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDrawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedReadFbo);

        GL30.glBindVertexArray(savedVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, savedEbo);
        GL20.glUseProgram(savedProgram);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTexture2D0);
        GL33.glBindSampler(0, savedSampler0);
        GL13.glActiveTexture(savedActiveTexture);

        if (savedBlend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
        if (savedDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (savedCullFace) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
        if (savedScissorTest) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        if (savedStencilTest) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
        if (savedColorLogicOp) GL11.glEnable(GL11.GL_COLOR_LOGIC_OP); else GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);

        GL11.glScissor(savedScissorBox[0], savedScissorBox[1], Math.max(0, savedScissorBox[2]), Math.max(0, savedScissorBox[3]));
        GL11.glDepthMask(savedDepthMask);
        GL11.glColorMask(savedColorMask[0] != 0, savedColorMask[1] != 0, savedColorMask[2] != 0, savedColorMask[3] != 0);

        GL20.glBlendFuncSeparate(savedBlendSrcRgb, savedBlendDstRgb, savedBlendSrcAlpha, savedBlendDstAlpha);
        GL11.glViewport(savedViewport[0], savedViewport[1], Math.max(0, savedViewport[2]), Math.max(0, savedViewport[3]));
    }

    private static int GL14_BLEND_SRC_RGB() { return 0x80C9; }
    private static int GL14_BLEND_DST_RGB() { return 0x80C8; }
    private static int GL14_BLEND_SRC_ALPHA() { return 0x80CB; }
    private static int GL14_BLEND_DST_ALPHA() { return 0x80CA; }
}
