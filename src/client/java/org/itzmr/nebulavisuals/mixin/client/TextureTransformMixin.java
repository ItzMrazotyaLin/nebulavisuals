package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.AnimationsCullingModule;
import net.minecraft.client.render.TextureTransform;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Останавливает скролл текстуры блика зачарования в режиме {@code AnimationsCulling -> Enchant Glint: Static}.
 */
@Mixin(TextureTransform.class)
public class TextureTransformMixin {

    @Redirect(
            method = "getGlintTransformation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Util;getMeasuringTimeMs()J"
            ),
            require = 0
    )
    private static long nebulavisuals$freezeGlintTime() {
        AnimationsCullingModule module = ModuleManager.getAnimationsCullingModule();
        if (module != null && module.shouldFreezeEnchantGlint()) {
            return 0L;
        }
        return Util.getMeasuringTimeMs();
    }
}
