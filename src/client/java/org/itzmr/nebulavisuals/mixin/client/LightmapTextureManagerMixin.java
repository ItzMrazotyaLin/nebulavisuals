package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.utilities.FullbrightModule;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Миксин {@link LightmapTextureManager}: перехватывает гамму освещения при включённом
 * {@code Fullbright} без наложения эффекта ночного зрения и без изменения настроек игры.
 */
@Mixin(LightmapTextureManager.class)
public class LightmapTextureManagerMixin {

    @Shadow private boolean dirty;

    @Unique private boolean nebulavisuals$wasFullbrightEnabled = false;
    @Unique private float nebulavisuals$lastBrightness = -1.0f;

    @Inject(method = "update", at = @At("HEAD"), require = 0)
    private void nebulavisuals$refreshDirtyOnFullbrightChange(float tickProgress, CallbackInfo ci) {
        FullbrightModule module = ModuleManager.getFullbrightModule();
        boolean enabled = module != null && module.isEnabled();
        float brightness = module != null ? module.brightness.getValue().floatValue() : 1.0f;

        if (enabled != this.nebulavisuals$wasFullbrightEnabled
                || (enabled && Math.abs(brightness - this.nebulavisuals$lastBrightness) > 0.001f)) {
            this.dirty = true;
            this.nebulavisuals$wasFullbrightEnabled = enabled;
            this.nebulavisuals$lastBrightness = brightness;
        }
    }

    @Redirect(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Double;floatValue()F",
                    ordinal = 1
            ),
            require = 0
    )
    private float nebulavisuals$overrideLightmapGamma(Double instance) {
        float vanillaGamma = instance != null ? instance.floatValue() : 0.0f;
        FullbrightModule module = ModuleManager.getFullbrightModule();
        if (module != null && module.isEnabled()) {
            return Math.max(vanillaGamma, module.brightness.getValue().floatValue());
        }
        return vanillaGamma;
    }
}
