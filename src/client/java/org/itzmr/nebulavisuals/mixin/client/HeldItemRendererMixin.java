package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import me.itzmr.nebulavisuals.module.visuals.ViewModelModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Миксин {@link HeldItemRenderer}:
 * применение смещений и пивотного скейла {@code ViewModel} для предметов в руках от первого лица.
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {

    @Unique
    private boolean nebulavisuals$viewModelTranslated = false;

    @Inject(
            method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nebulavisuals$hideHandDuringF5Return(
            float tickProgress,
            MatrixStack matrices,
            OrderedRenderCommandQueue orderedRenderCommandQueue,
            ClientPlayerEntity player,
            int light,
            CallbackInfo ci) {
        if (AnimationsModule.shouldHideFirstPersonHand()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void nebulavisuals$captureRenderContext(
            AbstractClientPlayerEntity player,
            float tickProgress,
            float pitch,
            Hand hand,
            float swingProgress,
            ItemStack item,
            float equipProgress,
            MatrixStack matrices,
            OrderedRenderCommandQueue orderedRenderCommandQueue,
            int light,
            CallbackInfo ci) {
        this.nebulavisuals$viewModelTranslated = false;
    }

    @Inject(method = "renderFirstPersonItem", at = @At("RETURN"))
    private void nebulavisuals$clearRenderContext(
            AbstractClientPlayerEntity player,
            float tickProgress,
            float pitch,
            Hand hand,
            float swingProgress,
            ItemStack item,
            float equipProgress,
            MatrixStack matrices,
            OrderedRenderCommandQueue orderedRenderCommandQueue,
            int light,
            CallbackInfo ci) {
        this.nebulavisuals$viewModelTranslated = false;
    }

    /**
     * Сдвигает предмет по ViewModel до {@code swingArm}, чтобы ванильный взмах происходил в смещённой позиции.
     */
    @Inject(
            method = "renderFirstPersonItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;swingArm(FLnet/minecraft/client/util/math/MatrixStack;ILnet/minecraft/util/Arm;)V"
            )
    )
    private void nebulavisuals$applyViewModelBeforeSwing(
            AbstractClientPlayerEntity player,
            float tickProgress,
            float pitch,
            Hand hand,
            float swingProgress,
            ItemStack item,
            float equipProgress,
            MatrixStack matrices,
            OrderedRenderCommandQueue orderedRenderCommandQueue,
            int light,
            CallbackInfo ci) {
        nebulavisuals$applyViewModelTranslationIfNeeded(player, hand, item, matrices);
    }

    @Unique
    private void nebulavisuals$applyViewModelTranslationIfNeeded(
            AbstractClientPlayerEntity player, Hand hand, ItemStack item, MatrixStack matrices) {
        if (this.nebulavisuals$viewModelTranslated || item == null || item.isEmpty()) {
            return;
        }
        ViewModelModule module = ModuleManager.getViewModelModule();
        if (module == null || !module.isEnabled()) {
            return;
        }
        if (module.onlySword.getValue() && !item.isIn(ItemTags.SWORDS)) {
            return;
        }

        boolean isMainHand = hand == Hand.MAIN_HAND;
        Arm arm = isMainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        float side = arm == Arm.RIGHT ? 1.0f : -1.0f;

        float x = (isMainHand ? module.mainHandX : module.offHandX).getValue().floatValue();
        float y = (isMainHand ? module.mainHandY : module.offHandY).getValue().floatValue();
        float z = (isMainHand ? module.mainHandZ : module.offHandZ).getValue().floatValue();

        matrices.translate(x * side, y, z);
        this.nebulavisuals$viewModelTranslated = true;
    }

    @Inject(
            method = "renderFirstPersonItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"
            )
    )
    private void nebulavisuals$applyViewModel(
            AbstractClientPlayerEntity player,
            float tickProgress,
            float pitch,
            Hand hand,
            float swingProgress,
            ItemStack item,
            float equipProgress,
            MatrixStack matrices,
            OrderedRenderCommandQueue orderedRenderCommandQueue,
            int light,
            CallbackInfo ci) {
        if (item == null || item.isEmpty()) {
            return;
        }

        ViewModelModule module = ModuleManager.getViewModelModule();
        if (module == null || !module.isEnabled()) {
            return;
        }

        if (module.onlySword.getValue() && !item.isIn(ItemTags.SWORDS)) {
            return;
        }

        // Если предмет не проходил через swingArm (например, лук/еда), применяем смещение здесь
        nebulavisuals$applyViewModelTranslationIfNeeded(player, hand, item, matrices);

        float scale = module.itemScale.getValue().floatValue();

        // Смещаем пивот к рукояти/центру модели перед scale и возвращаем обратно
        matrices.translate(0.0f, -0.15f, 0.0f);
        matrices.scale(scale, scale, scale);
        matrices.translate(0.0f, 0.15f, 0.0f);
    }
}
