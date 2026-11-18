package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.utilities.ZoomModule;
import me.itzmr.nebulavisuals.render.hud.HudEditor;
import me.itzmr.nebulavisuals.render.hud.KeystrokesRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехватывает нажатие/отпускание ЛКМ для перетаскивания HUD, колёсико мыши для Zoom и масштабирует чувствительность при приближении.
 */
@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$handleHudDragMouseButton(long window, MouseInput input, int action, CallbackInfo ci) {
        if (this.client == null) {
            return;
        }
        if (this.client.currentScreen == null && action == GLFW.GLFW_PRESS) {
            KeystrokesRenderer.onMouseClick(input.button());
        }
        if (!(this.client.currentScreen instanceof ChatScreen)) {
            if (HudEditor.isDragging() || HudEditor.isResizing()) {
                HudEditor.onMouseReleased();
            }
            return;
        }

        if (input.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW.GLFW_PRESS && HudEditor.onMousePressed(this.client, input.button())) {
                ci.cancel();
            } else if (action == GLFW.GLFW_RELEASE && (HudEditor.isDragging() || HudEditor.isResizing())) {
                HudEditor.onMouseReleased();
                ci.cancel();
            }
        }
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$handleZoomScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (this.client == null || this.client.getWindow() == null || window != this.client.getWindow().getHandle()) {
            return;
        }
        if (this.client.currentScreen instanceof ChatScreen) {
            if (HudEditor.onMouseScroll(this.client, vertical)) {
                ci.cancel();
            }
            return;
        }
        if (this.client.currentScreen != null) {
            return;
        }
        ZoomModule zoom = ModuleManager.getZoomModule();
        if (zoom != null && zoom.handleMouseScroll(vertical)) {
            ci.cancel();
        }
    }

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void nebulavisuals$scaleZoomSensitivity(double timeDelta, CallbackInfo ci) {
        if (this.client == null || this.client.currentScreen != null) {
            return;
        }
        ZoomModule zoom = ModuleManager.getZoomModule();
        if (zoom == null) {
            return;
        }
        double mult = zoom.getSensitivityMultiplier();
        if (mult < 0.999) {
            this.cursorDeltaX *= mult;
            this.cursorDeltaY *= mult;
        }
    }
}
