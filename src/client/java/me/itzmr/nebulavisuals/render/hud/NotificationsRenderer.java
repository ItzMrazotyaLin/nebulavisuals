package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.config.LocalizationManager;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.NotificationsHudModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.ClickGuiScreen;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine2d.util.IconAtlas;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Рендерер HUD-модуля {@link NotificationsHudModule}:
 * динамическая ширина без обрезки, режимы {@code Default}/{@code Compact}, 5 направлений анимации,
 * стекирование, превью в чате, низкая прочность и заканчивающиеся эффекты зелий.
 */
public final class NotificationsRenderer {
    public enum NotificationType {
        CONFIG,
        WARNING,
        MODULE_ON,
        MODULE_OFF,
        INFO
    }

    private static final float MIN_DEFAULT_WIDTH = 176.0f;
    private static final float DEFAULT_HEIGHT = 36.0f;
    private static final float MIN_COMPACT_WIDTH = 146.0f;
    private static final float COMPACT_HEIGHT = 23.0f;
    private static final float CARD_GAP = 6.0f;
    private static final float DEFAULT_RADIUS = 10.0f;
    private static final float COMPACT_RADIUS = 7.5f;
    private static final float ENTER_SEC = 0.26f;
    private static final float EXIT_SEC = 0.24f;
    private static final int MAX_ACTIVE_TOASTS = 5;

    private static final EquipmentSlot[] TRACKED_DURABILITY_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private static final class Toast {
        final NotificationType type;
        final String title;
        final String description;
        final long createdNs;
        final long totalDurationNs;
        float smoothOffsetY;
        boolean offsetInitialized = false;

        Toast(NotificationType type, String title, String description, long durationNs) {
            this.type = type;
            this.title = title;
            this.description = description;
            this.createdNs = System.nanoTime();
            this.totalDurationNs = Math.max(1_000_000_000L, durationNs);
        }
    }

    private static final class EffectTrackState {
        int maxSeenDuration;
        boolean warned;

        EffectTrackState(int initialDuration) {
            this.maxSeenDuration = initialDuration;
            this.warned = false;
        }
    }

    private static final List<Toast> TOASTS = new ArrayList<>();
    private static final Map<String, Integer> WARNED_DURABILITY_ITEMS = new HashMap<>();
    private static final Map<String, EffectTrackState> TRACKED_EFFECTS = new HashMap<>();
    private static Toast previewToast = null;
    private static boolean previewToastRussian = false;

    private static Toast getPreviewToast() {
        boolean isRu = LocalizationManager.isRussian();
        if (previewToast == null || previewToastRussian != isRu) {
            previewToast = new Toast(
                    NotificationType.INFO,
                    "Notifications HUD",
                    LocalizationManager.tr("notif.preview.desc"),
                    3_000_000_000L
            );
            previewToastRussian = isRu;
        }
        return previewToast;
    }

    private static long lastTimeNs = 0L;
    private static float smoothX = -1.0f;
    private static float smoothY = -1.0f;

    private NotificationsRenderer() {}

    public static void pushConfig(String title, String description) {
        push(NotificationType.CONFIG, title, description);
    }

    public static void pushWarning(String title, String description) {
        push(NotificationType.WARNING, title, description);
    }

    public static void pushModuleToggle(String moduleName, boolean enabled) {
        push(
                enabled ? NotificationType.MODULE_ON : NotificationType.MODULE_OFF,
                moduleName,
                LocalizationManager.tr(enabled ? "notif.module.enabled" : "notif.module.disabled")
        );
    }

    public static void push(NotificationType type, String title, String description) {
        NotificationsHudModule module = ModuleManager.getNotificationsHudModule();
        if (module == null || !module.isEnabled()) {
            return;
        }
        double durationSec = Math.clamp(module.duration.getValue(), 1.5, 6.0);
        long durationNs = Math.round(durationSec * 1_000_000_000.0);

        synchronized (TOASTS) {
            TOASTS.add(0, new Toast(
                    type,
                    title != null ? title : LocalizationManager.tr("notif.default_title"),
                    description != null ? description : "",
                    durationNs
            ));
            while (TOASTS.size() > MAX_ACTIVE_TOASTS) {
                TOASTS.remove(TOASTS.size() - 1);
            }
        }
    }

