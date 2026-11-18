package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.config.LocalizationManager;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.PotionsHudModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Рендерер HUD-модуля {@link PotionsHudModule}:
 * активные эффекты зелий с ванильными спрайтами, уровнем (II, III), временем (MM:SS / **:**)
 * и опциональным разделением на хорошие/нейтральные и плохие эффекты.
 */
public final class PotionsRenderer {
    private static final float BASE_WIDTH = 166.0f;
    private static final float PAD_X = 8.0f;
    private static final float PAD_Y = 7.0f;
    private static final float ROW_H = 18.0f;
    private static final float ROW_GAP = 4.0f;
    private static final float HEADER_H = 16.0f;
    private static final float SECTION_GAP = 7.0f;
    private static final float ICON_SIZE = 14.0f;
    private static final float FONT_NAME_SIZE = 10.5f;
    private static final float FONT_TIME_SIZE = 10.0f;
    private static final float FONT_HEADER_SIZE = 10.0f;
    private static final float CARD_RADIUS = 11.0f;

    private record PotionEntry(Identifier spriteId, String title, String timeText, boolean harmful) {}

    private static final List<PotionEntry> ALL_ENTRIES = new ArrayList<>();
    private static final List<PotionEntry> GOOD_ENTRIES = new ArrayList<>();
    private static final List<PotionEntry> BAD_ENTRIES = new ArrayList<>();

    private static long lastTimeNs = 0L;
    private static float fadeAlpha = 0.0f;
    private static float smoothX = -1.0f;
    private static float smoothY = -1.0f;

    private static boolean framePrepared = false;
    private static boolean frameSplit = true;
    private static float frameCanvasW = 0.0f;
    private static float frameCanvasH = 0.0f;
    private static float frameUiScale = 1.0f;
    private static float frameScaledW = 0.0f;
    private static float frameScaledH = 0.0f;
    private static float frameHudScale = 1.0f;
    private static float frameGoodBoxH = 0.0f;
    private static float frameBadBoxY = 0.0f;
    private static float frameBadBoxH = 0.0f;

    private PotionsRenderer() {}

