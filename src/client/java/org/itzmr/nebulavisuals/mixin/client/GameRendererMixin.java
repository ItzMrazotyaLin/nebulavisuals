package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.UnfocusModeModule;
import me.itzmr.nebulavisuals.module.utilities.NoRenderModule;
import org.lwjgl.glfw.GLFW;
import me.itzmr.nebulavisuals.module.utilities.ZoomModule;
import me.itzmr.nebulavisuals.render.hud.ArmorRenderer;
import me.itzmr.nebulavisuals.render.hud.CooldownsRenderer;
import me.itzmr.nebulavisuals.render.hud.HudEditor;
import me.itzmr.nebulavisuals.render.hud.KeystrokesRenderer;
import me.itzmr.nebulavisuals.render.hud.NotificationsRenderer;
import me.itzmr.nebulavisuals.render.hud.PotionsRenderer;
import me.itzmr.nebulavisuals.render.hud.TargetHudRenderer;
import me.itzmr.nebulavisuals.render.hud.WatermarkRenderer;
import me.itzmr.nebulavisuals.render.visuals.MotionBlurRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Рендерит кастомный HUD (Watermark, TargetHUD и оверлеи HudEditor) сразу после завершения GuiRenderer.render(...),
 * чтобы DrawContext / GuiRenderState не перекрывал наш прямой 2D-батчер и KawaseBlur.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/fog/FogRenderer;rotate()V",
                    shift = At.Shift.AFTER
            )
    )
    private void nebulavisuals$renderMotionBlurAfterWorld(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        MotionBlurRenderer.render(this.client);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"
            )
    )
    private void nebulavisuals$renderLayeredHudGlassBeforeGui(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        CooldownsRenderer.renderGlassBackgrounds(this.client);
        ArmorRenderer.renderGlassBackgrounds(this.client);
        PotionsRenderer.renderGlassBackgrounds(this.client);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void nebulavisuals$renderCustomHud(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        HudEditor.beginFrame(this.client);
        me.itzmr.nebulavisuals.render.visuals.PredictionsRenderer.renderFlightBadgeOverlay(this.client);
        me.itzmr.nebulavisuals.render.hud.CrosshairRenderer.render(this.client);
        WatermarkRenderer.render(this.client);
        TargetHudRenderer.render(this.client);
        KeystrokesRenderer.render(this.client);
        NotificationsRenderer.render(this.client);
        HudEditor.renderStandaloneOverlays(this.client);
    }

    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$cancelHurtCam(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        NoRenderModule module = ModuleManager.getNoRenderModule();
        if (module != null && module.shouldCancelHurtCam()) {
            ci.cancel();
        }
    }

    @Inject(method = "showFloatingItem", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$cancelTotemPopAnimation(ItemStack floatingItem, CallbackInfo ci) {
        NoRenderModule module = ModuleManager.getNoRenderModule();
        if (module != null && module.shouldCancelTotem()) {
            ci.cancel();
        }
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void nebulavisuals$applyZoomFov(
            Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (!changingFov) {
            return;
        }
        ZoomModule zoom = ModuleManager.getZoomModule();
        if (zoom == null) {
            return;
        }
        float factor = zoom.updateAndGetZoomFactor();
        if (factor > 1.001f) {
            cir.setReturnValue(Math.max(4.0f, cir.getReturnValueF() / factor));
        }
    }

    @Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true, require = 0)
    private void nebulavisuals$skipMinimizedWorldRender(RenderTickCounter tickCounter, CallbackInfo ci) {
        UnfocusModeModule module = ModuleManager.getUnfocusModeModule();
        if (module == null || !module.isEnabled() || !module.skipWorldRender.getValue()) {
            return;
        }
        if (this.client != null && this.client.getWindow() != null) {
            long handle = this.client.getWindow().getHandle();
            if (handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
                ci.cancel();
            }
        }
    }
}
