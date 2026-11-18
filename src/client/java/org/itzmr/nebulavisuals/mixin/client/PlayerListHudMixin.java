package org.itzmr.nebulavisuals.mixin.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.WatermarkModule;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import me.itzmr.nebulavisuals.render.engine2d.PlayerListHudAccess;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Плавная анимация открытия/закрытия списка игроков ({@code Tab}) по кривой {@code EaseOutCubic}
 * и автоматическое смещение ниже {@code Watermark}, чтобы элементы не перекрывались.
 */
@Mixin(PlayerListHud.class)
public class PlayerListHudMixin implements PlayerListHudAccess {
    @Unique private static final float TAB_ANIM_SPEED = 7.5f;
    @Unique private static final float TAB_SLIDE_PX = 8.0f;

    @Shadow @Final private MinecraftClient client;

    @Unique private float nebulavisuals$tabProgress = 0.0f;
    @Unique private float nebulavisuals$tabAlpha = 1.0f;
    @Unique private long nebulavisuals$lastFrameNs = 0L;
    @Unique private boolean nebulavisuals$pushedMatrix = false;

    @Override
    public float nebulavisuals$updateVisibility(boolean shouldShow) {
        long now = System.nanoTime();
        float deltaTime = this.nebulavisuals$lastFrameNs == 0L
                ? 0.0f
                : MathHelper.clamp((now - this.nebulavisuals$lastFrameNs) / 1_000_000_000.0f, 0.0f, 0.05f);
        this.nebulavisuals$lastFrameNs = now;

        AnimationsModule animations = ModuleManager.getAnimationsModule();
        float speed = (animations != null && animations.isEnabled()) ? animations.getSpeedMultiplier() : 1.0f;
        float step = deltaTime * TAB_ANIM_SPEED * speed;
        if (shouldShow) {
            this.nebulavisuals$tabProgress = Math.min(1.0f, this.nebulavisuals$tabProgress + step);
        } else {
            this.nebulavisuals$tabProgress = Math.max(0.0f, this.nebulavisuals$tabProgress - step);
        }
        return this.nebulavisuals$tabProgress;
    }

    @Override
    public float nebulavisuals$getTabAlpha() {
        return this.nebulavisuals$tabAlpha;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void nebulavisuals$pushTabTransform(
            DrawContext context, int scaledWindowWidth, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        float eased = AnimationsModule.easeOutCubic(this.nebulavisuals$tabProgress);
        this.nebulavisuals$tabAlpha = MathHelper.clamp(eased, 0.02f, 1.0f);

        float slideY = -TAB_SLIDE_PX * (1.0f - eased);
        float watermarkOffsetY = this.nebulavisuals$computeWatermarkAvoidOffset();
        float totalOffsetY = watermarkOffsetY + slideY;

        if (Math.abs(totalOffsetY) > 0.01f) {
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(0.0f, totalOffsetY);
            this.nebulavisuals$pushedMatrix = true;
        } else {
            this.nebulavisuals$pushedMatrix = false;
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void nebulavisuals$popTabTransform(
            DrawContext context, int scaledWindowWidth, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        if (this.nebulavisuals$pushedMatrix) {
            context.getMatrices().popMatrix();
            this.nebulavisuals$pushedMatrix = false;
        }
    }

    @Unique
    private float nebulavisuals$computeWatermarkAvoidOffset() {
        WatermarkModule watermark = ModuleManager.getWatermarkModule();
        if (watermark == null || !watermark.isEnabled() || this.client == null || this.client.getWindow() == null) {
            return 0.0f;
        }
        int fbW = Math.max(1, this.client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, this.client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float guiScale = Math.max(1.0f, (float) this.client.getWindow().getScaleFactor());

        float wmTopScaled = watermark.getLastRenderedY() * uiScale / guiScale;
        float wmBottomScaled = (watermark.getLastRenderedY() + watermark.getLastHeight()) * uiScale / guiScale;
        if (watermark.getHudY() < 45.0f || wmTopScaled < 45.0f) {
            return Math.max(0.0f, (wmBottomScaled + 6.0f) - 10.0f);
        }
        return 0.0f;
    }

    @ModifyArgs(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"
            )
    )
    private void nebulavisuals$fadeTabFills(Args args) {
        if (this.nebulavisuals$tabAlpha < 0.999f) {
            args.set(4, nebulavisuals$scaleAlpha((Integer) args.get(4), this.nebulavisuals$tabAlpha));
        }
    }

    @ModifyArgs(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"
            )
    )
    private void nebulavisuals$fadeTabText(Args args) {
        if (this.nebulavisuals$tabAlpha < 0.999f) {
            args.set(4, nebulavisuals$scaleAlpha((Integer) args.get(4), this.nebulavisuals$tabAlpha));
        }
    }

    @ModifyArgs(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)V"
            )
    )
    private void nebulavisuals$fadeTabOrderedText(Args args) {
        if (this.nebulavisuals$tabAlpha < 0.999f) {
            args.set(4, nebulavisuals$scaleAlpha((Integer) args.get(4), this.nebulavisuals$tabAlpha));
        }
    }

    @ModifyArgs(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/PlayerSkinDrawer;draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;IIIZZI)V"
            )
    )
    private void nebulavisuals$fadeTabHeads(Args args) {
        if (this.nebulavisuals$tabAlpha < 0.999f) {
            args.set(7, nebulavisuals$scaleAlpha((Integer) args.get(7), this.nebulavisuals$tabAlpha));
        }
    }

    @Redirect(
            method = "renderLatencyIcon",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V"
            )
    )
    private void nebulavisuals$fadeTabPingIcon(
            DrawContext context, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height) {
        if (this.nebulavisuals$tabAlpha < 0.999f) {
            context.drawGuiTexture(pipeline, sprite, x, y, width, height, ColorHelper.getWhite(this.nebulavisuals$tabAlpha));
        } else {
            context.drawGuiTexture(pipeline, sprite, x, y, width, height);
        }
    }

    @Unique
    private static int nebulavisuals$scaleAlpha(int argb, float factor) {
        int a = MathHelper.clamp(Math.round(((argb >>> 24) & 0xFF) * factor), 0, 255);
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
