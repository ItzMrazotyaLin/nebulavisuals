package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.ArmorHudModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Рендерер HUD-модуля {@link ArmorHudModule}:
 * отображает надетую броню (шлем -> ботинки) и её прочность в горизонтальной или вертикальной стеклянной карточке.
 */
public final class ArmorRenderer {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final float MIN_BASE_WIDTH = 90.0f;
    private static final float PAD_X = 8.0f;
    private static final float PAD_Y = 7.0f;
    private static final float HEADER_H = 16.0f;
    private static final float ICON_SIZE = 15.0f;
    private static final float CARD_RADIUS = 11.0f;
    private static final float FONT_HEADER_SIZE = 10.5f;
    private static final float FONT_SIZE = 9.5f;

    private record ArmorPiece(ItemStack stack, int remaining, int maxDamage, float fraction) {}

    private static final List<ArmorPiece> PIECES = new ArrayList<>(4);
    private static long lastTimeNs = 0L;
    private static float fadeAlpha = 0.0f;
    private static float smoothX = -1.0f;
    private static float smoothY = -1.0f;

    private static boolean framePrepared = false;
    private static float frameCanvasW = 0.0f;
    private static float frameCanvasH = 0.0f;
    private static float frameUiScale = 1.0f;
    private static float frameScaledW = 0.0f;
    private static float frameScaledH = 0.0f;
    private static float frameHudScale = 1.0f;
    private static boolean frameVertical = false;
    private static String frameMode = "Bar + Percent";
    private static float frameCellSize = 36.0f;
    private static float frameSlotGap = 6.0f;
    private static float frameRowH = 20.0f;

    private ArmorRenderer() {}