    public static void onClientTick(MinecraftClient client) {
        NotificationsHudModule module = ModuleManager.getNotificationsHudModule();
        if (module == null || !module.isEnabled() || client == null || client.player == null || client.world == null) {
            WARNED_DURABILITY_ITEMS.clear();
            TRACKED_EFFECTS.clear();
            return;
        }

        if (module.lowDurability.getValue()) {
            checkLowDurability(client);
        } else {
            WARNED_DURABILITY_ITEMS.clear();
        }

        if (module.expiringEffects.getValue()) {
            checkExpiringEffects(client);
        } else {
            TRACKED_EFFECTS.clear();
        }
    }

    private static void checkLowDurability(MinecraftClient client) {
        Map<String, ItemStack> currentEquipped = new HashMap<>();
        for (EquipmentSlot slot : TRACKED_DURABILITY_SLOTS) {
            ItemStack stack = client.player.getEquippedStack(slot);
            if (stack != null && !stack.isEmpty() && stack.isDamageable() && stack.getMaxDamage() > 0) {
                Item item = stack.getItem();
                String key = slot.isArmorSlot()
                        ? ("armor:" + slot.getName() + ":" + Registries.ITEM.getId(item))
                        : ("hand:" + Registries.ITEM.getId(item));
                currentEquipped.putIfAbsent(key, stack);
            }
        }

        Iterator<Map.Entry<String, Integer>> it = WARNED_DURABILITY_ITEMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> entry = it.next();
            ItemStack current = currentEquipped.get(entry.getKey());
            if (current == null || current.isEmpty()) {
                it.remove();
                continue;
            }
            int max = current.getMaxDamage();
            int rem = Math.max(0, max - current.getDamage());
            float pct = (float) rem / max;
            if (pct >= 0.18f && rem > 24) {
                it.remove();
            }
        }

