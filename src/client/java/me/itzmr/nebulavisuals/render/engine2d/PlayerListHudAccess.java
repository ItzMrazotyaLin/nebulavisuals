package me.itzmr.nebulavisuals.render.engine2d;

/**
 * Доступ к состоянию плавного открытия/закрытия списка игроков ({@code Tab}).
 */
public interface PlayerListHudAccess {
    float nebulavisuals$updateVisibility(boolean shouldShow);

    float nebulavisuals$getTabAlpha();
}
