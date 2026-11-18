package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import me.itzmr.nebulavisuals.render.engine2d.SmoothScrollAccess;
import net.minecraft.client.gui.widget.ScrollableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Перехватывает прокрутку колёсиком мыши в {@link ScrollableWidget#mouseScrolled}
 * и передаёт её в плавный целевой скролл {@link SmoothScrollAccess}.
 */
@Mixin(ScrollableWidget.class)
public abstract class ScrollableWidgetMixin {
    @Shadow protected abstract double getDeltaYPerScroll();

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$smoothWheelScroll(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount,
            CallbackInfoReturnable<Boolean> cir) {
        ScrollableWidget self = (ScrollableWidget) (Object) this;
        if (!self.visible || !AnimationsModule.isGuiAnimationsActive()) {
            return;
        }
        if ((Object) this instanceof SmoothScrollAccess access) {
            double nextTarget = access.nebulavisuals$getTargetScrollY() - verticalAmount * this.getDeltaYPerScroll();
            access.nebulavisuals$scrollByWheel(nextTarget);
            cir.setReturnValue(true);
        }
    }
}
