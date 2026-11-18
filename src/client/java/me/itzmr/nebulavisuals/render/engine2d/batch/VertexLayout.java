package me.itzmr.nebulavisuals.render.engine2d.batch;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Описание единого формата вершины для батчера RenderBatch2D.
 * 
 * Структура вершины (72 байта):
 *  0..7:   vec2 aPos (8 байт)
 *  8..15:  vec2 aTexCoord (8 байт)
 * 16..19:  vec4 aColor (4 байта GL_UNSIGNED_BYTE normalized)
 * 20..27:  vec2 aLocalPos (8 байт)
 * 28..35:  vec2 aSize (8 байт)
 * 36..51:  vec4 aRadii (16 байт)
 * 52..67:  vec4 aParams (16 байт: x=тип, y=толщина обводки, z=размытие/softness, w=pxRange/доп)
 * 68..71:  vec4 aOutlineColor (4 байта GL_UNSIGNED_BYTE normalized)
 */
public final class VertexLayout {
    public static final int STRIDE = 72;

    public static final int PRIMITIVE_RECT = 0;
    public static final int PRIMITIVE_SDF_RECT = 1;
    public static final int PRIMITIVE_TEXTURED = 2;
    public static final int PRIMITIVE_MSDF_TEXT = 3;

    private VertexLayout() {}

    public static void setupAttributes() {
        // location 0: aPos
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, STRIDE, 0);

        // location 1: aTexCoord
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE, 8);

        // location 2: aColor
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 16);

        // location 3: aLocalPos
        GL20.glEnableVertexAttribArray(3);
        GL20.glVertexAttribPointer(3, 2, GL11.GL_FLOAT, false, STRIDE, 20);

        // location 4: aSize
        GL20.glEnableVertexAttribArray(4);
        GL20.glVertexAttribPointer(4, 2, GL11.GL_FLOAT, false, STRIDE, 28);

        // location 5: aRadii
        GL20.glEnableVertexAttribArray(5);
        GL20.glVertexAttribPointer(5, 4, GL11.GL_FLOAT, false, STRIDE, 36);

        // location 6: aParams
        GL20.glEnableVertexAttribArray(6);
        GL20.glVertexAttribPointer(6, 4, GL11.GL_FLOAT, false, STRIDE, 52);

        // location 7: aOutlineColor
        GL20.glEnableVertexAttribArray(7);
        GL20.glVertexAttribPointer(7, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 68);
    }
}
