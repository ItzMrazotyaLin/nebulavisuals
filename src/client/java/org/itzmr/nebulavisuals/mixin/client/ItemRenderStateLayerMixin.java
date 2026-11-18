package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.AnimationsCullingModule;
import net.minecraft.client.render.item.ItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Отключает перелив зачарований на предметах в руках и инвентаре при активном {@code AnimationsCulling -> Enchant Glint}.
 */
@Mixin(ItemRenderState.LayerRenderState.class)
public class ItemRenderStateLayerMixin {

    @ModifyVariable(method = "setGlint", at = @At("HEAD"), argsOnly = true, require = 0)
    private ItemRenderState.Glint nebulavisuals$disableItemGlint(ItemRenderState.Glint glint) {
        AnimationsCullingModule module = ModuleManager.getAnimationsCullingModule();
        if (module != null && module.shouldDisableEnchantGlint()) {
            return ItemRenderState.Glint.NONE;
        }
        return glint;
    }
}
