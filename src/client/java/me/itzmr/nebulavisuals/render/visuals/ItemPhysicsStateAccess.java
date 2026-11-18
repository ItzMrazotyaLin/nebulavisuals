package me.itzmr.nebulavisuals.render.visuals;

/**
 * Интерфейс доступа к физическим полям, добавляемым миксином на {@code ItemEntityRenderState}.
 */
public interface ItemPhysicsStateAccess {
    void nebula$setPhysicsState(boolean onGround, boolean flatItem, float yawDegrees, float airSpinDegrees);

    boolean nebula$isOnGround();

    boolean nebula$isFlatItem();

    float nebula$getYawDegrees();

    float nebula$getAirSpinDegrees();
}
