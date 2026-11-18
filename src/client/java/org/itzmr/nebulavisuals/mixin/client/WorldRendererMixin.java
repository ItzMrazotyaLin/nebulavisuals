package org.itzmr.nebulavisuals.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.CullingHelper;
import me.itzmr.nebulavisuals.module.optimization.EntityCullingModule;
import me.itzmr.nebulavisuals.module.visuals.BlockOverlayModule;
import me.itzmr.nebulavisuals.render.engine3d.Camera3D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.memory.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Захватывает актуальные матрицы вида и проекции кадра (с учётом bobView, hurtTilt и FOV)
 * для точной синхронизации кастомного 3D-рендера {@link Camera3D}, а также отменяет ванильную рамку блока при включённом {@code BlockOverlay}.
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void nebulavisuals$captureWorldMatrices(
            ObjectAllocator allocator,
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f posMatrix,
            Matrix4f projMatrix,
            Matrix4f cullProjMatrix,
            GpuBufferSlice fogBuffer,
            Vector4f fogColor,
            boolean renderSky,
            CallbackInfo ci
    ) {
        EntityCullingModule.updateCameraPosition(MinecraftClient.getInstance());
        CullingHelper.updateCameraPosition(MinecraftClient.getInstance());
        Camera3D.captureMatrices(posMatrix, projMatrix);
    }

    @Inject(method = "renderTargetBlockOutline", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$cancelVanillaBlockOutline(
            VertexConsumerProvider.Immediate vertexConsumers,
            MatrixStack matrices,
            boolean translucent,
            WorldRenderState renderState,
            CallbackInfo ci
    ) {
        BlockOverlayModule module = ModuleManager.getBlockOverlayModule();
        if (module != null && module.isEnabled()) {
            ci.cancel();
        }
    }
}
