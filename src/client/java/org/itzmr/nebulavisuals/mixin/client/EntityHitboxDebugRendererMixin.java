package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.HitboxesModule;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.debug.EntityHitboxDebugRenderer;
import net.minecraft.world.debug.DebugDataStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Подавляет стандартные белые хитбоксы F3+B при активном модуле {@link HitboxesModule}.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public class EntityHitboxDebugRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$cancelVanillaHitboxes(
            double cameraX,
            double cameraY,
            double cameraZ,
            DebugDataStore dataStore,
            Frustum frustum,
            float tickProgress,
            CallbackInfo ci
    ) {
        HitboxesModule module = ModuleManager.getHitboxesModule();
        if (module != null && module.isEnabled()) {
            ci.cancel();
        }
    }
}
