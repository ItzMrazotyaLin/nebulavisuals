package me.itzmr.nebulavisuals.render.engine2d;

/**
 * Доступ к состоянию анимации открытия ванильного экрана из {@code ScreenMixin}.
 */
public interface AnimatedScreenAccess {
    boolean nebulavisuals$isScaleActive();

    float nebulavisuals$getScale();

    float nebulavisuals$getEasedAlpha();
}
