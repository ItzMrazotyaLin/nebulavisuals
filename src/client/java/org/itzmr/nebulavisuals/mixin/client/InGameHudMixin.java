package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.utilities.NoRenderModule;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import me.itzmr.nebulavisuals.render.engine2d.PlayerListHudAccess;
import me.itzmr.nebulavisuals.render.engine2d.screen.ClickGuiScreen;
import me.itzmr.nebulavisuals.render.hud.ArmorRenderer;
import me.itzmr.nebulavisuals.render.hud.CooldownsRenderer;
import me.itzmr.nebulavisuals.render.hud.PotionsRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Скрывает прицел при открытом ClickGuiScreen, отключает мешающие оверлеи при активном NoRender,
 * поддерживает плавный {@code Tab}, плавный хотбар ({@code Smooth Hotbar})
 * и отрисовку ванильных иконок для HUD-модулей.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private PlayerListHud playerListHud;

    @Unique private float nebulavisuals$smoothSelectedSlot = -1.0f;
    @Unique private long nebulavisuals$hotbarLastNs = 0L;
    @Unique private boolean nebulavisuals$pushedHotbarSlide = false;

    @Inject(method = "render", at = @At("TAIL"))
    private void nebulavisuals$renderHudIconsAndSprites(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (this.client == null) {
            return;
        }
        float tickProgress = tickCounter != null ? tickCounter.getTickProgress(false) : 0.0f;
        CooldownsRenderer.prepareAndRenderIcons(context, this.client, tickProgress);
        ArmorRenderer.prepareAndRenderIcons(context, this.client);
        PotionsRenderer.prepareAndRenderIcons(context, this.client);
    }

    @Inject(
            method = "renderHotbar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V",
                    ordinal = 1
            )
    )
    private void nebulavisuals$pushHotbarSelectionSlide(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        this.nebulavisuals$pushedHotbarSlide = false;
        PlayerEntity player = this.client != null
                ? (this.client.getCameraEntity() instanceof PlayerEntity p ? p : this.client.player)
                : null;
        if (player == null || !AnimationsModule.isSmoothHotbarActive()) {
            this.nebulavisuals$smoothSelectedSlot = player != null ? player.getInventory().getSelectedSlot() : -1.0f;
            this.nebulavisuals$hotbarLastNs = 0L;
            return;
        }

        int targetSlot = player.getInventory().getSelectedSlot();
        long now = System.nanoTime();
        float dt = this.nebulavisuals$hotbarLastNs == 0L
                ? 0.0f
                : MathHelper.clamp((now - this.nebulavisuals$hotbarLastNs) / 1_000_000_000.0f, 0.0f, 0.05f);
        this.nebulavisuals$hotbarLastNs = now;

        if (this.nebulavisuals$smoothSelectedSlot < 0.0f) {
            this.nebulavisuals$smoothSelectedSlot = targetSlot;
            return;
        }

        AnimationsModule animations = ModuleManager.getAnimationsModule();
        float speed = animations != null ? animations.getSpeedMultiplier() : 1.2f;
        float factor = dt <= 0.0f ? 0.0f : 1.0f - (float) Math.exp(-36.0f * dt * speed);
        this.nebulavisuals$smoothSelectedSlot += (targetSlot - this.nebulavisuals$smoothSelectedSlot) * factor;
        if (Math.abs(targetSlot - this.nebulavisuals$smoothSelectedSlot) < 0.002f) {
            this.nebulavisuals$smoothSelectedSlot = targetSlot;
        }

        float offsetX = (this.nebulavisuals$smoothSelectedSlot - targetSlot) * 20.0f;
        if (Math.abs(offsetX) > 0.02f) {
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(offsetX, 0.0f);
            this.nebulavisuals$pushedHotbarSlide = true;
        }
    }

    @Inject(
            method = "renderHotbar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            )
    )
    private void nebulavisuals$popHotbarSelectionSlide(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (this.nebulavisuals$pushedHotbarSlide) {
            context.getMatrices().popMatrix();
            this.nebulavisuals$pushedHotbarSlide = false;
        }
    }

    @Inject(method = "renderPlayerList", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$renderAnimatedPlayerList(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (this.client == null || this.client.world == null || this.playerListHud == null) {
            return;
        }
        if (!(this.playerListHud instanceof PlayerListHudAccess access)) {
            return;
        }

        Scoreboard scoreboard = this.client.world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.LIST);
        boolean shouldShow = this.client.options.playerListKey.isPressed()
                && (!this.client.isInSingleplayer()
                || (this.client.player != null && this.client.player.networkHandler.getListedPlayerListEntries().size() > 1)
                || objective != null);

        float progress = access.nebulavisuals$updateVisibility(shouldShow);
        if (progress > 0.01f) {
            this.playerListHud.setVisible(shouldShow);
            context.createNewRootLayer();
            this.playerListHud.render(context, context.getScaledWindowWidth(), scoreboard, objective);
        } else {
            this.playerListHud.setVisible(false);
        }
        ci.cancel();
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$hideVanillaCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.currentScreen instanceof ClickGuiScreen) {
            ci.cancel();
            return;
        }
        me.itzmr.nebulavisuals.module.hud.CrosshairModule crosshair = ModuleManager.getCrosshairModule();
        if (crosshair != null && crosshair.isEnabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$disablePumpkinOverlay(DrawContext context, Identifier texture, float opacity, CallbackInfo ci) {
        NoRenderModule noRender = ModuleManager.getNoRenderModule();
        if (noRender != null && noRender.shouldCancelOverlays()
                && texture != null && texture.getPath().contains("pumpkinblur")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderVignetteOverlay", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$disableVignette(DrawContext context, Entity entity, CallbackInfo ci) {
        NoRenderModule noRender = ModuleManager.getNoRenderModule();
        if (noRender != null && noRender.shouldCancelOverlays()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$disablePortalDistortion(DrawContext context, float nauseaStrength, CallbackInfo ci) {
        NoRenderModule noRender = ModuleManager.getNoRenderModule();
        if (noRender != null && noRender.shouldCancelOverlays()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderNauseaOverlay", at = @At("HEAD"), cancellable = true)
    private void nebulavisuals$disableNauseaDistortion(DrawContext context, float nauseaStrength, CallbackInfo ci) {
        NoRenderModule noRender = ModuleManager.getNoRenderModule();
        if (noRender != null && noRender.shouldCancelOverlays()) {
            ci.cancel();
        }
    }
}
