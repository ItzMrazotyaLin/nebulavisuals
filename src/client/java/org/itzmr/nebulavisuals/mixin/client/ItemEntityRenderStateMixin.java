package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.render.visuals.ItemPhysicsStateAccess;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Расширяет {@link ItemEntityRenderState} данными о контакте с поверхностью и углах наклона для {@code ItemPhysics}.
 */
@Mixin(ItemEntityRenderState.class)
public abstract class ItemEntityRenderStateMixin implements ItemPhysicsStateAccess {
    @Unique
    private boolean nebula$onGround;

    @Unique
    private boolean nebula$flatItem;

    @Unique
    private float nebula$yawDegrees;

    @Unique
    private float nebula$airSpinDegrees;

    @Override
    public void nebula$setPhysicsState(boolean onGround, boolean flatItem, float yawDegrees, float airSpinDegrees) {
        this.nebula$onGround = onGround;
        this.nebula$flatItem = flatItem;
        this.nebula$yawDegrees = yawDegrees;
        this.nebula$airSpinDegrees = airSpinDegrees;
    }

    @Override
    public boolean nebula$isOnGround() {
        return nebula$onGround;
    }

    @Override
    public boolean nebula$isFlatItem() {
        return nebula$flatItem;
    }

    @Override
    public float nebula$getYawDegrees() {
        return nebula$yawDegrees;
    }

    @Override
    public float nebula$getAirSpinDegrees() {
        return nebula$airSpinDegrees;
    }
}
