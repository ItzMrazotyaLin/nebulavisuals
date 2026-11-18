package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.CustomSkyModule;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Миксин {@link FogRenderer}: настройка цвета и дальности атмосферного тумана (модуль {@code CustomSky}).
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Inject(method = "getFogColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void nebulavisuals$overrideFogColor(
            Camera camera,
            float tickProgress,
            ClientWorld world,
            int viewDistance,
            float skyDarkness,
            CallbackInfoReturnable<Vector4f> cir) {
        CustomSkyModule module = ModuleManager.getCustomSkyModule();
        if (module == null || !module.isFogSyncEnabled()) {
            return;
        }
        if (camera == null || camera.getSubmersionType() != CameraSubmersionType.NONE) {
            return;
        }

        int argb = module.resolveHorizonColorArgb();
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        Vector4f original = cir.getReturnValue();
        float a = original != null ? original.w : 1.0f;
        cir.setReturnValue(new Vector4f(r, g, b, a));
    }

    @ModifyArgs(
            method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/fog/FogRenderer;applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"
            ),
            require = 0
    )
    private void nebulavisuals$scaleFogDistance(
            Args args,
            Camera camera,
            int viewDistance,
            RenderTickCounter renderTickCounter,
            float skyDarkness,
            ClientWorld world) {
        CustomSkyModule module = ModuleManager.getCustomSkyModule();
        if (module == null || !module.isEnabled()) {
            return;
        }
        if (camera != null && camera.getSubmersionType() != CameraSubmersionType.NONE) {
            return;
        }

        float scale = module.fogDistance.getValue().floatValue();
        if (Math.abs(scale - 1.0f) < 0.001f) {
            return;
        }

        // Индексы 3..8: environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd, skyEnd, cloudEnd
        for (int i = 3; i <= 8; i++) {
            float val = args.get(i);
            if (Math.abs(val) < 1.0e9f) {
                args.set(i, val * scale);
            }
        }
    }
}
