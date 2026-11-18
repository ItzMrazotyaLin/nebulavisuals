package me.itzmr.nebulavisuals.render.hud;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.hud.CrosshairModule;
import me.itzmr.nebulavisuals.render.engine2d.NebulaRenderEngine;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
import me.itzmr.nebulavisuals.render.engine2d.batch.RenderBatch2D;
import me.itzmr.nebulavisuals.render.engine2d.screen.NebulaScreen;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Векторный рендерер кастомного прицела {@link CrosshairModule} через {@link RenderBatch2D}.
 */
public final class CrosshairRenderer {
    private static final int OUTLINE_COLOR = 0xE005050A;
    private static final int WHITE_COLOR = 0xFFF8FAFC;

    private static long lastTimeNs = 0L;
    private static float dynamicSpread = 0.0f;
    private static float targetHighlight = 0.0f;

    private CrosshairRenderer() {}

    public static void render(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || client.getWindow() == null) {
            return;
        }
        if (client.options.hudHidden || client.currentScreen != null) {
            return;
        }

        CrosshairModule module = ModuleManager.getCrosshairModule();
        if (module == null || !module.isEnabled()) {
            dynamicSpread = 0.0f;
            targetHighlight = 0.0f;
            return;
        }

        if (!client.options.getPerspective().isFirstPerson() && !module.showInThirdPerson.getValue()) {
            return;
        }

        if (!NebulaRenderEngine.isInitialized()) {
            NebulaRenderEngine.init();
        }

        float dt = computeDeltaTime();

        float targetSpread = 0.0f;
        if (module.dynamic.getValue()) {
            Vec3d vel = client.player.getVelocity();
            double horizSpeed = Math.hypot(vel.x, vel.z);
            float moveExpand = Math.clamp((float) horizSpeed / 0.22f, 0.0f, 1.0f) * 3.0f;
            float airExpand = client.player.isOnGround() ? 0.0f : 2.5f;
            float cooldown = Math.clamp(1.0f - client.player.getAttackCooldownProgress(0.5f), 0.0f, 1.0f);
            float attackExpand = cooldown * 4.0f;
            targetSpread = moveExpand + airExpand + attackExpand;
        }
        dynamicSpread = AnimationEngine.expLerp(dynamicSpread, targetSpread, 18.0f, dt);

        boolean targetingPlayer = client.targetedEntity instanceof PlayerEntity targetPlayer
                && TargetHudRenderer.isValidPlayer(targetPlayer);
        targetHighlight = AnimationEngine.expLerp(targetHighlight, targetingPlayer ? 1.0f : 0.0f, 20.0f, dt);

        int mainColor = resolveColor(module.colorMode.getValue());

        int fbW = Math.max(1, client.getWindow().getFramebufferWidth());
        int fbH = Math.max(1, client.getWindow().getFramebufferHeight());
        float uiScale = NebulaScreen.computeHudBaseScale(fbW, fbH);

        float rawLen = module.length.getValue().floatValue();
        float rawThickness = module.thickness.getValue().floatValue();
        float rawGap = module.gap.getValue().floatValue() + dynamicSpread;
        boolean useOutline = module.outline.getValue();
        String style = module.style.getValue();

        // Привязываем центр и размеры строго к целым физическим пикселям (1:1 framebuffer),
        // исключая субпиксельное сглаживание и разную толщину полосок.
        int lenPx = Math.max(1, Math.round(rawLen * uiScale));
        int tPx = Math.max(1, Math.round(rawThickness * uiScale));
        int gapPx = Math.max(0, Math.round(rawGap * uiScale));
        int outlinePx = Math.max(1, Math.round(uiScale));
        int startX = (fbW - tPx) / 2;
        int startY = (fbH - tPx) / 2;

        RenderBatch2D batch = NebulaRenderEngine.getBatch();
        batch.begin(fbW, fbH, 1.0f);

