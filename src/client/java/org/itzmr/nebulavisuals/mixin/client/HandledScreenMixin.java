package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.render.engine2d.AnimatedScreenAccess;
import me.itzmr.nebulavisuals.render.engine2d.CreativeScrollAccess;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Применяет масштаб открытия к {@code HandledScreen#drawBackground},
 * синхронный сдвиг и {@code scissor}-обрезку слотов/подсветки/хитбокса наведения
 * для настоящего Smooth Scrolling в креативном инвентаре.
 */
@Mixin(HandledScreen.class)
public class HandledScreenMixin {
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected Slot focusedSlot;

    @Unique private boolean nebulavisuals$pushedBgScale = false;
    @Unique private boolean nebulavisuals$pushedSlotTransform = false;
    @Unique private boolean nebulavisuals$pushedHighlightTransform = false;

    @Inject(
            method = "renderBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawBackground(Lnet/minecraft/client/gui/DrawContext;FII)V"
            )
    )
    private void nebulavisuals$pushContainerBgScale(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        this.nebulavisuals$pushedBgScale = false;
        if (!((Object) this instanceof AnimatedScreenAccess access) || !access.nebulavisuals$isScaleActive()) {
            return;
        }
        Screen screen = (Screen) (Object) this;
        float scale = access.nebulavisuals$getScale();
        float centerX = screen.width / 2.0f;
        float centerY = screen.height / 2.0f;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(centerX, centerY);
        context.getMatrices().scale(scale, scale);
        context.getMatrices().translate(-centerX, -centerY);
        this.nebulavisuals$pushedBgScale = true;
    }

    @Inject(
            method = "renderBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawBackground(Lnet/minecraft/client/gui/DrawContext;FII)V",
                    shift = At.Shift.AFTER
            )
    )
    private void nebulavisuals$popContainerBgScale(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (this.nebulavisuals$pushedBgScale) {
            context.getMatrices().popMatrix();
            this.nebulavisuals$pushedBgScale = false;
        }
    }

    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void nebulavisuals$pushCreativeSlotOffset(DrawContext context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        this.nebulavisuals$pushedSlotTransform = false;
        if (!((Object) this instanceof CreativeScrollAccess scrollAccess)) {
            return;
        }
        if (!scrollAccess.nebulavisuals$isCreativeScrollTransformActive()
                || !scrollAccess.nebulavisuals$isCreativeGridSlot(slot)) {
            return;
        }
        context.enableScissor(8, 17, 170, 107);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(0.0f, scrollAccess.nebulavisuals$getSlotYOffset());
        this.nebulavisuals$pushedSlotTransform = true;
    }

    @Inject(method = "drawSlot", at = @At("RETURN"))
    private void nebulavisuals$popCreativeSlotOffset(DrawContext context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if ((Object) this instanceof CreativeScrollAccess scrollAccess && scrollAccess.nebulavisuals$shouldFadeSlot(slot)) {
            float alpha = 1.0f - MathHelper.clamp(scrollAccess.nebulavisuals$getTabFadeAlpha(), 0.0f, 1.0f);
            int a = MathHelper.clamp(Math.round(alpha * 220.0f), 0, 220);
            if (a > 2) {
                context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, (a << 24) | 0x8B8B8B);
            }
        }
        if (this.nebulavisuals$pushedSlotTransform) {
            context.getMatrices().popMatrix();
            context.disableScissor();
            this.nebulavisuals$pushedSlotTransform = false;
        }
    }

    @Inject(method = "drawSlots", at = @At("TAIL"))
    private void nebulavisuals$drawExtraPeekingRow(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        if ((Object) this instanceof CreativeScrollAccess scrollAccess) {
            scrollAccess.nebulavisuals$renderExtraBottomRow(context);
        }
    }

    @Inject(method = "drawSlotHighlightBack", at = @At("HEAD"))
    private void nebulavisuals$pushHighlightBackOffset(DrawContext context, CallbackInfo ci) {
        this.nebulavisuals$pushFocusedHighlightTransform(context);
    }

    @Inject(method = "drawSlotHighlightBack", at = @At("RETURN"))
    private void nebulavisuals$popHighlightBackOffset(DrawContext context, CallbackInfo ci) {
        this.nebulavisuals$popFocusedHighlightTransform(context);
    }

    @Inject(method = "drawSlotHighlightFront", at = @At("HEAD"))
    private void nebulavisuals$pushHighlightFrontOffset(DrawContext context, CallbackInfo ci) {
        this.nebulavisuals$pushFocusedHighlightTransform(context);
    }

    @Inject(method = "drawSlotHighlightFront", at = @At("RETURN"))
    private void nebulavisuals$popHighlightFrontOffset(DrawContext context, CallbackInfo ci) {
        this.nebulavisuals$popFocusedHighlightTransform(context);
    }

    @Inject(method = "isPointOverSlot", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$clipCreativeHoverBounds(Slot slot, double pointX, double pointY, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof CreativeScrollAccess scrollAccess)) {
            return;
        }
        if (!scrollAccess.nebulavisuals$isCreativeScrollTransformActive()
                || !scrollAccess.nebulavisuals$isCreativeGridSlot(slot)) {
            return;
        }
        double relX = pointX - this.x;
        double relY = pointY - this.y;
        if (relX < 8.0 || relX >= 170.0 || relY < 17.0 || relY >= 107.0) {
            cir.setReturnValue(false);
        }
    }

    @ModifyVariable(method = "isPointOverSlot", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private double nebulavisuals$adjustHoverYForCreativeScroll(double pointY, Slot slot) {
        if ((Object) this instanceof CreativeScrollAccess scrollAccess
                && scrollAccess.nebulavisuals$isCreativeScrollTransformActive()
                && scrollAccess.nebulavisuals$isCreativeGridSlot(slot)) {
            return pointY - scrollAccess.nebulavisuals$getSlotYOffset();
        }
        return pointY;
    }

    @Unique
    private void nebulavisuals$pushFocusedHighlightTransform(DrawContext context) {
        this.nebulavisuals$pushedHighlightTransform = false;
        if (!((Object) this instanceof CreativeScrollAccess scrollAccess)) {
            return;
        }
        if (!scrollAccess.nebulavisuals$isCreativeScrollTransformActive()
                || !scrollAccess.nebulavisuals$isCreativeGridSlot(this.focusedSlot)) {
            return;
        }
        context.enableScissor(8, 17, 170, 107);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(0.0f, scrollAccess.nebulavisuals$getSlotYOffset());
        this.nebulavisuals$pushedHighlightTransform = true;
    }

    @Unique
    private void nebulavisuals$popFocusedHighlightTransform(DrawContext context) {
        if (this.nebulavisuals$pushedHighlightTransform) {
            context.getMatrices().popMatrix();
            context.disableScissor();
            this.nebulavisuals$pushedHighlightTransform = false;
        }
    }
}
