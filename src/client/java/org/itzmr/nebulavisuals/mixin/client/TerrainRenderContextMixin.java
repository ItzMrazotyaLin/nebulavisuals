package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.optimization.CullingHelper;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.TerrainRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Дополняет {@link BlockRenderManagerMixin} для Fabric Renderer API (Indigo):
 * Indigo перехватывает {@code BlockState.getRenderType()} в {@code SectionBuilder.build} и буферизует модели
 * напрямую через {@link TerrainRenderContext#bufferModel} в обход {@code BlockRenderManager.renderBlock}.
 */
@Mixin(TerrainRenderContext.class)
public class TerrainRenderContextMixin {

    @Inject(method = "bufferModel", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$cullIndigoTerrainBlock(
            BlockStateModel model, BlockState blockState, BlockPos blockPos, CallbackInfo ci) {
        if (CullingHelper.shouldCullBlock(blockState, blockPos)) {
            ci.cancel();
        }
    }
}
