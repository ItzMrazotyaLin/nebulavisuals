package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.CustomSkyModule;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Миксин для клиентской подмены времени суток (модуль {@code CustomSky}).
 */
@Mixin(ClientWorld.Properties.class)
public class ClientWorldPropertiesMixin {

    @Inject(method = "getTimeOfDay", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$overrideTimeOfDay(CallbackInfoReturnable<Long> cir) {
        CustomSkyModule module = ModuleManager.getCustomSkyModule();
        if (module != null && module.isEnabled() && module.customTime.getValue()) {
            cir.setReturnValue(module.time.getValue().longValue());
        }
    }
}
