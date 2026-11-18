package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.optimization.CullingHelper;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Максимально раннее отсечение рендера сущностей до проверки Frustum, bounding box,
 * подготовки матриц, модели и теней (EntityCulling).
 * Игроки, снаряды и эндер-кристаллы никогда не отсекаются.
 */
@Mixin(EntityRenderManager.class)
public class EntityRenderManagerMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private <E extends Entity> void nebulavisuals$cullDistantEntitiesEarly(
            E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (CullingHelper.shouldCullEntity(entity, x, y, z)) {
            cir.setReturnValue(false);
        }
    }
}
