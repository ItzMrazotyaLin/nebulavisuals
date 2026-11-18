package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.SmallerRainModule;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.client.render.state.WeatherRenderState;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Миксин {@link WeatherRendering}: реализует модуль {@code Smaller Rain},
 * сужая горизонтальную ширину капель/снежинок ({@code NORMAL_LINE_DX/DZ}) и уменьшая их прозрачность.
 */
@Mixin(WeatherRendering.class)
public class WeatherRenderingMixin {
    @Shadow @Final private float[] NORMAL_LINE_DX;
    @Shadow @Final private float[] NORMAL_LINE_DZ;

    @Unique
    private float[] nebulavisuals$baseDx;

    @Unique
    private float[] nebulavisuals$baseDz;

    @Unique
    private float nebulavisuals$appliedScale = 1.0f;

    @Inject(method = "renderPrecipitation", at = @At("HEAD"))
    private void nebulavisuals$updateRainGeometryScale(
            VertexConsumerProvider vertexConsumers, Vec3d pos, WeatherRenderState state, CallbackInfo ci) {
        if (this.nebulavisuals$baseDx == null) {
            this.nebulavisuals$baseDx = this.NORMAL_LINE_DX.clone();
            this.nebulavisuals$baseDz = this.NORMAL_LINE_DZ.clone();
        }

        SmallerRainModule module = ModuleManager.getSmallerRainModule();
        float targetScale = (module != null && module.isEnabled())
                ? Math.clamp(module.scale.getValue().floatValue(), 0.1f, 1.0f)
                : 1.0f;

        if (Math.abs(this.nebulavisuals$appliedScale - targetScale) > 1e-4f) {
            this.nebulavisuals$appliedScale = targetScale;
            for (int i = 0; i < this.NORMAL_LINE_DX.length; i++) {
                this.NORMAL_LINE_DX[i] = this.nebulavisuals$baseDx[i] * targetScale;
                this.NORMAL_LINE_DZ[i] = this.nebulavisuals$baseDz[i] * targetScale;
            }
        }
    }

    @ModifyVariable(method = "renderPieces", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float nebulavisuals$scalePrecipitationOpacity(float intensity) {
        SmallerRainModule module = ModuleManager.getSmallerRainModule();
        if (module != null && module.isEnabled()) {
            return intensity * Math.clamp(module.opacity.getValue().floatValue(), 0.05f, 1.0f);
        }
        return intensity;
    }
}
