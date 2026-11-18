package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.CooldownsHudModule;
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
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Рендерер HUD-модуля {@link CooldownsHudModule}:
 * стеклянная карточка с иконкой предмета, названием, таймером в секундах и полоской прогресса.
 */
public final class CooldownsRenderer {
    private static final float BASE_WIDTH = 166.0f;
    private static final float PAD_X = 8.0f;
    private static final float PAD_Y = 7.0f;
    private static final float HEADER_H = 16.0f;
    private static final float ROW_GAP = 5.0f;
    private static final float ICON_SIZE = 14.0f;
    private static final float FONT_HEADER_SIZE = 10.5f;
    private static final float FONT_NAME_SIZE = 10.5f;
    private static final float FONT_TIME_SIZE = 10.0f;
    private static final float BAR_HEIGHT = 2.0f;
    private static final float CARD_RADIUS = 11.0f;

    private record CooldownEntry(ItemStack stack, String name, String timeText, float progress) {}

    private static final List<CooldownEntry> ENTRIES = new ArrayList<>();
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
    private static boolean frameShowBar = true;
    private static float frameRowH = 22.0f;

    private CooldownsRenderer() {}

    public static void prepareAndRenderIcons(DrawContext context, MinecraftClient client, float tickProgress) {
        framePrepared = false;
        if (HudGlassHelper.shouldSkipHud(client)) {
            return;
        }
        CooldownsHudModule module = ModuleManager.getCooldownsHudModule();
        if (module == null) {
            return;
        }

        float dt = computeDelta();
        collectEntries(client, module, tickProgress);
        boolean shouldShow = module.isEnabled() && !ENTRIES.isEmpty();
        fadeAlpha = AnimationEngine.expLerp(fadeAlpha, shouldShow ? 1.0f : 0.0f, 15.0f, dt);
        if (!shouldShow && fadeAlpha <= 0.01f) {
            return;
        }
        if (ENTRIES.isEmpty() || !FontRenderer.hasDefaultFont()) {
            return;
        }

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        boolean showBar = module.showBar.getValue();
        float hudScale = module.getHudScale();
        float maxNameAndTimeW = BASE_WIDTH;
        for (CooldownEntry entry : ENTRIES) {
            float needed = PAD_X * 2.0f + ICON_SIZE + 6.0f
                    + FontRenderer.getWidth(entry.name, FONT_NAME_SIZE)
                    + 12.0f
                    + FontRenderer.getWidth(entry.timeText, FONT_TIME_SIZE);
            maxNameAndTimeW = Math.max(maxNameAndTimeW, needed);
        }

        float rowH = showBar ? 22.0f : 18.0f;
        float baseH = PAD_Y * 2.0f + HEADER_H + ENTRIES.size() * rowH + Math.max(0, ENTRIES.size() - 1) * ROW_GAP;
        float scaledW = maxNameAndTimeW * hudScale;
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
        frameShowBar = showBar;
        frameRowH = rowH;

        if (context != null && fadeAlpha > 0.10f) {
            float guiScale = Math.max(1.0f, (float) client.getWindow().getScaleFactor());
            float iconSize = ICON_SIZE * hudScale;
            float listTopY = smoothY + (PAD_Y + HEADER_H) * hudScale;
            for (int i = 0; i < ENTRIES.size(); i++) {
                float rowY = listTopY + i * (rowH + ROW_GAP) * hudScale;
                float iconX = smoothX + PAD_X * hudScale;
                float iconY = rowY + ((showBar ? 17.0f : 16.0f) * hudScale - iconSize) * 0.5f;
                HudGlassHelper.drawScaledItem(context, ENTRIES.get(i).stack, iconX, iconY, iconSize, uiScale, guiScale);
            }
        }
    }

    public static void renderGlassBackgrounds(MinecraftClient client) {
        if (!framePrepared || ENTRIES.isEmpty()) {
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
        int textColor = ColorUtil.argb((int) (242.0f * fadeAlpha), 243, 244, 246);
        int timeColor = ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFFFFFFFF, 0.35f), fadeAlpha);
        int trackColor = ColorUtil.argb((int) (60.0f * fadeAlpha), 255, 255, 255);
        int barFillColor = ColorUtil.withAlpha(accent, 0.92f * fadeAlpha);

        batch.begin(frameCanvasW, frameCanvasH, frameUiScale);
        HudGlassHelper.drawGlassCard(
                batch, blur, blurredTex,
                smoothX, smoothY, frameScaledW, frameScaledH,
                CARD_RADIUS * frameHudScale, frameHudScale, fadeAlpha, borderColor,
                frameCanvasW, frameCanvasH
        );

