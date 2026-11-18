package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.CullingHelper;
import me.itzmr.nebulavisuals.module.utilities.FreelookModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Перехватывает поворот камеры мышью во время {@code Freelook},
 * а также отключает клиентские эффекты огня, свечения и неймтегов для отсечённых сущностей ({@code EntityCulling}).
 */
@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$captureFreelookMouse(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || (Object) this != client.player) {
            return;
        }
        FreelookModule freelook = ModuleManager.getFreelookModule();
        if (freelook != null && freelook.isCapturingMouse()) {
            float dYaw = (float) cursorDeltaX * 0.15f;
            float dPitch = (float) cursorDeltaY * 0.15f;
            freelook.addDelta(dYaw, dPitch);
            ci.cancel();
        }
    }

    @Inject(method = "doesRenderOnFire", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$skipCulledEntityFire(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.getEntityWorld().isClient() && CullingHelper.shouldCullEntity(self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isGlowing", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$skipCulledEntityGlow(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.getEntityWorld().isClient() && CullingHelper.shouldCullEntity(self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldRenderName", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$skipCulledEntityName(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.getEntityWorld().isClient() && CullingHelper.shouldCullEntity(self)) {
            cir.setReturnValue(false);
        }
    }
}
