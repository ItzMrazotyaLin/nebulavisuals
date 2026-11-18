package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.SelfNametagModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Показывает стандартный неймтег локального игрока (включая командные префиксы и цвета)
 * при виде от третьего лица (F5), когда включён модуль {@code SelfNametag}.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {

    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$showSelfNametag(T entity, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        SelfNametagModule module = ModuleManager.getSelfNametagModule();
        if (module == null || !module.isEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null
                && entity == client.player
                && !client.options.getPerspective().isFirstPerson()
                && !client.options.hudHidden) {
            cir.setReturnValue(true);
        }
    }
}
