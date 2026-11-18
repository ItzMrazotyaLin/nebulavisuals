package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.CustomSkyModule;
import me.itzmr.nebulavisuals.render.visuals.NebulaSkyRenderer;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.world.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Миксин {@link SkyRendering}: полностью заменяет ванильный купол неба,
 * свечение заката/рассвета, солнце, луну, звёзды и тёмную нижнюю полусферу
 * на процедурный GLSL-скайбокс {@link NebulaSkyRenderer} при включённом {@code CustomSky}.
 */
@Mixin(value = SkyRendering.class, priority = 900)
public class SkyRenderingMixin {

    @Inject(method = "renderTopSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$renderProceduralSky(int skyColor, CallbackInfo ci) {
        CustomSkyModule module = ModuleManager.getCustomSkyModule();
        if (module != null && module.hasCustomAtmosphere()) {
            NebulaSkyRenderer.render(module);
            ci.cancel();
        }
    }

    @Inject(method = "renderGlowingSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$cancelVanillaSunriseGlow(MatrixStack matrices, float sunAngle, int color, CallbackInfo ci) {
        CustomSkyModule module = ModuleManager.getCustomSkyModule();
        if (module != null && module.hasCustomAtmosphere()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderCelestialBodies", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$cancelVanillaCelestialBodies(
            MatrixStack matrices,
            float sunAngle,
            float moonAngle,
            float starAngle,
            MoonPhase moonPhase,
            float rainGradient,
            float starBrightness,
            CallbackInfo ci) {
        CustomSkyModule module = ModuleManager.getCustomSkyModule();
        if (module != null && module.hasCustomAtmosphere()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSkyDark", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$cancelVanillaSkyDark(CallbackInfo ci) {
        CustomSkyModule module = ModuleManager.getCustomSkyModule();
        if (module != null && module.hasCustomAtmosphere()) {
            ci.cancel();
        }
    }
}
