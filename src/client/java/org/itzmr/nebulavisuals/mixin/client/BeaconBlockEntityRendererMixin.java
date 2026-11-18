package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.AnimationsCullingModule;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.render.block.entity.state.BeaconBlockEntityRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пропускает рендер луча маяка при активном {@code AnimationsCulling -> Beacon & Anchor}.
 */
@Mixin(BeaconBlockEntityRenderer.class)
public class BeaconBlockEntityRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/client/render/block/entity/state/BeaconBlockEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nebulavisuals$skipBeaconBeam(
            BeaconBlockEntityRenderState state,
            MatrixStack matrices,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraRenderState,
            CallbackInfo ci) {
        AnimationsCullingModule module = ModuleManager.getAnimationsCullingModule();
        if (module != null && module.shouldSkipBeaconBeam()) {
            ci.cancel();
        }
    }
}