    public static void prepareAndRenderIcons(DrawContext context, MinecraftClient client) {
        framePrepared = false;
        if (HudGlassHelper.shouldSkipHud(client)) {
            return;
        }
        PotionsHudModule module = ModuleManager.getPotionsHudModule();
        if (module == null) {
            return;
        }

        float dt = computeDelta();
        collectPotions(client, module);
        boolean shouldShow = module.isEnabled() && !ALL_ENTRIES.isEmpty();
        fadeAlpha = AnimationEngine.expLerp(fadeAlpha, shouldShow ? 1.0f : 0.0f, 15.0f, dt);
        if (!shouldShow && fadeAlpha <= 0.01f) {
            return;
        }
        if (ALL_ENTRIES.isEmpty() || !FontRenderer.hasDefaultFont()) {
            return;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        boolean split = module.splitGoodBad.getValue();
        float hudScale = module.getHudScale();

        float maxContentW = BASE_WIDTH;
        for (PotionEntry entry : ALL_ENTRIES) {
            float needed = PAD_X * 2.0f + ICON_SIZE + 6.0f
                    + FontRenderer.getWidth(entry.title, FONT_NAME_SIZE)
                    + 12.0f
                    + FontRenderer.getWidth(entry.timeText, FONT_TIME_SIZE);
            maxContentW = Math.max(maxContentW, needed);
        }

        float goodBoxH = 0.0f;
        float badBoxH = 0.0f;
        float totalBaseH;
        if (split) {
            if (!GOOD_ENTRIES.isEmpty()) {
                goodBoxH = PAD_Y * 2.0f + HEADER_H + GOOD_ENTRIES.size() * ROW_H + Math.max(0, GOOD_ENTRIES.size() - 1) * ROW_GAP;
            }
            if (!BAD_ENTRIES.isEmpty()) {
                badBoxH = PAD_Y * 2.0f + HEADER_H + BAD_ENTRIES.size() * ROW_H + Math.max(0, BAD_ENTRIES.size() - 1) * ROW_GAP;
            }
            totalBaseH = goodBoxH + (goodBoxH > 0.0f && badBoxH > 0.0f ? SECTION_GAP : 0.0f) + badBoxH;
        } else {
            totalBaseH = PAD_Y * 2.0f + HEADER_H + ALL_ENTRIES.size() * ROW_H + Math.max(0, ALL_ENTRIES.size() - 1) * ROW_GAP;
        }

        float scaledW = maxContentW * hudScale;
        float scaledH = totalBaseH * hudScale;
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
        frameSplit = split;
        frameCanvasW = canvasW;
        frameCanvasH = canvasH;
        frameUiScale = uiScale;
        frameScaledW = scaledW;
        frameScaledH = scaledH;
        frameHudScale = hudScale;
        frameGoodBoxH = goodBoxH * hudScale;
        frameBadBoxH = badBoxH * hudScale;
        frameBadBoxY = smoothY + frameGoodBoxH + (goodBoxH > 0.0f ? SECTION_GAP * hudScale : 0.0f);

        if (context != null && fadeAlpha > 0.03f) {
            float guiScale = Math.max(1.0f, (float) client.getWindow().getScaleFactor());
            float iconSize = ICON_SIZE * hudScale;
            if (split) {
                if (!GOOD_ENTRIES.isEmpty()) {
                    drawSpriteList(context, GOOD_ENTRIES, smoothY + (PAD_Y + HEADER_H) * hudScale, iconSize, uiScale, guiScale);
                }
                if (!BAD_ENTRIES.isEmpty()) {
                    drawSpriteList(context, BAD_ENTRIES, frameBadBoxY + (PAD_Y + HEADER_H) * hudScale, iconSize, uiScale, guiScale);
                }
            } else {
                drawSpriteList(context, ALL_ENTRIES, smoothY + (PAD_Y + HEADER_H) * hudScale, iconSize, uiScale, guiScale);
            }
        }
    }

    private static void drawSpriteList(DrawContext context, List<PotionEntry> list, float startY,
                                       float iconSize, float uiScale, float guiScale) {
        float iconX = smoothX + PAD_X * frameHudScale;
        for (int i = 0; i < list.size(); i++) {
            float rowY = startY + i * (ROW_H + ROW_GAP) * frameHudScale;
            float iconY = rowY + (ROW_H * frameHudScale - iconSize) * 0.5f;
            HudGlassHelper.drawScaledTexture(context, list.get(i).spriteId, iconX, iconY, iconSize, uiScale, guiScale, fadeAlpha);
        }
    }

    public static void renderGlassBackgrounds(MinecraftClient client) {
        if (!framePrepared || ALL_ENTRIES.isEmpty()) {
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
        int goodBorder = ColorUtil.withAlpha(accent, 0.45f * fadeAlpha);
        int badBorder = ColorUtil.withAlpha(0xFFEF4444, 0.55f * fadeAlpha);
        float radius = CARD_RADIUS * frameHudScale;

        batch.begin(frameCanvasW, frameCanvasH, frameUiScale);
        if (frameSplit) {
            if (!GOOD_ENTRIES.isEmpty()) {
                HudGlassHelper.drawGlassCard(
                        batch, blur, blurredTex,
                        smoothX, smoothY, frameScaledW, frameGoodBoxH,
                        radius, frameHudScale, fadeAlpha, goodBorder,
                        frameCanvasW, frameCanvasH
                );
                int goodHeaderColor = ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFFFFFFFF, 0.20f), fadeAlpha);
                HudGlassHelper.drawHudHeader(
                        batch,
                        LocalizationManager.tr("potions.beneficial"),
                        goodHeaderColor,
                        String.valueOf(GOOD_ENTRIES.size()),
                        goodHeaderColor,
                        smoothX,
                        smoothY + PAD_Y * frameHudScale,
                        frameScaledW,
                        PAD_X,
                        HEADER_H,
                        FONT_HEADER_SIZE * frameHudScale,
                        frameHudScale,
                        ColorUtil.withAlpha(accent, 0.40f * fadeAlpha)
                );
                renderEntryRows(batch, GOOD_ENTRIES, smoothY + (PAD_Y + HEADER_H) * frameHudScale, accent, false);
            }
            if (!BAD_ENTRIES.isEmpty()) {
                HudGlassHelper.drawGlassCard(
                        batch, blur, blurredTex,
                        smoothX, frameBadBoxY, frameScaledW, frameBadBoxH,
                        radius, frameHudScale, fadeAlpha, badBorder,
                        frameCanvasW, frameCanvasH
                );
                // Мягкая красная подсветка фона секции плохих эффектов
                batch.drawRoundedRect(
                        smoothX, frameBadBoxY, frameScaledW, frameBadBoxH,
                        radius, ColorUtil.argb((int) (26.0f * fadeAlpha), 239, 68, 68)
                );
                int badHeaderColor = ColorUtil.withAlpha(0xFFFF6B6B, fadeAlpha);
                HudGlassHelper.drawHudHeader(
                        batch,
                        LocalizationManager.tr("potions.harmful"),
                        badHeaderColor,
                        String.valueOf(BAD_ENTRIES.size()),
                        badHeaderColor,
                        smoothX,
                        frameBadBoxY + PAD_Y * frameHudScale,
                        frameScaledW,
                        PAD_X,
                        HEADER_H,
                        FONT_HEADER_SIZE * frameHudScale,
                        frameHudScale,
                        ColorUtil.withAlpha(0xFFEF4444, 0.42f * fadeAlpha)
                );
                renderEntryRows(batch, BAD_ENTRIES, frameBadBoxY + (PAD_Y + HEADER_H) * frameHudScale, accent, true);
            }
        } else {
            HudGlassHelper.drawGlassCard(
                    batch, blur, blurredTex,
                    smoothX, smoothY, frameScaledW, frameScaledH,
                    radius, frameHudScale, fadeAlpha, goodBorder,
                    frameCanvasW, frameCanvasH
            );
            int headerTitleColor = ColorUtil.argb((int) (245.0f * fadeAlpha), 243, 244, 246);
            int dividerColor = ColorUtil.argb((int) (46.0f * fadeAlpha), 255, 255, 255);
            HudGlassHelper.drawHudHeader(
                    batch,
                    "Potions",
                    headerTitleColor,
                    String.valueOf(ALL_ENTRIES.size()),
                    ColorUtil.withAlpha(accent, fadeAlpha),
                    smoothX,
                    smoothY + PAD_Y * frameHudScale,
                    frameScaledW,
                    PAD_X,
                    HEADER_H,
                    FONT_HEADER_SIZE * frameHudScale,
                    frameHudScale,
                    dividerColor
            );
            renderEntryRows(batch, ALL_ENTRIES, smoothY + (PAD_Y + HEADER_H) * frameHudScale, accent, false);
        }
        batch.end();
    }

