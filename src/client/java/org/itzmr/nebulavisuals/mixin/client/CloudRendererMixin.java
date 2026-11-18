package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.CustomSkyModule;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Отменяет ванильные квадратные облака при активном процедурном скайбоксе {@code CustomSky}.
 */
@Mixin(value = CloudRenderer.class, priority = 900)
public class CloudRendererMixin {

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$cancelVanillaClouds(
            int color,
            CloudRenderMode cloudRenderMode,
            float cloudHeight,
            Vec3d cameraPos,
            long time,
            float tickProgress,
            CallbackInfo ci) {
        CustomSkyModule module = ModuleManager.getCustomSkyModule();
        if (module != null && module.hasCustomAtmosphere()) {
            ci.cancel();
        }
    }
}
