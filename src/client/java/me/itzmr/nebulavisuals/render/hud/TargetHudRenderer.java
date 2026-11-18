package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.TargetHudModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.blur.KawaseBlur;
import me.itzmr.nebulavisuals.render.engine2d.font.FontRenderer;
import me.itzmr.nebulavisuals.render.engine2d.screen.ClickGuiScreen;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.util.EntityVisibilityUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.Locale;
import java.util.UUID;

/**
 * Рендерер HUD-модуля TargetHUD: стеклянная карточка с чёткой пиксельной головой цели (GL_NEAREST + SDF-скругление),
 * ником, дистанцией, здоровьем (включая золотой бонус Absorption), статусом WIN/LOSE,
 * плавной полоской HP и превью-режимом для ChatScreen / ClickGuiScreen.
 */
public final class TargetHudRenderer {
    private static final float CARD_WIDTH = 210.0f;
    private static final float CARD_HEIGHT = 62.0f;
    private static final float CARD_RADIUS = 14.0f;
    private static final float SAFE_MARGIN = 8.0f;

    private static final float HEAD_SIZE = 38.0f;
    private static final float HEAD_RADIUS = 8.0f;
    private static final float BAR_HEIGHT = 7.0f;
    private static final float BAR_RADIUS = 3.5f;

    private static final double COMBAT_RANGE_SQ = 12.0 * 12.0;
    private static final long AIM_TIMEOUT_NS = 1_500_000_000L;
    private static final long COMBAT_TIMEOUT_NS = 3_000_000_000L;

    private static long lastTimeNs = 0L;
    private static PlayerEntity activeTarget = null;
    private static boolean inCombat = false;
    private static long lastAimNs = 0L;
    private static long lastCombatNs = 0L;
    private static int lastTrackedPlayerId = -1;
    private static int lastProcessedAttackTime = -1;
    private static int lastProcessedAttackedTime = -1;

    private static UUID lastSnapshotUuid = null;
    private static String snapName = "Player";
    private static float snapHealth = 20.0f;
    private static float snapMaxHealth = 20.0f;
    private static float snapAbsorption = 0.0f;
    private static float snapDistance = 0.0f;
    private static float snapSelfTotalHp = 20.0f;
    private static boolean snapIsPreview = true;
    private static boolean snapHealthHidden = false;
    private static boolean snapHideDistance = false;
    private static Identifier snapSkinId = null;

    private static float visAnim = 0.0f;
    private static float smoothX = -1.0f;
    private static float smoothY = -1.0f;
    private static float smoothHpRatio = 1.0f;
    private static float smoothAbsRatio = 0.0f;

    private TargetHudRenderer() {}

    public static boolean isInActiveCombat() {
        return inCombat && (System.nanoTime() - lastCombatNs) <= COMBAT_TIMEOUT_NS;
    }