    private static void renderEntryRows(RenderBatch2D batch, List<PotionEntry> list, float startY, int accent, boolean forceHarmful) {
        float nameFont = FONT_NAME_SIZE * frameHudScale;
        float timeFont = FONT_TIME_SIZE * frameHudScale;
        float textOffsetX = (PAD_X + ICON_SIZE + 6.0f) * frameHudScale;
        float rightPad = PAD_X * frameHudScale;
        int normalColor = ColorUtil.argb((int) (242.0f * fadeAlpha), 243, 244, 246);
        int badTextColor = ColorUtil.argb((int) (242.0f * fadeAlpha), 254, 202, 202);
        int goodTimeColor = ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFFFFFFFF, 0.35f), fadeAlpha);
        int badTimeColor = ColorUtil.withAlpha(0xFFF87171, fadeAlpha);

        for (int i = 0; i < list.size(); i++) {
            PotionEntry entry = list.get(i);
            boolean harmful = forceHarmful || entry.harmful;
            float rowY = startY + i * (ROW_H + ROW_GAP) * frameHudScale;
            float nameY = rowY + (ROW_H * frameHudScale - FontRenderer.getHeight(nameFont)) * 0.5f;
            FontRenderer.drawText(batch, entry.title, smoothX + textOffsetX, nameY, nameFont, harmful ? badTextColor : normalColor);

            float timeW = FontRenderer.getWidth(entry.timeText, timeFont);
            float timeY = rowY + (ROW_H * frameHudScale - FontRenderer.getHeight(timeFont)) * 0.5f;
            FontRenderer.drawText(batch, entry.timeText, smoothX + frameScaledW - rightPad - timeW, timeY, timeFont,
                    harmful ? badTimeColor : goodTimeColor);
        }
    }

    private static void collectPotions(MinecraftClient client, PotionsHudModule module) {
        ALL_ENTRIES.clear();
        GOOD_ENTRIES.clear();
        BAD_ENTRIES.clear();
        if (!module.isEnabled() || client.player == null) {
            return;
        }

        List<StatusEffectInstance> effects = new ArrayList<>(client.player.getStatusEffects());
        effects.sort(Comparator.comparingInt(StatusEffectInstance::getDuration).reversed());

        for (StatusEffectInstance inst : effects) {
            RegistryEntry<StatusEffect> type = inst.getEffectType();
            StatusEffect effect = type.value();
            boolean harmful = effect.getCategory() == StatusEffectCategory.HARMFUL;
            String title = formatEffectName(effect.getName().getString(), inst.getAmplifier());
            String time = inst.isInfinite() ? "**:**" : formatDuration(inst.getDuration());
            PotionEntry entry = new PotionEntry(InGameHud.getEffectTexture(type), title, time, harmful);
            ALL_ENTRIES.add(entry);
            if (harmful) {
                BAD_ENTRIES.add(entry);
            } else {
                GOOD_ENTRIES.add(entry);
            }
        }

        if (ALL_ENTRIES.isEmpty() && client.currentScreen instanceof ChatScreen) {
            PotionEntry p1 = new PotionEntry(InGameHud.getEffectTexture(StatusEffects.SPEED), "Speed II", "01:45", false);
            PotionEntry p2 = new PotionEntry(InGameHud.getEffectTexture(StatusEffects.STRENGTH), "Strength", "02:10", false);
            PotionEntry p3 = new PotionEntry(InGameHud.getEffectTexture(StatusEffects.SLOWNESS), "Slowness", "00:15", true);
            ALL_ENTRIES.add(p1);
            ALL_ENTRIES.add(p2);
            ALL_ENTRIES.add(p3);
            GOOD_ENTRIES.add(p1);
            GOOD_ENTRIES.add(p2);
            BAD_ENTRIES.add(p3);
        }
    }

    public static String formatEffectName(String baseName, int amplifier) {
        if (amplifier <= 0) {
            return baseName;
        }
        String roman = switch (amplifier + 1) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(amplifier + 1);
        };
        return baseName + " " + roman;
    }

    private static String formatDuration(int ticks) {
        int totalSec = Math.max(0, ticks / 20);
        int mins = totalSec / 60;
        int secs = totalSec % 60;
        return String.format(Locale.US, "%02d:%02d", mins, secs);
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