        switch (style) {
            case "Cross" -> drawCross(batch, startX, startY, lenPx, tPx, gapPx, outlinePx, useOutline, mainColor);
            case "Dot" -> {
                int dotPx = Math.max(2, Math.round((rawThickness + 1.0f) * uiScale));
                drawSharpSquare(batch, (fbW - dotPx) / 2, (fbH - dotPx) / 2, dotPx, outlinePx, useOutline, mainColor);
            }
            case "Circle" -> drawCircle(
                    batch,
                    Math.round(fbW * 0.5f),
                    Math.round(fbH * 0.5f),
                    Math.max(2.0f, Math.round((rawLen + dynamicSpread * 0.6f) * uiScale)),
                    tPx,
                    outlinePx,
                    useOutline,
                    mainColor
            );
            default -> { // Cross + Dot
                drawCross(batch, startX, startY, lenPx, tPx, gapPx, outlinePx, useOutline, mainColor);
                drawSharpSquare(batch, startX, startY, tPx, outlinePx, useOutline, mainColor);
            }
        }

        batch.end();
    }

    private static int resolveColor(String colorMode) {
        int accent = ClientSettings.getAccentColor();
        return switch (colorMode) {
            case "Always Accent" -> accent;
            case "White" -> WHITE_COLOR;
            default -> ColorUtil.lerp(WHITE_COLOR, accent, targetHighlight);
        };
    }

    private static void drawCross(RenderBatch2D batch, int startX, int startY,
                                  int length, int thickness, int gap, int outlinePx,
                                  boolean useOutline, int mainColor) {
        // Top, Bottom, Left, Right — строго идентичная целая толщина и длина, без SDF smoothstep
        drawSharpBar(batch, startX, startY - gap - length, thickness, length, outlinePx, useOutline, mainColor);
        drawSharpBar(batch, startX, startY + thickness + gap, thickness, length, outlinePx, useOutline, mainColor);
        drawSharpBar(batch, startX - gap - length, startY, length, thickness, outlinePx, useOutline, mainColor);
        drawSharpBar(batch, startX + thickness + gap, startY, length, thickness, outlinePx, useOutline, mainColor);
    }

    private static void drawSharpBar(RenderBatch2D batch, int x, int y, int w, int h,
                                     int outlinePx, boolean useOutline, int fillColor) {
        if (useOutline) {
            batch.drawRect(x - outlinePx, y - outlinePx, w + outlinePx * 2, h + outlinePx * 2, OUTLINE_COLOR);
        }
        batch.drawRect(x, y, w, h, fillColor);
    }

    private static void drawSharpSquare(RenderBatch2D batch, int x, int y, int size,
                                        int outlinePx, boolean useOutline, int mainColor) {
        if (useOutline) {
            batch.drawRect(x - outlinePx, y - outlinePx, size + outlinePx * 2, size + outlinePx * 2, OUTLINE_COLOR);
        }
        batch.drawRect(x, y, size, size, mainColor);
    }

    private static void drawCircle(RenderBatch2D batch, float cx, float cy,
                                   float radius, float thickness, float outlinePx,
                                   boolean useOutline, int mainColor) {
        if (useOutline) {
            float outerR = radius + outlinePx;
            batch.drawOutlineRoundedRect(cx - outerR, cy - outerR, outerR * 2.0f, outerR * 2.0f,
                    outerR, thickness + outlinePx * 2.0f, OUTLINE_COLOR);
        }
        batch.drawOutlineRoundedRect(cx - radius, cy - radius, radius * 2.0f, radius * 2.0f,
                radius, thickness, mainColor);
    }

    private static float computeDeltaTime() {
        long now = System.nanoTime();
        double dt = (now - lastTimeNs) / 1_000_000_000.0;
        if (lastTimeNs == 0L || dt <= 0.0 || dt > 0.1) {
            dt = 0.016;
        }
        lastTimeNs = now;
        return (float) Math.clamp(dt, 0.0001, 0.05);
    }
}
