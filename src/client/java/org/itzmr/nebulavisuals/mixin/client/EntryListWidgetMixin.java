package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import me.itzmr.nebulavisuals.render.engine2d.SmoothScrollAccess;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Плавный инерционный скролл ({@code Smooth Scroll}) для всех ванильных списков
 * ({@link EntryListWidget}: настройки, клавиши, ресурс-паки, серверы, миры и т.д.).
 */
@Mixin(EntryListWidget.class)
public class EntryListWidgetMixin implements SmoothScrollAccess {
    @Unique private static final float SCROLL_LERP_RATE = 16.0f;

    @Unique private double nebulavisuals$targetScrollY = 0.0;
    @Unique private boolean nebulavisuals$hasTarget = false;
    @Unique private boolean nebulavisuals$internalScrollUpdate = false;
    @Unique private long nebulavisuals$lastRenderNs = 0L;

    @Override
    public double nebulavisuals$getTargetScrollY() {
        EntryListWidget<?> self = (EntryListWidget<?>) (Object) this;
        if (!this.nebulavisuals$hasTarget) {
            this.nebulavisuals$targetScrollY = self.getScrollY();
            this.nebulavisuals$hasTarget = true;
        }
        return this.nebulavisuals$targetScrollY;
    }

    @Override
    public void nebulavisuals$scrollByWheel(double newTargetScrollY) {
        EntryListWidget<?> self = (EntryListWidget<?>) (Object) this;
        this.nebulavisuals$targetScrollY = MathHelper.clamp(newTargetScrollY, 0.0, self.getMaxScrollY());
        this.nebulavisuals$hasTarget = true;
        if (this.nebulavisuals$lastRenderNs == 0L) {
            this.nebulavisuals$lastRenderNs = System.nanoTime();
        }
    }

    @Inject(method = "setScrollY", at = @At("HEAD"))
    private void nebulavisuals$syncExternalScrollSet(double scrollY, CallbackInfo ci) {
        if (this.nebulavisuals$internalScrollUpdate) {
            return;
        }
        EntryListWidget<?> self = (EntryListWidget<?>) (Object) this;
        this.nebulavisuals$targetScrollY = MathHelper.clamp(scrollY, 0.0, self.getMaxScrollY());
        this.nebulavisuals$hasTarget = true;
    }

    @Inject(method = "renderWidget", at = @At("HEAD"))
    private void nebulavisuals$interpolateListScroll(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        EntryListWidget<?> self = (EntryListWidget<?>) (Object) this;
        double maxScroll = self.getMaxScrollY();
        if (!this.nebulavisuals$hasTarget) {
            this.nebulavisuals$targetScrollY = MathHelper.clamp(self.getScrollY(), 0.0, maxScroll);
            this.nebulavisuals$hasTarget = true;
        } else {
            this.nebulavisuals$targetScrollY = MathHelper.clamp(this.nebulavisuals$targetScrollY, 0.0, maxScroll);
        }

        if (!AnimationsModule.isGuiAnimationsActive()) {
            this.nebulavisuals$lastRenderNs = 0L;
            if (Math.abs(self.getScrollY() - this.nebulavisuals$targetScrollY) > 0.01) {
                this.nebulavisuals$applyScroll(self, this.nebulavisuals$targetScrollY);
            }
            return;
        }

        long now = System.nanoTime();
        float deltaTime = this.nebulavisuals$lastRenderNs == 0L
                ? 0.0f
                : MathHelper.clamp((now - this.nebulavisuals$lastRenderNs) / 1_000_000_000.0f, 0.0f, 0.05f);
        this.nebulavisuals$lastRenderNs = now;

        double current = self.getScrollY();
        double diff = this.nebulavisuals$targetScrollY - current;
        if (Math.abs(diff) > 0.05) {
            AnimationsModule animations = ModuleManager.getAnimationsModule();
            float speed = animations != null ? animations.getSpeedMultiplier() : 1.2f;
            double lerp = deltaTime <= 0.0f ? 0.0 : 1.0 - Math.exp(-deltaTime * SCROLL_LERP_RATE * speed);
            this.nebulavisuals$applyScroll(self, current + diff * lerp);
        } else if (Math.abs(diff) > 0.0001) {
            this.nebulavisuals$applyScroll(self, this.nebulavisuals$targetScrollY);
        }
    }

    @Unique
    private void nebulavisuals$applyScroll(EntryListWidget<?> self, double value) {
        this.nebulavisuals$internalScrollUpdate = true;
        try {
            self.setScrollY(value);
        } finally {
            this.nebulavisuals$internalScrollUpdate = false;
        }
    }
}