        float headerFont = FONT_HEADER_SIZE * frameHudScale;
        int headerTitleColor = ColorUtil.argb((int) (245.0f * fadeAlpha), 243, 244, 246);
        int headerCountColor = ColorUtil.withAlpha(accent, fadeAlpha);
        int dividerColor = ColorUtil.argb((int) (46.0f * fadeAlpha), 255, 255, 255);
        HudGlassHelper.drawHudHeader(
                batch,
                "Cooldowns",
                headerTitleColor,
                String.valueOf(ENTRIES.size()),
                headerCountColor,
                smoothX,
                smoothY + PAD_Y * frameHudScale,
                frameScaledW,
                PAD_X,
                HEADER_H,
                headerFont,
                frameHudScale,
                dividerColor
        );

        float nameFont = FONT_NAME_SIZE * frameHudScale;
        float timeFont = FONT_TIME_SIZE * frameHudScale;
        float textOffsetX = (PAD_X + ICON_SIZE + 6.0f) * frameHudScale;
        float rightPad = PAD_X * frameHudScale;
        float listTopY = smoothY + (PAD_Y + HEADER_H) * frameHudScale;

        for (int i = 0; i < ENTRIES.size(); i++) {
            CooldownEntry entry = ENTRIES.get(i);
            float rowY = listTopY + i * (frameRowH + ROW_GAP) * frameHudScale;
            float rowTextAreaH = (frameShowBar ? 17.0f : 16.0f) * frameHudScale;

            float nameY = rowY + (rowTextAreaH - FontRenderer.getHeight(nameFont)) * 0.5f;
            FontRenderer.drawText(batch, entry.name, smoothX + textOffsetX, nameY, nameFont, textColor);

            float timeW = FontRenderer.getWidth(entry.timeText, timeFont);
            float timeY = rowY + (rowTextAreaH - FontRenderer.getHeight(timeFont)) * 0.5f;
            FontRenderer.drawText(batch, entry.timeText, smoothX + frameScaledW - rightPad - timeW, timeY, timeFont, timeColor);

            if (frameShowBar) {
                float barX = smoothX + PAD_X * frameHudScale;
                float barY = rowY + frameRowH * frameHudScale - BAR_HEIGHT * frameHudScale;
                float barW = Math.max(8.0f, frameScaledW - PAD_X * 2.0f * frameHudScale);
                float barH = BAR_HEIGHT * frameHudScale;
                float r = barH * 0.5f;
                batch.drawRoundedRect(barX, barY, barW, barH, r, trackColor);
                float fillW = Math.max(barH, barW * Math.clamp(entry.progress, 0.0f, 1.0f));
                batch.drawRoundedRect(barX, barY, fillW, barH, r, barFillColor);
            }
        }

        batch.end();
    }

    private static void collectEntries(MinecraftClient client, CooldownsHudModule module, float tickProgress) {
        ENTRIES.clear();
        if (!module.isEnabled() || client.player == null) {
            return;
        }
        ClientPlayerEntity player = client.player;
        ItemCooldownManager manager = player.getItemCooldownManager();
        CooldownManagerAccess access = (CooldownManagerAccess) manager;

        Map<Identifier, CooldownEntry> unique = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            collectStackCooldown(player.getInventory().getStack(i), manager, access, tickProgress, unique);
        }
        collectStackCooldown(player.getOffHandStack(), manager, access, tickProgress, unique);
        ENTRIES.addAll(unique.values());

        if (ENTRIES.isEmpty() && client.currentScreen instanceof ChatScreen) {
            ENTRIES.add(new CooldownEntry(new ItemStack(Items.ENDER_PEARL), "Ender Pearl", "8.5s", 0.57f));
            ENTRIES.add(new CooldownEntry(new ItemStack(Items.GOLDEN_APPLE), "Golden Apple", "3.2s", 0.36f));
        }
    }

    private static void collectStackCooldown(ItemStack stack, ItemCooldownManager manager,
                                             CooldownManagerAccess access, float tickProgress,
                                             Map<Identifier, CooldownEntry> out) {
        if (stack == null || stack.isEmpty() || !manager.isCoolingDown(stack)) {
            return;
        }
        Identifier group = manager.getGroup(stack);
        if (out.containsKey(group)) {
            return;
        }
        float progress = Math.clamp(manager.getCooldownProgress(stack, tickProgress), 0.0f, 1.0f);
        float sec = access.nebulavisuals$getRemainingSeconds(stack, tickProgress);
        String timeStr = String.format(Locale.US, "%.1fs", Math.max(0.0f, sec));
        out.put(group, new CooldownEntry(stack, stack.getName().getString(), timeStr, progress));
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
