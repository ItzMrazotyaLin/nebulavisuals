package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.BossbarChangerModule;
import me.itzmr.nebulavisuals.module.hud.WatermarkModule;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

/**
 * Сдвигает оригинальный ванильный {@link BossBarHud} по горизонтали (Left, Center, Right)
 * и аккуратно опускает его ниже {@code Watermark}, если она находится в зоне боссбара.
 */
@Mixin(BossBarHud.class)
public class BossBarHudMixin {
    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    @Final
    Map<UUID, ClientBossBar> bossBars;

    @Unique
    private boolean nebula$pushedMatrix = false;

    @Unique
    private float nebula$smoothOffsetX = 0.0f;

    @Unique
    private float nebula$smoothOffsetY = 0.0f;

    @Unique
    private long nebula$lastTimeNs = 0L;

    @Unique
    private boolean nebula$offsetInitialized = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void nebulavisuals$translateBossBarBegin(DrawContext context, CallbackInfo ci) {
        nebula$pushedMatrix = false;
        if (context == null || bossBars == null || bossBars.isEmpty()) {
            return;
        }
        BossbarChangerModule module = ModuleManager.getBossbarChangerModule();
        if (module == null || !module.isEnabled()) {
            nebula$offsetInitialized = false;
            nebula$smoothOffsetX = 0.0f;
            nebula$smoothOffsetY = 0.0f;
            return;
        }

        int scaledWidth = Math.max(1, context.getScaledWindowWidth());
        int scaledHeight = Math.max(1, context.getScaledWindowHeight());
        float defaultLeftX = scaledWidth * 0.5f - 91.0f;

        String pos = module.position.getValue();
        float targetOffsetX;
        float targetOffsetY;

        switch (pos) {
            case "Left" -> {
                targetOffsetX = 10.0f - defaultLeftX;
                targetOffsetY = nebula$computeWatermarkAvoidOffset(scaledWidth, scaledHeight, 10.0f, 192.0f);
            }
            case "Right" -> {
                targetOffsetX = (scaledWidth - 182.0f - 10.0f) - defaultLeftX;
                targetOffsetY = nebula$computeWatermarkAvoidOffset(scaledWidth, scaledHeight, scaledWidth - 192.0f, scaledWidth - 10.0f);
            }
            default -> {
                targetOffsetX = 0.0f;
                float centerX = scaledWidth * 0.5f;
                targetOffsetY = nebula$computeWatermarkAvoidOffset(scaledWidth, scaledHeight, centerX - 95.0f, centerX + 95.0f);
            }
        }

        long now = System.nanoTime();
        double dtRaw = nebula$lastTimeNs == 0L ? 0.016 : (now - nebula$lastTimeNs) / 1_000_000_000.0;
        nebula$lastTimeNs = now;
        float dt = Math.clamp((float) dtRaw * ClientSettings.getAnimationSpeed(), 0.0001f, 0.1f);

        if (!nebula$offsetInitialized) {
            nebula$smoothOffsetX = targetOffsetX;
            nebula$smoothOffsetY = targetOffsetY;
            nebula$offsetInitialized = true;
        } else {
            nebula$smoothOffsetX = AnimationEngine.expLerp(nebula$smoothOffsetX, targetOffsetX, 16.0f, dt);
            nebula$smoothOffsetY = AnimationEngine.expLerp(nebula$smoothOffsetY, targetOffsetY, 16.0f, dt);
        }

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(nebula$smoothOffsetX, nebula$smoothOffsetY);
        nebula$pushedMatrix = true;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void nebulavisuals$translateBossBarEnd(DrawContext context, CallbackInfo ci) {
        if (nebula$pushedMatrix && context != null) {
            context.getMatrices().popMatrix();
            nebula$pushedMatrix = false;
        }
    }

    @Unique
    private float nebula$computeWatermarkAvoidOffset(int scaledWidth, int scaledHeight, float barMinX, float barMaxX) {
        if (client == null || client.getWindow() == null) {
            return 0.0f;
        }
        WatermarkModule wm = ModuleManager.getWatermarkModule();
        if (wm == null || !wm.isEnabled()) {
            return 0.0f;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        float scaleToGuiX = scaledWidth / Math.max(1.0f, canvasW);
        float scaleToGuiY = scaledHeight / Math.max(1.0f, canvasH);

        float wmCanvasX = wm.getLastWidth() > 1.0f ? wm.getLastRenderedX() : wm.getHudX();
        float wmCanvasW = wm.getLastWidth() > 1.0f ? wm.getLastWidth() : 140.0f * wm.getHudScale();
        float wmCanvasH = wm.getLastHeight() > 1.0f ? wm.getLastHeight() : 28.0f * wm.getHudScale();
        float wmCanvasTop = Math.min(wm.getHudY(), wm.getLastWidth() > 1.0f ? wm.getLastRenderedY() : wm.getHudY());

        float wmLeft = wmCanvasX * scaleToGuiX;
        float wmRight = wmLeft + wmCanvasW * scaleToGuiX;
        float wmTop = wmCanvasTop * scaleToGuiY;
        float wmBottom = wmTop + wmCanvasH * scaleToGuiY;

        if (wmTop >= 40.0f || wmRight <= barMinX || wmLeft >= barMaxX) {
            return 0.0f;
        }

        // Ванильный заголовок боссбара начинается на y = 3 (12 - 9), поэтому сдвигаем от 3.0f, чтобы текст не заезжал на Watermark
        return Math.max(0.0f, (wmBottom + 6.0f) - 3.0f);
    }
}
