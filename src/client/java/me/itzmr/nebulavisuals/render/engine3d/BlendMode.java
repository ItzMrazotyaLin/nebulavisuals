package me.itzmr.nebulavisuals.render.engine3d;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * Режимы смешивания (Blend Mode) для 3D-рендера визуальных эффектов.
 */
public enum BlendMode {
    /**
     * Стандартное альфа-наложение: {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}.
     */
    ALPHA,

    /**
     * Аддитивное неоновое свечение: {@code SRC_ALPHA, ONE}.
     */
    ADDITIVE;

    public void apply() {
        switch (this) {
            case ALPHA -> GL14.glBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
            );
            case ADDITIVE -> GL14.glBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE,
                    GL11.GL_ONE, GL11.GL_ONE
            );
        }
    }
}
