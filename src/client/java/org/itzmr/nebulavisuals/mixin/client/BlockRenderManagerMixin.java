package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.optimization.CullingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Перехватывает ванильный рендер блоков в меш чанка через {@link BlockRenderManager#renderBlock}
 * и отменяет запекание блоков, отсечённых по дистанции в {@link CullingHelper}.
 */
@Mixin(BlockRenderManager.class)
public class BlockRenderManagerMixin {

    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$cullRenderBlock(
            BlockState state,
            BlockPos pos,
            BlockRenderView world,
            MatrixStack matrices,
            VertexConsumer vertexConsumer,
            boolean cull,
            List<BlockModelPart> parts,
            CallbackInfo ci) {
        if (CullingHelper.shouldCullBlock(state, pos)) {
            ci.cancel();
        }
    }
}
