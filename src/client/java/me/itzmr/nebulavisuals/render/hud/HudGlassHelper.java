package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.hud.HudModule;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.ClickGuiScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;

/**
 * Общие хелперы рендера стеклянных HUD-карточек (KawaseBlur + обводка)
 * и вывода ванильных иконок предметов/эффектов поверх них через {@link DrawContext}.
 */
public final class HudGlassHelper {
    public static final float MARGIN_EDGE = 8.0f;

    private HudGlassHelper() {}

    public static boolean shouldSkipHud(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || client.getWindow() == null) {
            return true;
        }
        if (client.options.hudHidden) {
            return true;
        }
        return client.currentScreen != null
                && !(client.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)
                && !(client.currentScreen instanceof ClickGuiScreen);
    }

    public static boolean isOverlappingClickGui(MinecraftClient client, float x, float y, float w, float h) {
        return client.currentScreen instanceof ClickGuiScreen clickGui
                && clickGui.isRectOverlappingWindow(x, y, w, h);
    }

    public static int prepareBlurTexture(MinecraftClient client, KawaseBlur blur, int fbW, int fbH) {
        float blurStrength = ClientSettings.getBlurStrength();
        float blurFade = blurStrength <= 0.01f ? 0.0f : Math.clamp(blurStrength / 0.25f, 0.0f, 1.0f);
        if (blurFade > 0.001f && client.getFramebuffer() != null
                && client.getFramebuffer().getColorAttachment() instanceof GlTexture glTexture) {
            int fbTex = glTexture.getGlId();
            if (fbTex > 0) {
                float offset = Math.clamp(1.6f * blurStrength, 0.25f, 4.0f);
                return blur.renderBlur(fbTex, fbW, fbH, 3, offset);
            }
        }
        return 0;
    }

    public static void drawGlassCard(RenderBatch2D batch, KawaseBlur blur, int blurredTex,
                                     float x, float y, float w, float h, float radius,
                                     float hudScale, float fadeAlpha, int borderColor,
                                     float canvasW, float canvasH) {
        float uiOpacity = ClientSettings.getUiOpacity();
        float blurStrength = ClientSettings.getBlurStrength();
        float blurFade = blurStrength <= 0.01f ? 0.0f : Math.clamp(blurStrength / 0.25f, 0.0f, 1.0f);

        batch.drawShadow(x, y, w, h, radius, 12.0f * hudScale,
                ColorUtil.argb((int) (88.0f * fadeAlpha), 0, 0, 0));

        if (blurredTex != 0 && blurFade > 0.001f) {
            blur.drawBlurredRect(
                    batch, blurredTex,
                    x, y, w, h, radius, canvasW, canvasH,
                    ColorUtil.argb((int) (255.0f * fadeAlpha * blurFade), 255, 255, 255)
            );
        }

        int bgColor = ColorUtil.argb((int) (230.0f * uiOpacity * fadeAlpha), 13, 15, 18);
        batch.drawRoundedRectWithOutline(x, y, w, h, radius, bgColor, 1.0f * hudScale, borderColor);
    }

    public static void drawHudHeader(RenderBatch2D batch, String title, int titleColor,
                                     String rightBadge, int badgeColor,
                                     float cardX, float headerTopY, float cardW, float padX,
                                     float headerH, float fontSize, float hudScale, int dividerColor) {
        float lx = cardX + padX * hudScale;
        float scaledHeaderH = headerH * hudScale;
        float textH = FontRenderer.getHeight(fontSize);
        float textY = headerTopY + Math.max(0.0f, (scaledHeaderH - 4.0f * hudScale - textH) * 0.5f);
        FontRenderer.drawText(batch, title, lx, textY, fontSize, titleColor);

        if (rightBadge != null && !rightBadge.isEmpty()) {
            float badgeW = FontRenderer.getWidth(rightBadge, fontSize);
            FontRenderer.drawText(batch, rightBadge, cardX + cardW - padX * hudScale - badgeW, textY, fontSize, badgeColor);
        }

        float lineY = headerTopY + scaledHeaderH - 3.0f * hudScale;
        float lineW = Math.max(10.0f, cardW - padX * 2.0f * hudScale);
        batch.drawRect(lx, lineY, lineW, 1.0f * hudScale, dividerColor);
    }

    public static void drawScaledItem(DrawContext context, ItemStack stack,
                                      float canvasX, float canvasY, float canvasSize,
                                      float uiScale, float guiScale) {
        if (context == null || stack == null || stack.isEmpty() || canvasSize <= 0.5f) {
            return;
        }
        float toGui = uiScale / Math.max(1.0f, guiScale);
        float guiX = canvasX * toGui;
        float guiY = canvasY * toGui;
        float itemScale = (canvasSize * toGui) / 16.0f;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(guiX, guiY);
        context.getMatrices().scale(itemScale, itemScale);
        context.drawItem(stack, 0, 0);
        context.getMatrices().popMatrix();
    }

    public static void drawScaledTexture(DrawContext context, Identifier texture,
                                         float canvasX, float canvasY, float canvasSize,
                                         float uiScale, float guiScale, float alpha) {
        if (context == null || texture == null || canvasSize <= 0.5f || alpha <= 0.02f) {
            return;
        }
        float toGui = uiScale / Math.max(1.0f, guiScale);
        float guiX = canvasX * toGui;
        float guiY = canvasY * toGui;
        float spriteScale = (canvasSize * toGui) / 18.0f;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(guiX, guiY);
        context.getMatrices().scale(spriteScale, spriteScale);
        context.drawGuiTexture(
                RenderPipelines.GUI_TEXTURED,
                texture,
                0, 0, 18, 18,
                ColorHelper.getWhite(Math.clamp(alpha, 0.0f, 1.0f))
        );
        context.getMatrices().popMatrix();
    }

    public static float resolveAxisPosition(float target, float smooth, boolean dragging, float dt) {
        if (dragging || smooth < 0.0f) {
            return target;
        }
        return me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine.expLerp(smooth, target, 18.0f, dt);
    }

    public static boolean isInteractive(HudModule module) {
        return HudEditor.isDragging(module) || HudEditor.isResizing(module);
    }
}