    public static void prepareAndRenderIcons(DrawContext context, MinecraftClient client) {
        framePrepared = false;
        if (HudGlassHelper.shouldSkipHud(client)) {
            return;
        }
        ArmorHudModule module = ModuleManager.getArmorHudModule();
        if (module == null) {
            return;
        }

        float dt = computeDelta();
        collectArmor(client, module);
        boolean shouldShow = module.isEnabled() && !PIECES.isEmpty();
        fadeAlpha = AnimationEngine.expLerp(fadeAlpha, shouldShow ? 1.0f : 0.0f, 15.0f, dt);
        if (!shouldShow && fadeAlpha <= 0.01f) {
            return;
        }
        if (PIECES.isEmpty() || !FontRenderer.hasDefaultFont()) {
            return;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        boolean vertical = "Vertical".equalsIgnoreCase(module.orientation.getValue());
        String mode = module.durabilityMode.getValue();
        float hudScale = module.getHudScale();

        float baseW;
        float baseH;
        float cellW = "Bar".equals(mode) ? 26.0f : 34.0f;
        float slotGap = 6.0f;
        float rowH = 19.0f;

        if (vertical) {
            float maxTextW = 0.0f;
            for (ArmorPiece piece : PIECES) {
                String label = formatLabel(piece, mode);
                if (!label.isEmpty()) {
                    maxTextW = Math.max(maxTextW, FontRenderer.getWidth(label, FONT_SIZE));
                }
            }
            float barPartW = mode.contains("Bar") ? 36.0f : 0.0f;
            float textGap = (mode.contains("Bar") && maxTextW > 0.0f) ? 6.0f : 0.0f;
            baseW = Math.max(MIN_BASE_WIDTH, PAD_X * 2.0f + ICON_SIZE + 6.0f + barPartW + textGap + maxTextW);
            baseH = PAD_Y * 2.0f + HEADER_H + PIECES.size() * rowH + Math.max(0, PIECES.size() - 1) * 4.0f;
        } else {
            baseW = Math.max(MIN_BASE_WIDTH, PAD_X * 2.0f + PIECES.size() * cellW + Math.max(0, PIECES.size() - 1) * slotGap);
            float extraH = switch (mode) {
                case "Bar" -> 6.0f;
                case "Bar + Percent" -> 16.0f;
                default -> 12.0f;
            };
            baseH = PAD_Y * 2.0f + HEADER_H + ICON_SIZE + extraH;
        }

        float scaledW = baseW * hudScale;
        float scaledH = baseH * hudScale;
        float maxX = Math.max(HudGlassHelper.MARGIN_EDGE, canvasW - scaledW - HudGlassHelper.MARGIN_EDGE);
        float maxY = Math.max(HudGlassHelper.MARGIN_EDGE, canvasH - scaledH - HudGlassHelper.MARGIN_EDGE);
        float targetX = Math.clamp(module.getHudX(), HudGlassHelper.MARGIN_EDGE, maxX);
        float targetY = Math.clamp(module.getHudY(), HudGlassHelper.MARGIN_EDGE, maxY);

        boolean interactive = HudGlassHelper.isInteractive(module);
        smoothX = HudGlassHelper.resolveAxisPosition(targetX, smoothX, interactive, dt);
        smoothY = HudGlassHelper.resolveAxisPosition(targetY, smoothY, interactive, dt);

        module.updateRenderedBounds(smoothX, smoothY, scaledW, scaledH);
        if (HudGlassHelper.isOverlappingClickGui(client, smoothX, smoothY, scaledW, scaledH)) {
            return;
        }

        framePrepared = true;
        frameCanvasW = canvasW;
        frameCanvasH = canvasH;
        frameUiScale = uiScale;
        frameScaledW = scaledW;
        frameScaledH = scaledH;
        frameHudScale = hudScale;
        frameVertical = vertical;
        frameMode = mode;
        frameCellSize = cellW;
        frameSlotGap = slotGap;
        frameRowH = rowH;

        if (context != null && fadeAlpha > 0.10f) {
            float guiScale = Math.max(1.0f, (float) client.getWindow().getScaleFactor());
            float iconSize = ICON_SIZE * hudScale;
            float contentTopY = smoothY + (PAD_Y + HEADER_H) * hudScale;
            for (int i = 0; i < PIECES.size(); i++) {
                float ix;
                float iy;
                if (vertical) {
                    ix = smoothX + PAD_X * hudScale;
                    iy = contentTopY + i * (rowH + 4.0f) * hudScale + (rowH * hudScale - iconSize) * 0.5f;
                } else {
                    float cellX = smoothX + (PAD_X + i * (cellW + slotGap)) * hudScale;
                    ix = cellX + (cellW * hudScale - iconSize) * 0.5f;
                    iy = contentTopY;
                }
                HudGlassHelper.drawScaledItem(context, PIECES.get(i).stack, ix, iy, iconSize, uiScale, guiScale);
            }
        }
    }

    public static void renderGlassBackgrounds(MinecraftClient client) {
        if (!framePrepared || PIECES.isEmpty()) {
            return;
        }
        if (!NebulaRenderEngine.isInitialized()) {
            NebulaRenderEngine.init();
        }
        if (!FontRenderer.hasDefaultFont()) {
            return;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        KawaseBlur blur = NebulaRenderEngine.getBlur();
        int blurredTex = HudGlassHelper.prepareBlurTexture(client, blur, fbW, fbH);

        int accent = ClientSettings.getAccentColor();
        int borderColor = ColorUtil.withAlpha(accent, 0.45f * fadeAlpha);
        int trackColor = ColorUtil.argb((int) (60.0f * fadeAlpha), 255, 255, 255);
        float textFont = FONT_SIZE * frameHudScale;

        batch.begin(frameCanvasW, frameCanvasH, frameUiScale);
        HudGlassHelper.drawGlassCard(
                batch, blur, blurredTex,
                smoothX, smoothY, frameScaledW, frameScaledH,
                CARD_RADIUS * frameHudScale, frameHudScale, fadeAlpha, borderColor,
                frameCanvasW, frameCanvasH
        );

        float headerFont = FONT_HEADER_SIZE * frameHudScale;
        int headerTitleColor = ColorUtil.argb((int) (245.0f * fadeAlpha), 243, 244, 246);
        int dividerColor = ColorUtil.argb((int) (46.0f * fadeAlpha), 255, 255, 255);
        HudGlassHelper.drawHudHeader(
                batch,
                "Armor",
                headerTitleColor,
                null,
                0,
                smoothX,
                smoothY + PAD_Y * frameHudScale,
                frameScaledW,
                PAD_X,
                HEADER_H,
                headerFont,
                frameHudScale,
                dividerColor
        );

        float contentTopY = smoothY + (PAD_Y + HEADER_H) * frameHudScale;
        for (int i = 0; i < PIECES.size(); i++) {
            ArmorPiece piece = PIECES.get(i);
            int statusColor = ColorUtil.withAlpha(resolveDurabilityColor(piece.fraction, accent), fadeAlpha);
            String label = formatLabel(piece, frameMode);

            if (frameVertical) {
                float rowY = contentTopY + i * (frameRowH + 4.0f) * frameHudScale;
                float contentX = smoothX + (PAD_X + ICON_SIZE + 6.0f) * frameHudScale;
                float rowCenterY = rowY + frameRowH * frameHudScale * 0.5f;

                if (frameMode.contains("Bar")) {
                    float barW = 36.0f * frameHudScale;
                    float barH = 3.0f * frameHudScale;
                    float barY = rowCenterY - barH * 0.5f;
                    batch.drawRoundedRect(contentX, barY, barW, barH, barH * 0.5f, trackColor);
                    batch.drawRoundedRect(contentX, barY, Math.max(barH, barW * piece.fraction), barH, barH * 0.5f, statusColor);
                    contentX += barW + 6.0f * frameHudScale;
                }
                if (!label.isEmpty()) {
                    float textY = rowCenterY - FontRenderer.getHeight(textFont) * 0.5f;
                    FontRenderer.drawText(batch, label, contentX, textY, textFont, statusColor);
                }
            } else {
                float cellX = smoothX + (PAD_X + i * (frameCellSize + frameSlotGap)) * frameHudScale;
                float cellW = frameCellSize * frameHudScale;
                float curY = contentTopY + (ICON_SIZE + 3.0f) * frameHudScale;

                if (frameMode.contains("Bar")) {
                    float barW = cellW - 4.0f * frameHudScale;
                    float barX = cellX + (cellW - barW) * 0.5f;
                    float barH = 2.5f * frameHudScale;
                    batch.drawRoundedRect(barX, curY, barW, barH, barH * 0.5f, trackColor);
                    batch.drawRoundedRect(barX, curY, Math.max(barH, barW * piece.fraction), barH, barH * 0.5f, statusColor);
                    curY += barH + 3.0f * frameHudScale;
                }
                if (!label.isEmpty()) {
                    FontRenderer.drawCenteredText(batch, label, cellX + cellW * 0.5f, curY, textFont, statusColor);
                }
            }
        }

        batch.end();
    }

    private static String formatLabel(ArmorPiece piece, String mode) {
        if ("Bar".equals(mode)) {
            return "";
        }
        if ("Number".equals(mode)) {
            return String.valueOf(piece.remaining);
        }
        int pct = Math.round(piece.fraction * 100.0f);
        return pct + "%";
    }

    private static int resolveDurabilityColor(float frac, int accent) {
        int healthy = ColorUtil.lerp(0xFF4ADE80, accent, 0.35f);
        if (frac >= 0.60f) {
            return healthy;
        }
        if (frac >= 0.25f) {
            float t = (frac - 0.25f) / 0.35f;
            return ColorUtil.lerp(0xFFFACC15, healthy, t);
        }
        float t = Math.clamp(frac / 0.25f, 0.0f, 1.0f);
        return ColorUtil.lerp(0xFFEF4444, 0xFFFACC15, t);
    }

    private static void collectArmor(MinecraftClient client, ArmorHudModule module) {
        PIECES.clear();
        if (!module.isEnabled() || client.player == null) {
            return;
        }
        ClientPlayerEntity player = client.player;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getEquippedStack(slot);
            if (stack != null && !stack.isEmpty() && stack.isDamageable() && stack.getMaxDamage() > 0) {
                int max = stack.getMaxDamage();
                int rem = Math.max(0, max - stack.getDamage());
                PIECES.add(new ArmorPiece(stack, rem, max, Math.clamp((float) rem / max, 0.0f, 1.0f)));
            }
        }

        if (PIECES.isEmpty() && client.currentScreen instanceof ChatScreen) {
            PIECES.add(makePreview(Items.NETHERITE_HELMET, 0.92f));
            PIECES.add(makePreview(Items.NETHERITE_CHESTPLATE, 0.74f));
            PIECES.add(makePreview(Items.NETHERITE_LEGGINGS, 0.41f));
            PIECES.add(makePreview(Items.NETHERITE_BOOTS, 0.18f));
        }
    }

    private static ArmorPiece makePreview(net.minecraft.item.Item item, float fraction) {
        ItemStack stack = new ItemStack(item);
        int max = Math.max(1, stack.getMaxDamage());
        int rem = Math.max(1, Math.round(max * fraction));
        return new ArmorPiece(stack, rem, max, fraction);
    }

    private static float computeDelta() {
        long now = System.nanoTime();
        double dt = (now - lastTimeNs) / 1_000_000_000.0;
        if (lastTimeNs == 0L || dt <= 0.0 || dt > 0.1) {
            dt = 0.016;
        }
        lastTimeNs = now;
        float raw = (float) Math.clamp(dt, 0.0001, 0.05);
        return Math.clamp(raw * ClientSettings.getAnimationSpeed(), 0.0001f, 0.1f);
    }
}