    /**
     * Фиксирует атаку по игроку на клиенте, переводя цель в боевой режим (таймаут 3.0 сек).
     */
    public static void onPlayerAttack(PlayerEntity attacker, Entity target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || attacker != client.player) {
            return;
        }
        if (target instanceof PlayerEntity targetPlayer && isValidPlayer(targetPlayer)) {
            lastTrackedPlayerId = client.player.getId();
            lastProcessedAttackTime = client.player.getLastAttackTime();
            lastProcessedAttackedTime = client.player.getLastAttackedTime();
            activeTarget = targetPlayer;
            inCombat = true;
            lastCombatNs = System.nanoTime();
        }
    }

    public static void render(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || client.getWindow() == null) {
            return;
        }
        if (client.options.hudHidden) {
            return;
        }
        if (client.currentScreen != null
                && !(client.currentScreen instanceof ChatScreen)
                && !(client.currentScreen instanceof ClickGuiScreen)) {
            return;
        }

        TargetHudModule module = ModuleManager.getTargetHudModule();
        if (module == null) {
            return;
        }

        float rawDt = computeRawDelta();
        float uiDt = Math.clamp(rawDt * ClientSettings.getAnimationSpeed(), 0.0001f, 0.1f);

        boolean previewMode = client.currentScreen instanceof ChatScreen || client.currentScreen instanceof ClickGuiScreen;
        PlayerEntity displayTarget = null;
        if (module.isEnabled()) {
            displayTarget = previewMode ? client.player : resolveCombatTarget(client);
        }

        if (displayTarget != null) {
            updateSnapshot(client, displayTarget, previewMode);
        } else if (!previewMode && lastSnapshotUuid != null) {
            PlayerEntity tracked = client.world.getPlayerByUuid(lastSnapshotUuid);
            if (tracked != null && !EntityVisibilityUtil.canRenderVisualsOn(tracked)) {
                activeTarget = null;
                inCombat = false;
                lastSnapshotUuid = null;
                visAnim = 0.0f;
                return;
            }
        }

        float targetVis = (module.isEnabled() && displayTarget != null) ? 1.0f : 0.0f;
        visAnim = AnimationEngine.expLerp(visAnim, targetVis, 13.0f, uiDt);
        if (visAnim <= 0.01f) {
            if (displayTarget == null) {
                lastSnapshotUuid = null;
            }
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
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);
        float canvasW = fbW / uiScale;
        float canvasH = fbH / uiScale;

        float hudScale = module.getHudScale();
        float scaledW = CARD_WIDTH * hudScale;
        float scaledH = CARD_HEIGHT * hudScale;

        float maxX = Math.max(SAFE_MARGIN, canvasW - scaledW - SAFE_MARGIN);
        float maxY = Math.max(SAFE_MARGIN, canvasH - scaledH - SAFE_MARGIN);
        float targetX = Math.clamp(module.getHudX(), SAFE_MARGIN, maxX);
        float targetY = Math.clamp(module.getHudY(), SAFE_MARGIN, maxY);

        boolean dragging = HudEditor.isDragging(module) || HudEditor.isResizing(module);
        if (dragging || smoothX < 0.0f || smoothY < 0.0f) {
            smoothX = targetX;
            smoothY = targetY;
        } else {
            smoothX = AnimationEngine.expLerp(smoothX, targetX, 16.0f, uiDt);
            smoothY = AnimationEngine.expLerp(smoothY, targetY, 16.0f, uiDt);
        }

        module.updateRenderedBounds(smoothX, smoothY, scaledW, scaledH);
        if (client.currentScreen instanceof ClickGuiScreen clickGui
                && clickGui.isRectOverlappingWindow(smoothX, smoothY, scaledW, scaledH)) {
            return;
        }

        float targetHpRatio = Math.clamp(snapHealth / Math.max(1.0f, snapMaxHealth), 0.0f, 1.0f);
        float targetAbsRatio = module.showAbsorption.getValue()
                ? Math.clamp(snapAbsorption / Math.max(1.0f, snapMaxHealth), 0.0f, 1.0f)
                : 0.0f;
        float hpSpeed = module.smoothSpeed.getValue().floatValue();
        smoothHpRatio = AnimationEngine.expLerp(smoothHpRatio, targetHpRatio, hpSpeed, rawDt);
        smoothAbsRatio = AnimationEngine.expLerp(smoothAbsRatio, targetAbsRatio, hpSpeed, rawDt);

        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        KawaseBlur blur = NebulaRenderEngine.getBlur();

        int blurredTex = 0;
        float blurStrength = ClientSettings.getBlurStrength();
        float blurFade = blurStrength <= 0.01f ? 0.0f : Math.clamp(blurStrength / 0.25f, 0.0f, 1.0f);
        if (blurFade > 0.001f && client.getFramebuffer() != null
                && client.getFramebuffer().getColorAttachment() instanceof GlTexture glTexture) {
            int fbTex = glTexture.getGlId();
            if (fbTex > 0) {
                float offset = Math.clamp(1.8f * blurStrength, 0.25f, 4.2f);
                blurredTex = blur.renderBlur(fbTex, fbW, fbH, 3, offset);
            }
        }

        float animScale = dragging ? 1.0f : (0.92f + 0.08f * visAnim);
        float totalScale = hudScale * animScale;
        float cx = smoothX + scaledW * 0.5f;
        float cy = smoothY + scaledH * 0.5f;

        float rx = scaleCardCoord(smoothX, smoothX, CARD_WIDTH, cx, totalScale);
        float ry = scaleCardCoord(smoothY, smoothY, CARD_HEIGHT, cy, totalScale);
        float rw = CARD_WIDTH * totalScale;
        float rh = CARD_HEIGHT * totalScale;

        int accent = ClientSettings.getAccentColor();
        float uiOpacity = ClientSettings.getUiOpacity();
        int bgColor = ColorUtil.argb((int) (226.0f * uiOpacity * visAnim), 13, 15, 18);
        int borderColor = ColorUtil.withAlpha(ColorUtil.lerp(0xFF3A3E4A, accent, 0.42f), 0.56f * visAnim);

        batch.begin(canvasW, canvasH, uiScale);

        // 1. Тень, матовое стекло, подложка и тонкая акцентная рамка 1px
        batch.drawShadow(rx, ry, rw, rh, CARD_RADIUS * totalScale, 14.0f * totalScale,
                ColorUtil.argb((int) (95 * visAnim), 0, 0, 0));

        if (blurredTex != 0 && blurFade > 0.001f) {
            blur.drawBlurredRect(
                    batch, blurredTex,
                    rx, ry, rw, rh,
                    CARD_RADIUS * totalScale, canvasW, canvasH,
                    ColorUtil.argb((int) (255.0f * visAnim * blurFade), 255, 255, 255)
            );
        }

        batch.drawRoundedRectWithOutline(
                rx, ry, rw, rh,
                CARD_RADIUS * totalScale,
                bgColor,
                1.0f * totalScale,
                borderColor
        );

        // 2. Скруглённый аватар игрока (38x38, radius = 8px, лицо + второй слой шляпы)
        float headX = scaleCardCoord(smoothX + 12.0f, smoothX, CARD_WIDTH, cx, totalScale);
        float headY = scaleCardCoord(smoothY + (CARD_HEIGHT - HEAD_SIZE) * 0.5f, smoothY, CARD_HEIGHT, cy, totalScale);
        float headSize = HEAD_SIZE * totalScale;
        float headRadius = HEAD_RADIUS * totalScale;

        batch.drawRoundedRect(headX, headY, headSize, headSize, headRadius,
                ColorUtil.argb((int) (180 * visAnim), 22, 26, 34));

        int skinTexId = resolveSkinGlId(client, snapSkinId);
        if (skinTexId > 0) {
            batch.flush();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, skinTexId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            int whiteTint = ColorUtil.argb((int) (255 * visAnim), 255, 255, 255);
            batch.drawTexturedRoundedRect(
                    skinTexId,
                    headX, headY, headSize, headSize, headRadius,
                    8.0f / 64.0f, 8.0f / 64.0f, 16.0f / 64.0f, 16.0f / 64.0f,
                    whiteTint
            );
            batch.drawTexturedRoundedRect(
                    skinTexId,
                    headX, headY, headSize, headSize, headRadius,
                    40.0f / 64.0f, 8.0f / 64.0f, 48.0f / 64.0f, 16.0f / 64.0f,
                    whiteTint
            );

            batch.flush();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, skinTexId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }

        batch.drawOutlineRoundedRect(headX, headY, headSize, headSize, headRadius,
                1.0f * totalScale, ColorUtil.argb((int) (45 * visAnim), 255, 255, 255));

        // 3. Правая колонка информации
        float infoLocalX = smoothX + 12.0f + HEAD_SIZE + 10.0f;
        float infoLocalRight = smoothX + CARD_WIDTH - 12.0f;
        float infoLocalW = infoLocalRight - infoLocalX;

        // Верхняя строка: ник цели + компактный бейдж дистанции справа (скрывается в "? m" для невидимок без брони)
        float nameMaxW = infoLocalW;
        if (module.showDistance.getValue()) {
            String distLabel = snapHideDistance ? "? m" : String.format(Locale.US, "%.1fm", snapDistance);
            float distFont = 10.0f;
            float badgeW = FontRenderer.getWidth(distLabel, distFont) + 10.0f;
            float badgeH = 14.0f;
            float badgeLocalX = infoLocalRight - badgeW;
            float badgeLocalY = smoothY + 10.5f;
            nameMaxW = Math.max(24.0f, badgeLocalX - infoLocalX - 6.0f);

            float bx = scaleCardCoord(badgeLocalX, smoothX, CARD_WIDTH, cx, totalScale);
            float by = scaleCardCoord(badgeLocalY, smoothY, CARD_HEIGHT, cy, totalScale);
            batch.drawRoundedRectWithOutline(
                    bx, by, badgeW * totalScale, badgeH * totalScale, 5.0f * totalScale,
                    ColorUtil.argb((int) (22 * visAnim), 255, 255, 255),
                    1.0f * totalScale,
                    ColorUtil.argb((int) (34 * visAnim), 255, 255, 255)
            );
            float textY = badgeLocalY + (badgeH - FontRenderer.getHeight(distFont)) * 0.5f;
            FontRenderer.drawCenteredText(
                    batch, distLabel,
                    bx + badgeW * 0.5f * totalScale,
                    scaleCardCoord(textY, smoothY, CARD_HEIGHT, cy, totalScale),
                    distFont * totalScale,
                    ColorUtil.withAlpha(0xFFD1D5DB, visAnim)
            );
        }

        String displayName = FontRenderer.getDefaultFont() != null
                ? FontRenderer.getDefaultFont().trimToWidth(snapName, nameMaxW, 14.0f, true)
                : snapName;
        FontRenderer.drawText(
                batch,
                displayName,
                scaleCardCoord(infoLocalX, smoothX, CARD_WIDTH, cx, totalScale),
                scaleCardCoord(smoothY + 9.5f, smoothY, CARD_HEIGHT, cy, totalScale),
                14.0f * totalScale,
                ColorUtil.withAlpha(0xFFFFFFFF, visAnim)
        );

        // Средняя строка: числовое HP ("? HP" при скрытом здоровье) + статус WIN/LOSE только при реальном HP
        float hpSize = 11.5f;
        float hpY = scaleCardCoord(smoothY + 27.5f, smoothY, CARD_HEIGHT, cy, totalScale);
        int textMutedCol = ColorUtil.withAlpha(0xFFD1D5DB, visAnim);

        if (snapHealthHidden) {
            FontRenderer.drawText(
                    batch,
                    "? HP",
                    scaleCardCoord(infoLocalX, smoothX, CARD_WIDTH, cx, totalScale),
                    hpY,
                    hpSize * totalScale,
                    textMutedCol
            );
        } else {
            String hpNumText = String.format(Locale.US, "%.1f", snapHealth);
            float curInfoLocalX = infoLocalX;

            FontRenderer.drawText(
                    batch,
                    hpNumText,
                    scaleCardCoord(curInfoLocalX, smoothX, CARD_WIDTH, cx, totalScale),
                    hpY,
                    hpSize * totalScale,
                    textMutedCol
            );
            curInfoLocalX += FontRenderer.getWidth(hpNumText, hpSize);

            if (module.showAbsorption.getValue() && snapAbsorption > 0.05f) {
                String absText = String.format(Locale.US, " +%.1f", snapAbsorption);
                FontRenderer.drawText(
                        batch,
                        absText,
                        scaleCardCoord(curInfoLocalX, smoothX, CARD_WIDTH, cx, totalScale),
                        hpY,
                        hpSize * totalScale,
                        ColorUtil.withAlpha(0xFFFBBF24, visAnim)
                );
                curInfoLocalX += FontRenderer.getWidth(absText, hpSize);
            }

            FontRenderer.drawText(
                    batch,
                    " HP",
                    scaleCardCoord(curInfoLocalX, smoothX, CARD_WIDTH, cx, totalScale),
                    hpY,
                    hpSize * totalScale,
                    textMutedCol
            );
        }

        if (module.showStatus.getValue() && !snapIsPreview && !snapHealthHidden) {
            float targetTotalHp = snapHealth + snapAbsorption;
            String statusText;
            int statusColor;
            if (Math.abs(snapSelfTotalHp - targetTotalHp) < 0.05f) {
                statusText = "DRAW";
                statusColor = 0xFFFBBF24;
            } else if (snapSelfTotalHp > targetTotalHp) {
                statusText = "WIN";
                statusColor = 0xFF34D399;
            } else {
                statusText = "LOSE";
                statusColor = 0xFFF87171;
            }
            float stFont = 9.5f;
            float stW = FontRenderer.getWidth(statusText, stFont) + 10.0f;
            float stH = 13.5f;
            float stX = scaleCardCoord(infoLocalRight - stW, smoothX, CARD_WIDTH, cx, totalScale);
            float stY = scaleCardCoord(smoothY + 27.0f, smoothY, CARD_HEIGHT, cy, totalScale);

            batch.drawRoundedRectWithOutline(
                    stX, stY, stW * totalScale, stH * totalScale, 4.5f * totalScale,
                    ColorUtil.withAlpha(statusColor, 0.16f * visAnim),
                    1.0f * totalScale,
                    ColorUtil.withAlpha(statusColor, 0.45f * visAnim)
            );
            float stTextY = smoothY + 27.0f + (stH - FontRenderer.getHeight(stFont)) * 0.5f;
            FontRenderer.drawCenteredText(
                    batch, statusText,
                    stX + stW * 0.5f * totalScale,
                    scaleCardCoord(stTextY, smoothY, CARD_HEIGHT, cy, totalScale),
                    stFont * totalScale,
                    ColorUtil.withAlpha(statusColor, visAnim)
            );
        }

        // Нижняя строка: чистая полоса здоровья (7px, radius 3.5px) в Accent Color + золотой сегмент Absorption (#FBBF24)
        float barX = scaleCardCoord(infoLocalX, smoothX, CARD_WIDTH, cx, totalScale);
        float barY = scaleCardCoord(smoothY + 45.0f, smoothY, CARD_HEIGHT, cy, totalScale);
        float barW = infoLocalW * totalScale;
        float barH = BAR_HEIGHT * totalScale;
        float barR = BAR_RADIUS * totalScale;

        batch.drawRoundedRect(barX, barY, barW, barH, barR,
                ColorUtil.argb((int) (28 * visAnim), 255, 255, 255));

        if (snapHealthHidden) {
            int placeholderLeft = ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFF6B7280, 0.45f), 0.52f * visAnim);
            int placeholderRight = ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFF9CA3AF, 0.35f), 0.35f * visAnim);
            batch.drawGradientRoundedRect(barX, barY, barW, barH, barR, placeholderLeft, placeholderRight, placeholderRight, placeholderLeft);
        } else {
            float fillW = Math.clamp(smoothHpRatio, 0.0f, 1.0f) * barW;
            if (fillW > 1.0f) {
                int leftCol = ColorUtil.withAlpha(accent, visAnim);
                int rightCol = ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFFFFFFFF, 0.18f), visAnim);
                batch.beginScissor(barX, barY, fillW, barH, canvasW, canvasH);
                batch.drawGradientRoundedRect(barX, barY, barW, barH, barR, leftCol, rightCol, rightCol, leftCol);
                batch.endScissor();
            }

            if (module.showAbsorption.getValue() && smoothAbsRatio > 0.005f) {
                float absW = Math.clamp(smoothAbsRatio, 0.0f, 1.0f) * barW;
                if (absW > 1.0f) {
                    float absStartX = Math.max(barX, Math.min(barX + fillW, barX + barW - absW));
                    int goldLeft = ColorUtil.withAlpha(0xFFF59E0B, visAnim);
                    int goldRight = ColorUtil.withAlpha(0xFFFBBF24, visAnim);
                    batch.beginScissor(absStartX, barY, absW, barH, canvasW, canvasH);
                    batch.drawGradientRoundedRect(barX, barY, barW, barH, barR, goldLeft, goldRight, goldRight, goldLeft);
                    batch.endScissor();
                }
            }
        }

        batch.end();
    }

    /**
     * Общий резолвер цели с анти-NPC фильтрацией и таймаутами (1.5s aim / 3.0s combat)
     * для переиспользования в 3D-модулях (TargetESP) даже при выключенном TargetHUD.
     */
    public static PlayerEntity resolveTarget(MinecraftClient client, boolean onlyInCombat) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        PlayerEntity target = resolveCombatTarget(client);
        if (onlyInCombat && !inCombat) {
            return null;
        }
        return target;
    }

    private static PlayerEntity resolveCombatTarget(MinecraftClient client) {
        long now = System.nanoTime();
        if (client.player.getId() != lastTrackedPlayerId) {
            lastTrackedPlayerId = client.player.getId();
            lastProcessedAttackTime = client.player.getLastAttackTime();
            lastProcessedAttackedTime = client.player.getLastAttackedTime();
            activeTarget = null;
            inCombat = false;
            lastAimNs = 0L;
            lastCombatNs = 0L;
        }

        // 1. Исходящий удар (PvP -> 3.0 сек)
        int attackTime = client.player.getLastAttackTime();
        if (attackTime != lastProcessedAttackTime) {
            lastProcessedAttackTime = attackTime;
            LivingEntity attacking = client.player.getAttacking();
            if (isValidCombatTarget(client, attacking)) {
                activeTarget = (PlayerEntity) attacking;
                inCombat = true;
                lastCombatNs = now;
            }
        }

        // 2. Входящий урон от другого игрока (PvP -> 3.0 сек)
        int attackedTime = client.player.getLastAttackedTime();
        if (attackedTime != lastProcessedAttackedTime) {
            lastProcessedAttackedTime = attackedTime;
            LivingEntity attacker = client.player.getAttacker();
            if (isValidCombatTarget(client, attacker)) {
                activeTarget = (PlayerEntity) attacker;
                inCombat = true;
                lastCombatNs = now;
            }
        }

        // 3. Обычное наведение прицелом (без боя -> 1.5 сек после увода прицела)
        if (client.targetedEntity instanceof PlayerEntity aimed && isValidCombatTarget(client, aimed)) {
            if (aimed == activeTarget) {
                lastAimNs = now;
            } else {
                activeTarget = aimed;
                inCombat = false;
                lastAimNs = now;
                lastCombatNs = 0L;
            }
        }

        boolean combatAlive = inCombat && ((now - lastCombatNs) <= COMBAT_TIMEOUT_NS || EntityVisibilityUtil.isRecentlyHit(activeTarget));
        if (!combatAlive) {
            inCombat = false;
        }
        boolean aimAlive = (now - lastAimNs) <= AIM_TIMEOUT_NS;

        if (activeTarget != null && (!isValidCombatTarget(client, activeTarget) || (!combatAlive && !aimAlive))) {
            EntityVisibilityUtil.clearHitIfMatches(activeTarget);
            activeTarget = null;
            inCombat = false;
        }
        return activeTarget;
    }

    private static boolean isValidCombatTarget(MinecraftClient client, Entity entity) {
        return entity instanceof PlayerEntity target
                && isValidPlayer(target)
                && target.squaredDistanceTo(client.player) <= COMBAT_RANGE_SQ;
    }

    /**
     * Строгая анти-NPC фильтрация: проверяет локального игрока, Tab-лист, легитную видимость,
     * версию UUID (отсекает v2 NPC), форматирование/префикс имени и возраст сущности.
     */
    public static boolean isValidPlayer(PlayerEntity target) {
        if (target == null) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return false;
        if (target == client.player) return false;
        if (!EntityVisibilityUtil.canRenderVisualsOn(target)) {
            return false;
        }
        if (target.getEntityWorld() != client.world || target.isRemoved() || !target.isAlive()) {
            return false;
        }

        UUID uuid = target.getUuid();
        if (uuid == null || uuid.version() == 2) {
            return false;
        }

        if (client.getNetworkHandler() == null || client.getNetworkHandler().getPlayerListEntry(uuid) == null) {
            return false;
        }

        if (target.age < 5) {
            return false;
        }

        String name = target.getName() != null ? target.getName().getString() : "";
        return !name.isBlank()
                && name.indexOf('\u00A7') < 0
                && !name.startsWith("[NPC]")
                && !name.contains("[NPC]");
    }

    private static void updateSnapshot(MinecraftClient client, PlayerEntity target, boolean previewMode) {
        UUID uuid = target.getUuid();
        float health = Math.max(0.0f, target.getHealth());
        float maxHealth = Math.max(1.0f, target.getMaxHealth());
        float absorption = Math.max(0.0f, target.getAbsorptionAmount());

        if (lastSnapshotUuid == null || !lastSnapshotUuid.equals(uuid)) {
            lastSnapshotUuid = uuid;
            smoothHpRatio = Math.clamp(health / maxHealth, 0.0f, 1.0f);
            smoothAbsRatio = Math.clamp(absorption / maxHealth, 0.0f, 1.0f);
        }

        snapName = target.getName() != null ? target.getName().getString() : "Player";
        snapHealth = health;
        snapMaxHealth = maxHealth;
        snapAbsorption = absorption;
        snapIsPreview = previewMode || target == client.player;
        snapHealthHidden = !snapIsPreview && EntityVisibilityUtil.isHealthHidden(target);
        boolean targetInvisible = target.isInvisible() || (client.player != null && target.isInvisibleTo(client.player));
        snapHideDistance = !snapIsPreview && targetInvisible && !EntityVisibilityUtil.hasVisibleArmor(target);
        snapDistance = (snapIsPreview || snapHideDistance) ? 0.0f : target.distanceTo(client.player);
        snapSelfTotalHp = Math.max(0.0f, client.player.getHealth() + client.player.getAbsorptionAmount());

        if (target instanceof AbstractClientPlayerEntity clientPlayer && clientPlayer.getSkin() != null) {
            snapSkinId = clientPlayer.getSkin().body().texturePath();
        } else {
            snapSkinId = DefaultSkinHelper.getSkinTextures(uuid).body().texturePath();
        }
    }

    private static int resolveSkinGlId(MinecraftClient client, Identifier skinId) {
        if (skinId == null || client.getTextureManager() == null) {
            return 0;
        }
        AbstractTexture tex = client.getTextureManager().getTexture(skinId);
        if (tex != null && tex.getGlTexture() instanceof GlTexture glTex && !glTex.isClosed()) {
            return glTex.getGlId();
        }
        return 0;
    }

    private static float computeRawDelta() {
        long now = System.nanoTime();
        double dt = (now - lastTimeNs) / 1_000_000_000.0;
        if (lastTimeNs == 0L || dt <= 0.0 || dt > 0.1) {
            dt = 0.016;
        }
        lastTimeNs = now;
        return (float) Math.clamp(dt, 0.0001, 0.05);
    }

    private static float scaleCardCoord(float coord, float origin, float baseSize, float center, float totalScale) {
        return center + ((coord - origin) - baseSize * 0.5f) * totalScale;
    }
}
