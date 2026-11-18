package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.BlockCullingModule;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderManager;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Дистанционное отсечение рендера BlockEntity (табличек, сундуков, знамён) для {@link BlockCullingModule}.
 */
@Mixin(BlockEntityRenderManager.class)
public class BlockEntityRenderManagerMixin {
    @Shadow private Vec3d cameraPos;

    @Inject(method = "getRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void nebulavisuals$cullDistantBlockEntities(
            E blockEntity,
            float tickProgress,
            ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay,
            CallbackInfoReturnable<S> cir) {
        BlockCullingModule module = ModuleManager.getBlockCullingModule();
        if (module != null && this.cameraPos != null
                && module.shouldCullBlockEntity(blockEntity, this.cameraPos.x, this.cameraPos.y, this.cameraPos.z)) {
            cir.setReturnValue(null);
        }
    }
}
