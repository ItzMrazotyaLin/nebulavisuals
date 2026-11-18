package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.AnimationsCullingModule;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Отключает анимированный перелив зачарований на надетой броне при активном {@code AnimationsCulling -> Enchant Glint}.
 */
@Mixin(EquipmentRenderer.class)
public class EquipmentRendererMixin {

    @Redirect(
            method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/item/ItemStack;hasGlint()Z"
            ),
            require = 0
    )
    private boolean nebulavisuals$disableArmorGlint(ItemStack stack) {
        AnimationsCullingModule module = ModuleManager.getAnimationsCullingModule();
        if (module != null && module.shouldDisableEnchantGlint()) {
            return false;
        }
        return stack.hasGlint();
    }
}