        for (Map.Entry<String, ItemStack> entry : currentEquipped.entrySet()) {
            String key = entry.getKey();
            if (WARNED_DURABILITY_ITEMS.containsKey(key)) {
                continue;
            }
            ItemStack stack = entry.getValue();
            int max = stack.getMaxDamage();
            int rem = Math.max(0, max - stack.getDamage());
            float pct = (float) rem / max;
            if (pct < 0.15f || rem <= 20) {
                WARNED_DURABILITY_ITEMS.put(key, rem);
                int pctInt = Math.clamp(Math.round(pct * 100.0f), 0, 100);
                pushWarning(
                        LocalizationManager.tr("notif.durability.title"),
                        stack.getName().getString() + " (" + rem + " / " + pctInt + "%)"
                );
            }
        }
    }

    private static void checkExpiringEffects(MinecraftClient client) {
        Set<String> activeKeys = new HashSet<>();
        for (StatusEffectInstance inst : client.player.getStatusEffects()) {
            if (inst == null || inst.isInfinite() || inst.isAmbient()) {
                continue;
            }
            RegistryEntry<StatusEffect> type = inst.getEffectType();
            String id = type.getKey()
                    .map(key -> key.getValue().toString())
                    .orElseGet(() -> type.value().getName().getString());
            String trackKey = id + ":" + inst.getAmplifier();
            activeKeys.add(trackKey);

            int durationTicks = inst.getDuration();
            EffectTrackState state = TRACKED_EFFECTS.get(trackKey);
            if (state == null) {
                state = new EffectTrackState(durationTicks);
                TRACKED_EFFECTS.put(trackKey, state);
            } else {
                if (durationTicks > state.maxSeenDuration) {
                    state.maxSeenDuration = durationTicks;
                }
                if (durationTicks > 120) {
                    state.warned = false;
                }
            }

            if (!state.warned && state.maxSeenDuration > 120 && durationTicks > 0 && durationTicks <= 100) {
                state.warned = true;
                String effectName = PotionsRenderer.formatEffectName(
                        type.value().getName().getString(),
                        inst.getAmplifier()
                );
                pushWarning(
                        LocalizationManager.tr("notif.effect.title"),
                        LocalizationManager.tr("notif.effect.desc", effectName)
                );
            }
        }

        if (!TRACKED_EFFECTS.isEmpty()) {
            TRACKED_EFFECTS.keySet().removeIf(k -> !activeKeys.contains(k));
        }
    }

    public static void render(MinecraftClient client) {
        if (client == null || client.getWindow() == null || client.options.hudHidden) {
            return;
        }
        if (client.currentScreen != null
                && !(client.currentScreen instanceof ChatScreen)
                && !(client.currentScreen instanceof ClickGuiScreen)) {
            return;
        }
        NotificationsHudModule module = ModuleManager.getNotificationsHudModule();
        if (module == null) {
            return;
        }

        float dt = computeDelta();
        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        boolean compact = "Compact".equalsIgnoreCase(module.style.getValue());
        float hudScale = module.getHudScale();
        float baseAnchorH = compact ? COMPACT_HEIGHT : DEFAULT_HEIGHT;
        float anchorW = (compact ? MIN_COMPACT_WIDTH : MIN_DEFAULT_WIDTH) * hudScale;
        float anchorH = baseAnchorH * hudScale;

        float maxX = Math.max(HudGlassHelper.MARGIN_EDGE, canvasW - anchorW - HudGlassHelper.MARGIN_EDGE);
        float maxY = Math.max(HudGlassHelper.MARGIN_EDGE, canvasH - anchorH - HudGlassHelper.MARGIN_EDGE);
        float targetX = Math.clamp(module.getHudX(), HudGlassHelper.MARGIN_EDGE, maxX);
        float targetY = Math.clamp(module.getHudY(), HudGlassHelper.MARGIN_EDGE, maxY);

        boolean interactive = HudGlassHelper.isInteractive(module);
        smoothX = HudGlassHelper.resolveAxisPosition(targetX, smoothX, interactive, dt);
        smoothY = HudGlassHelper.resolveAxisPosition(targetY, smoothY, interactive, dt);
        module.updateRenderedBounds(smoothX, smoothY, anchorW, anchorH);

        if (!module.isEnabled()) {
            synchronized (TOASTS) {
                TOASTS.clear();
            }
            return;
        }

        if (!NebulaRenderEngine.isInitialized()) {
            NebulaRenderEngine.init();
        }
        if (!FontRenderer.hasDefaultFont()) {
            return;
        }

        long now = System.nanoTime();
        List<Toast> activeSnapshot;
        synchronized (TOASTS) {
            TOASTS.removeIf(t -> (now - t.createdNs) >= t.totalDurationNs);
            activeSnapshot = new ArrayList<>(TOASTS);
        }

        boolean isPreview = false;
        if (activeSnapshot.isEmpty() && client.currentScreen instanceof ChatScreen) {
            isPreview = true;
            activeSnapshot.add(getPreviewToast());
        }

        if (activeSnapshot.isEmpty()) {
            return;
        }

        if (HudGlassHelper.isOverlappingClickGui(client, smoothX, smoothY, anchorW, anchorH)) {
            return;
        }

        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        KawaseBlur blur = NebulaRenderEngine.getBlur();
        int blurredTex = HudGlassHelper.prepareBlurTexture(client, blur, fbW, fbH);

        float anchorCenterX = smoothX + anchorW * 0.5f;
        float anchorCenterRatioX = anchorCenterX / Math.max(1.0f, canvasW);
        boolean stackUpward = (smoothY + anchorH * 0.5f) >= (canvasH * 0.5f);
        String animMode = module.animation.getValue();
        int accent = ClientSettings.getAccentColor();

        batch.begin(canvasW, canvasH, uiScale);

        for (int i = 0; i < activeSnapshot.size(); i++) {
            Toast toast = activeSnapshot.get(i);

            float cardW = computeToastWidth(toast, compact, hudScale, canvasW);
            float cardXBase = alignCardToAnchor(cardW, anchorCenterX, anchorCenterRatioX, anchorW, canvasW);

            float targetOffsetY = stackUpward
                    ? -i * (anchorH + CARD_GAP * hudScale)
                    : i * (anchorH + CARD_GAP * hudScale);
            if (!toast.offsetInitialized) {
                toast.smoothOffsetY = targetOffsetY;
                toast.offsetInitialized = true;
            } else {
                toast.smoothOffsetY = AnimationEngine.expLerp(toast.smoothOffsetY, targetOffsetY, 16.0f, dt);
            }
            float cardYBase = Math.clamp(
                    smoothY + toast.smoothOffsetY,
                    HudGlassHelper.MARGIN_EDGE,
                    Math.max(HudGlassHelper.MARGIN_EDGE, canvasH - anchorH - HudGlassHelper.MARGIN_EDGE)
            );

            float alpha;
            float offsetX = 0.0f;
            float offsetY = 0.0f;
            float animScale = 1.0f;
            float remainingRatio;
            if (isPreview) {
                alpha = 1.0f;
                remainingRatio = 0.65f;
            } else {
                float elapsedSec = Math.max(0.0f, (now - toast.createdNs) / 1_000_000_000.0f);
                float totalSec = toast.totalDurationNs / 1_000_000_000.0f;
                float enterT = Math.clamp(elapsedSec / ENTER_SEC, 0.0f, 1.0f);
                float exitStart = Math.max(ENTER_SEC, totalSec - EXIT_SEC);
                float exitT = elapsedSec > exitStart ? Math.clamp((elapsedSec - exitStart) / EXIT_SEC, 0.0f, 1.0f) : 0.0f;

                float enterEase = easeOutCubic(enterT);
                float exitEase = easeInCubic(exitT);
                alpha = Math.clamp(enterEase * (1.0f - exitEase), 0.0f, 1.0f);

                float combinedMove = (1.0f - enterEase) + exitEase;
                switch (animMode) {
                    case "Left" -> offsetX = -combinedMove * (cardW + 24.0f * hudScale);
                    case "Bottom" -> offsetY = combinedMove * (anchorH + 22.0f * hudScale);
                    case "Top" -> offsetY = -combinedMove * (anchorH + 22.0f * hudScale);
                    case "Scale" -> animScale = 0.88f + 0.12f * alpha;
                    default -> offsetX = combinedMove * (cardW + 24.0f * hudScale); // Right
                }
                remainingRatio = Math.clamp(1.0f - elapsedSec / Math.max(0.1f, totalSec), 0.0f, 1.0f);
            }

            if (alpha <= 0.01f) {
                continue;
            }

            float finalScale = hudScale * animScale;
            float scaledCardW = cardW * animScale;
            float scaledCardH = anchorH * animScale;
            float centerX = cardXBase + offsetX + cardW * 0.5f;
            float centerY = cardYBase + offsetY + anchorH * 0.5f;
            float drawX = centerX - scaledCardW * 0.5f;
            float drawY = centerY - scaledCardH * 0.5f;

            if (compact) {
                renderCompactCard(batch, blur, blurredTex, toast, drawX, drawY, scaledCardW, scaledCardH,
                        finalScale, alpha, remainingRatio, accent, canvasW, canvasH);
            } else {
                renderDefaultCard(batch, blur, blurredTex, toast, drawX, drawY, scaledCardW, scaledCardH,
                        finalScale, alpha, remainingRatio, accent, canvasW, canvasH);
            }
        }

        batch.end();
    }

    private static float alignCardToAnchor(float cardW, float anchorCenterX, float anchorCenterRatioX,
                                           float anchorW, float canvasW) {
        float rawX;
        if (anchorCenterRatioX < 0.40f) {
            rawX = smoothX;
        } else if (anchorCenterRatioX <= 0.60f) {
            rawX = anchorCenterX - cardW * 0.5f;
        } else {
            rawX = smoothX + anchorW - cardW;
        }
        float maxX = Math.max(HudGlassHelper.MARGIN_EDGE, canvasW - cardW - HudGlassHelper.MARGIN_EDGE);
        return Math.clamp(rawX, HudGlassHelper.MARGIN_EDGE, maxX);
    }

    private static float computeToastWidth(Toast toast, boolean compact, float hudScale, float canvasW) {
        float unscaledW;
        if (compact) {
            String line = buildCompactLine(toast);
            float textStartX = 8.0f + 12.0f + 6.0f;
            float rightPad = 10.0f;
            unscaledW = Math.max(MIN_COMPACT_WIDTH, textStartX + FontRenderer.getWidth(line, 10.0f) + rightPad);
        } else {
            float textStartX = 9.0f + 22.0f + 8.0f;
            float rightPad = 12.0f;
            float titleW = FontRenderer.getWidth(toast.title, 10.8f);
            float descW = FontRenderer.getWidth(toast.description, 9.2f);
            unscaledW = Math.max(MIN_DEFAULT_WIDTH, textStartX + Math.max(titleW, descW) + rightPad);
        }
        float maxAllowed = Math.max(120.0f, canvasW - HudGlassHelper.MARGIN_EDGE * 2.0f);
        return Math.min(maxAllowed, unscaledW * hudScale);
    }

    private static String buildCompactLine(Toast toast) {
        if (toast.description == null || toast.description.isEmpty()) {
            return toast.title;
        }
        return toast.title + ": " + toast.description;
    }

    private static void renderDefaultCard(RenderBatch2D batch, KawaseBlur blur, int blurredTex,
                                          Toast toast, float x, float y, float w, float h,
                                          float scale, float alpha, float remainingRatio,
                                          int accent, float canvasW, float canvasH) {
        int typeColor = resolveTypeColor(toast.type, accent);
        int borderColor = ColorUtil.withAlpha(typeColor, 0.48f * alpha);
        float radius = DEFAULT_RADIUS * scale;

        HudGlassHelper.drawGlassCard(batch, blur, blurredTex, x, y, w, h, radius, scale, alpha, borderColor, canvasW, canvasH);

        float padX = 9.0f * scale;
        float iconBoxSize = 22.0f * scale;
        float iconBoxX = x + padX;
        float iconBoxY = y + (h - 2.0f * scale - iconBoxSize) * 0.5f;
        batch.drawRoundedRectWithOutline(
                iconBoxX, iconBoxY, iconBoxSize, iconBoxSize, 6.0f * scale,
                ColorUtil.withAlpha(typeColor, 0.16f * alpha),
                1.0f * scale,
                ColorUtil.withAlpha(typeColor, 0.36f * alpha)
        );
        IconAtlas.drawIcon(
                batch,
                resolveTypeIcon(toast.type),
                iconBoxX + iconBoxSize * 0.5f,
                iconBoxY + iconBoxSize * 0.5f,
                12.5f * scale,
                0.0f,
                ColorUtil.withAlpha(ColorUtil.lerp(typeColor, 0xFFFFFFFF, 0.25f), alpha)
        );

        float textX = iconBoxX + iconBoxSize + 8.0f * scale;
        float titleSize = 10.8f * scale;
        float descSize = 9.2f * scale;

        float blockH = FontRenderer.getHeight(titleSize) + 2.0f * scale + FontRenderer.getHeight(descSize);
        float titleY = y + (h - 2.0f * scale - blockH) * 0.5f;
        float descY = titleY + FontRenderer.getHeight(titleSize) + 2.0f * scale;

        FontRenderer.drawText(batch, toast.title, textX, titleY, titleSize, ColorUtil.argb((int) (245.0f * alpha), 243, 244, 246));
        if (!toast.description.isEmpty()) {
            FontRenderer.drawText(batch, toast.description, textX, descY, descSize, ColorUtil.argb((int) (210.0f * alpha), 192, 198, 208));
        }

        drawProgressBar(batch, x, y, w, h, scale, alpha, remainingRatio, typeColor, 8.0f);
    }

    private static void renderCompactCard(RenderBatch2D batch, KawaseBlur blur, int blurredTex,
                                          Toast toast, float x, float y, float w, float h,
                                          float scale, float alpha, float remainingRatio,
                                          int accent, float canvasW, float canvasH) {
        int typeColor = resolveTypeColor(toast.type, accent);
        int borderColor = ColorUtil.withAlpha(typeColor, 0.45f * alpha);
        float radius = COMPACT_RADIUS * scale;

        HudGlassHelper.drawGlassCard(batch, blur, blurredTex, x, y, w, h, radius, scale, alpha, borderColor, canvasW, canvasH);

        float padX = 8.0f * scale;
        float iconSize = 12.0f * scale;
        float iconCenterX = x + padX + iconSize * 0.5f;
        float iconCenterY = y + (h - 2.0f * scale) * 0.5f;
        IconAtlas.drawIcon(
                batch,
                resolveTypeIcon(toast.type),
                iconCenterX,
                iconCenterY,
                iconSize,
                0.0f,
                ColorUtil.withAlpha(ColorUtil.lerp(typeColor, 0xFFFFFFFF, 0.25f), alpha)
        );

        float textX = x + padX + iconSize + 6.0f * scale;
        float textSize = 10.0f * scale;
        float textY = y + (h - 2.0f * scale - FontRenderer.getHeight(textSize)) * 0.5f;
        String line = buildCompactLine(toast);
        FontRenderer.drawText(batch, line, textX, textY, textSize, ColorUtil.argb((int) (242.0f * alpha), 243, 244, 246));

        drawProgressBar(batch, x, y, w, h, scale, alpha, remainingRatio, typeColor, 6.0f);
    }

    private static void drawProgressBar(RenderBatch2D batch, float x, float y, float w, float h,
                                        float scale, float alpha, float remainingRatio, int color, float marginX) {
        float barMarginX = marginX * scale;
        float barW = Math.max(10.0f, w - barMarginX * 2.0f);
        float barH = 1.8f * scale;
        float barX = x + barMarginX;
        float barY = y + h - barH - 2.0f * scale;
        batch.drawRoundedRect(barX, barY, barW, barH, barH * 0.5f, ColorUtil.argb((int) (45.0f * alpha), 255, 255, 255));
        float fillW = barW * Math.clamp(remainingRatio, 0.0f, 1.0f);
        if (fillW > barH * 0.5f) {
            batch.drawRoundedRect(barX, barY, Math.max(barH, fillW), barH, barH * 0.5f, ColorUtil.withAlpha(color, 0.92f * alpha));
        }
    }

    private static int resolveTypeColor(NotificationType type, int accent) {
        return switch (type) {
            case WARNING -> 0xFFF59E0B;
            case MODULE_ON -> ColorUtil.lerp(accent, 0xFF34D399, 0.45f);
            case MODULE_OFF -> 0xFF9CA3AF;
            case CONFIG, INFO -> accent;
        };
    }

    private static IconAtlas.Icon resolveTypeIcon(NotificationType type) {
        return switch (type) {
            case CONFIG -> IconAtlas.Icon.FOLDER;
            case WARNING -> IconAtlas.Icon.BOLT;
            case MODULE_ON -> IconAtlas.Icon.CHECK;
            case MODULE_OFF -> IconAtlas.Icon.CLOSE;
            case INFO -> IconAtlas.Icon.INFO;
        };
    }

    private static float easeOutCubic(float t) {
        float inv = 1.0f - Math.clamp(t, 0.0f, 1.0f);
        return 1.0f - inv * inv * inv;
    }

    private static float easeInCubic(float t) {
        float c = Math.clamp(t, 0.0f, 1.0f);
        return c * c * c;
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
