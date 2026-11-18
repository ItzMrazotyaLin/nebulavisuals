package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.UnfocusModeModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Снижает лимит FPS при потере фокуса окна игры ({@link UnfocusModeModule}).
 */
@Mixin(InactivityFpsLimiter.class)
public class InactivityFpsLimiterMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "update", at = @At("RETURN"), cancellable = true)
    private void nebulavisuals$capUnfocusedFps(CallbackInfoReturnable<Integer> cir) {
        UnfocusModeModule module = ModuleManager.getUnfocusModeModule();
        if (module == null || !module.isEnabled()) {
            return;
        }
        if (this.client != null && !this.client.isWindowFocused()) {
            int cap = module.getUnfocusedFpsCap();
            cir.setReturnValue(Math.min(cir.getReturnValueI(), cap));
        }
    }
}
