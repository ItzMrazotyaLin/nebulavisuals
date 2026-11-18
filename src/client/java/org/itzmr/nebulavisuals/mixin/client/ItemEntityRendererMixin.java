package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.ItemPhysicsModule;
import me.itzmr.nebulavisuals.render.visuals.ItemPhysicsStateAccess;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Реализует физику выброшенных предметов (отмена ванильного парения, 3D-вращение в воздухе, укладка на поверхность).
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin extends EntityRenderer<ItemEntity, ItemEntityRenderState> {
    @Shadow
    @Final
    private Random random;

    protected ItemEntityRendererMixin(EntityRendererFactory.Context context) {
        super(context);
    }

    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void nebula$capturePhysicsState(ItemEntity entity, ItemEntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (!(state instanceof ItemPhysicsStateAccess access)) {
            return;
        }
        boolean onGround = entity.isOnGround();
        Box modelBox = state.itemRenderState.isEmpty() ? null : state.itemRenderState.getModelBoundingBox();
        boolean isBlock = entity.getStack().getItem() instanceof BlockItem
                && modelBox != null
                && modelBox.getLengthZ() > 0.14;
        boolean flatItem = !isBlock;

        // Детерминированный случайный угол поворота вокруг вертикали на базе uniqueOffset и id сущности
        int hash = entity.getId() * 747796405 + 289133645;
        hash = ((hash >>> 16) ^ hash) * 0x45d9f3b;
        float yawDegrees = Math.abs(hash % 360) + (entity.uniqueOffset * 57.29578f);
        float direction = (entity.getId() & 1) == 0 ? 1.0f : -1.0f;
        float airSpinDegrees = state.age * direction;

        access.nebula$setPhysicsState(onGround, flatItem, yawDegrees, airSpinDegrees);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nebula$renderItemPhysics(
            ItemEntityRenderState state,
            MatrixStack matrices,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraState,
            CallbackInfo ci
    ) {
        ItemPhysicsModule module = ModuleManager.getItemPhysicsModule();
        if (module == null || !module.isEnabled()) {
            return;
        }
        if (!(state instanceof ItemPhysicsStateAccess access)) {
            return;
        }
        if (state.itemRenderState.isEmpty()) {
            ci.cancel();
            return;
        }

        matrices.push();
        Box box = state.itemRenderState.getModelBoundingBox();
        float yaw = access.nebula$getYawDegrees();
        boolean flat = access.nebula$isFlatItem();

        if (!access.nebula$isOnGround()) {
            float speed = module.rotationSpeed.getValue().floatValue();
            float spin = access.nebula$getAirSpinDegrees() * speed;
            // Приподнимаем центр модели в полёте, чтобы вращение происходило вокруг центра предмета
            matrices.translate(0.0f, 0.20f, 0.0f);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw + spin * 6.0f));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(spin * 18.0f));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(spin * 11.0f));
        } else if (flat) {
            // Укладываем плоские предметы плашмя на поверхность блока, исключая Z-fighting с землёй
            float halfThickness = Math.max(0.016f, (float) (box.getLengthZ() * 0.5));
            matrices.translate(0.0f, halfThickness + 0.006f, 0.0f);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90.0f));
        } else {
            // Объёмные блоки аккуратно стоят на поверхности со случайным поворотом вокруг Y
            float bottomOffset = -((float) box.minY) + 0.004f;
            matrices.translate(0.0f, bottomOffset, 0.0f);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        }

        ItemEntityRenderer.render(matrices, queue, state.light, state, this.random, box);
        matrices.pop();

        super.render(state, matrices, queue, cameraState);
        ci.cancel();
    }
}
