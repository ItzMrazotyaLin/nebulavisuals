package org.itzmr.nebulavisuals.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.AnimationsCullingModule;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Выборочно замораживает анимацию спрайтов воды/лавы, порталов/костров и якоря возрождения
 * для {@link AnimationsCullingModule}, не затрагивая остальные текстуры.
 */
@Mixin(SpriteAtlasTexture.class)
public class SpriteAtlasTextureMixin {
    @Unique private final Map<SpriteContents.Animator, String> nebulavisuals$animatorPaths = new IdentityHashMap<>();

    @Inject(method = "clear", at = @At("HEAD"), require = 0)
    private void nebulavisuals$clearTrackedAnimators(CallbackInfo ci) {
        nebulavisuals$animatorPaths.clear();
    }

    @Redirect(
            method = "create",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/texture/Sprite;createAnimator(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)Lnet/minecraft/client/texture/SpriteContents$Animator;"
            ),
            require = 0
    )
    private SpriteContents.Animator nebulavisuals$trackAnimatorPath(
            Sprite sprite, GpuBufferSlice bufferSlice, int infoSize) {
        SpriteContents.Animator animator = sprite.createAnimator(bufferSlice, infoSize);
        if (animator != null && sprite.getContents() != null) {
            Identifier spriteId = sprite.getContents().getId();
            if (spriteId != null) {
                nebulavisuals$animatorPaths.put(animator, spriteId.getPath());
            }
        }
        return animator;
    }

    @Redirect(
            method = "tickAnimatedSprites",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/texture/SpriteContents$Animator;tick()V"
            ),
            require = 0
    )
    private void nebulavisuals$skipFrozenSpriteAnimations(SpriteContents.Animator animator) {
        AnimationsCullingModule module = ModuleManager.getAnimationsCullingModule();
        if (module != null && module.isEnabled()) {
            String path = nebulavisuals$animatorPaths.get(animator);
            if (module.shouldFreezeSprite(path)) {
                return;
            }
        }
        animator.tick();
    }
}
