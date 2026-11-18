package me.itzmr.nebulavisuals.render.engine2d.font;

/**
 * Описание глифа шрифта из атласа msdf-atlas-gen.
 */
public record MsdfGlyph(
        int unicode,
        float advance,
        boolean hasPlaneBounds,
        float planeLeft,
        float planeBottom,
        float planeRight,
        float planeTop,
        boolean hasAtlasBounds,
        float atlasLeft,
        float atlasBottom,
        float atlasRight,
        float atlasTop
) {
    public static MsdfGlyph empty(int unicode, float advance) {
        return new MsdfGlyph(unicode, advance, false, 0, 0, 0, 0, false, 0, 0, 0, 0);
    }
}
