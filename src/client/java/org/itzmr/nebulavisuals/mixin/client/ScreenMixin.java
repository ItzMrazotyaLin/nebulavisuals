package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import me.itzmr.nebulavisuals.render.engine2d.AnimatedScreenAccess;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Реализует плавное открытие ({@code Scale 0.94 -> 1.0} + {@code Fade}) исключительно для контейнеров/инвентаря
 * ({@link HandledScreen}) и выезд снизу вверх + {@code Fade} для {@link ChatScreen},
 * не затрагивая меню паузы ({@code ESC}) и экраны настроек.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin implements AnimatedScreenAccess {
    @Unique private static final float GUI_BASE_SPEED = 4.8f;
    @Unique private static final float MIN_OPEN_SCALE = 0.94f;
    @Unique private static final float CHAT_SLIDE_OFFSET_PX = 18.0f;

    @Shadow public int width;
    @Shadow public int height;

    @Shadow protected abstract void setWidgetAlpha(float alpha);

    @Unique private float nebulavisuals$animProgress = 0.0f;
    @Unique private long nebulavisuals$lastFrameNs = 0L;
    @Unique private boolean nebulavisuals$appliedTransform = false;
    @Unique private boolean nebulavisuals$modifiedWidgetAlpha = false;

    @Unique
    private boolean nebulavisuals$isContainerScreen() {
        return (Object) this instanceof HandledScreen<?>;
    }

    @Unique
    private boolean nebulavisuals$isChatScreen() {
        return (Object) this instanceof ChatScreen;
    }

    @Unique
    private boolean nebulavisuals$isAnimatedScreen() {
        return this.nebulavisuals$isContainerScreen() || this.nebulavisuals$isChatScreen();
    }

    @Override
    public boolean nebulavisuals$isScaleActive() {
        return AnimationsModule.isGuiAnimationsActive()
                && this.nebulavisuals$isContainerScreen()
                && this.nebulavisuals$animProgress < 1.0f;
    }

    @Override
    public float nebulavisuals$getScale() {
        float eased = AnimationsModule.easeOutCubic(this.nebulavisuals$animProgress);
        return MathHelper.lerp(eased, MIN_OPEN_SCALE, 1.0f);
    }

    @Override
    public float nebulavisuals$getEasedAlpha() {
        return AnimationsModule.easeOutCubic(this.nebulavisuals$animProgress);
    }

    @Inject(method = "onDisplayed", at = @At("HEAD"))
    private void nebulavisuals$resetOpenAnimation(CallbackInfo ci) {
        this.nebulavisuals$animProgress = 0.0f;
        this.nebulavisuals$lastFrameNs = 0L;
        this.nebulavisuals$appliedTransform = false;
    }

    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void nebulavisuals$updateOpenAnimation(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!AnimationsModule.isGuiAnimationsActive() || !this.nebulavisuals$isAnimatedScreen()) {
            this.nebulavisuals$animProgress = 1.0f;
            this.nebulavisuals$lastFrameNs = 0L;
            if (this.nebulavisuals$modifiedWidgetAlpha) {
                this.setWidgetAlpha(1.0f);
                this.nebulavisuals$modifiedWidgetAlpha = false;
            }
            return;
        }

        long now = System.nanoTime();
        float deltaTime = this.nebulavisuals$lastFrameNs == 0L
                ? 0.0f
                : MathHelper.clamp((now - this.nebulavisuals$lastFrameNs) / 1_000_000_000.0f, 0.0f, 0.05f);
        this.nebulavisuals$lastFrameNs = now;

        AnimationsModule animations = ModuleManager.getAnimationsModule();
        float speed = animations != null ? animations.getSpeedMultiplier() : 1.2f;
        this.nebulavisuals$animProgress = Math.min(1.0f, this.nebulavisuals$animProgress + deltaTime * GUI_BASE_SPEED * speed);

        if (this.nebulavisuals$animProgress < 1.0f) {
            float eased = AnimationsModule.easeOutCubic(this.nebulavisuals$animProgress);
            this.setWidgetAlpha(MathHelper.clamp(eased, 0.02f, 1.0f));
            this.nebulavisuals$modifiedWidgetAlpha = true;
        } else if (this.nebulavisuals$modifiedWidgetAlpha) {
            this.setWidgetAlpha(1.0f);
            this.nebulavisuals$modifiedWidgetAlpha = false;
        }
    }

    @Inject(
            method = "renderWithTooltip",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/Screen;render(Lnet/minecraft/client/gui/DrawContext;IIF)V"
            )
    )
    private void nebulavisuals$pushScreenTransform(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        this.nebulavisuals$appliedTransform = false;
        if (!AnimationsModule.isGuiAnimationsActive()
                || !this.nebulavisuals$isAnimatedScreen()
                || this.nebulavisuals$animProgress >= 1.0f) {
            return;
        }

        float eased = AnimationsModule.easeOutCubic(this.nebulavisuals$animProgress);
        if (this.nebulavisuals$isChatScreen()) {
            float offsetY = CHAT_SLIDE_OFFSET_PX * (1.0f - eased);
            if (offsetY > 0.01f) {
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(0.0f, offsetY);
                this.nebulavisuals$appliedTransform = true;
            }
            return;
        }

        float scale = MathHelper.lerp(eased, MIN_OPEN_SCALE, 1.0f);
        float centerX = this.width / 2.0f;
        float centerY = this.height / 2.0f;
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(centerX, centerY);
        context.getMatrices().scale(scale, scale);
        context.getMatrices().translate(-centerX, -centerY);
        this.nebulavisuals$appliedTransform = true;
    }

    @Inject(
            method = "renderWithTooltip",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/Screen;render(Lnet/minecraft/client/gui/DrawContext;IIF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void nebulavisuals$popScreenTransform(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (this.nebulavisuals$appliedTransform) {
            context.getMatrices().popMatrix();
            this.nebulavisuals$appliedTransform = false;
        }
    }

    @ModifyArgs(
            method = "renderInGameBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;fillGradient(IIIIII)V"
            )
    )
    private void nebulavisuals$fadeInGameBackdrop(Args args) {
        if (!this.nebulavisuals$isScaleActive()) {
            return;
        }
        float alphaFactor = MathHelper.clamp(this.nebulavisuals$getEasedAlpha(), 0.0f, 1.0f);
        args.set(4, nebulavisuals$scaleColorAlpha((Integer) args.get(4), alphaFactor));
        args.set(5, nebulavisuals$scaleColorAlpha((Integer) args.get(5), alphaFactor));
    }

    @Unique
    private static int nebulavisuals$scaleColorAlpha(int argb, float factor) {
        int a = MathHelper.clamp(Math.round(((argb >>> 24) & 0xFF) * factor), 0, 255);
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
