package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.TargetEspModule;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine3d.BlendMode;
import me.itzmr.nebulavisuals.render.engine3d.Camera3D;
import me.itzmr.nebulavisuals.render.engine3d.RenderBatch3D;
import me.itzmr.nebulavisuals.render.engine3d.SphereBatch3D;
import me.itzmr.nebulavisuals.render.hud.TargetHudRenderer;
import me.itzmr.nebulavisuals.util.EntityVisibilityUtil;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Высокопроизводительный 3D-рендерер модуля {@link TargetEspModule}
 * с режимами Orbs, Ring, Spirals, Halo и Frame без аллокаций в кадре.
 */
public final class TargetEspRenderer {
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float PI = (float) Math.PI;
    private static final int ORB_COUNT = 4;
    private static final int ORB_TRAIL_STEPS = 8;
    private static final int SPIRAL_POINTS = 48;

    private static final double[] SPIRAL_XS = new double[SPIRAL_POINTS];
    private static final double[] SPIRAL_YS = new double[SPIRAL_POINTS];
    private static final double[] SPIRAL_ZS = new double[SPIRAL_POINTS];
    private static final int[] SPIRAL_COLORS = new int[SPIRAL_POINTS];
    private static final float[] SPIRAL_WIDTHS = new float[SPIRAL_POINTS];

    private static long lastTimeNs = 0L;
    private static float animTime = 0.0f;
    private static float fadeAlpha = 0.0f;
    private static float hurtAnim = 0.0f;

    private static double cachedX = 0.0;
    private static double cachedY = 0.0;
    private static double cachedZ = 0.0;
    private static float cachedWidth = 0.6f;
    private static float cachedHeight = 1.8f;
    private static UUID lastTargetUuid = null;

    private TargetEspRenderer() {}

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        MinecraftClient client = MinecraftClient.getInstance();
        TargetEspModule module = ModuleManager.getTargetEspModule();
        if (client == null || client.world == null || client.player == null) {
            fadeAlpha = 0.0f;
            hurtAnim = 0.0f;
            lastTimeNs = 0L;
            return;
        }

        long now = System.nanoTime();
        float dt = lastTimeNs == 0L ? 0.016f : Math.clamp((now - lastTimeNs) * 1e-9f, 0.0f, 0.05f);
        lastTimeNs = now;

        boolean enabled = module != null && module.isEnabled();
        PlayerEntity target = enabled ? TargetHudRenderer.resolveTarget(client, module.onlyInCombat.getValue()) : null;
        if (target != null && !EntityVisibilityUtil.canRenderVisualsOn(target)) {
            target = null;
        }
        if (target == null && lastTargetUuid != null) {
            PlayerEntity prev = client.world.getPlayerByUuid(lastTargetUuid);
            if (prev != null && !EntityVisibilityUtil.canRenderVisualsOn(prev)) {
                fadeAlpha = 0.0f;
                hurtAnim = 0.0f;
                lastTargetUuid = null;
                return;
            }
        }

        if (target != null) {
            lastTargetUuid = target.getUuid();
            Vec3d pos = target.getLerpedPos(Camera3D.getTickProgress());
            cachedX = pos.x;
            cachedY = pos.y;
            cachedZ = pos.z;
            cachedWidth = target.getWidth();
            cachedHeight = target.getHeight();
            fadeAlpha += (1.0f - fadeAlpha) * Math.min(1.0f, dt * 11.0f);

            float targetHurt = target.hurtTime > 0
                    ? Math.clamp(target.hurtTime / (float) Math.max(1, target.maxHurtTime), 0.0f, 1.0f)
                    : 0.0f;
            float hurtRate = targetHurt > hurtAnim ? 24.0f : 10.0f;
            hurtAnim += (targetHurt - hurtAnim) * Math.min(1.0f, dt * hurtRate);
        } else {
            fadeAlpha += (0.0f - fadeAlpha) * Math.min(1.0f, dt * 9.0f);
            hurtAnim += (0.0f - hurtAnim) * Math.min(1.0f, dt * 10.0f);
        }

        if (fadeAlpha <= 0.005f || cachedHeight <= 0.0f || module == null) {
            if (fadeAlpha <= 0.005f) {
                lastTargetUuid = null;
            }
            return;
        }

        float speed = module.speed.getValue().floatValue();
        float size = module.size.getValue().floatValue();
        animTime += dt * speed;

        float eased = 1.0f - (float) Math.pow(1.0f - fadeAlpha, 3.0);
        float scaleAnim = 0.65f + 0.35f * eased;

        int accent = ClientSettings.getAccentColor();
        int accentBright = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.38f);
        int accentSecondary = ClientSettings.getAccentHoverColor();

        switch (module.mode.getValue()) {
            case "Ring" -> renderRingMode(batch3D, scaleAnim, size, accent, accentBright);
            case "Spirals" -> renderSpiralsMode(batch3D, scaleAnim, size, accent, accentBright, accentSecondary);
            case "Halo" -> renderHaloMode(batch3D, scaleAnim, size, accent, accentBright, accentSecondary);
            case "Frame" -> renderFrameMode(batch3D, scaleAnim, size, accent, accentBright);
            default -> renderOrbsMode(spheres, scaleAnim, size, accent, accentBright, accentSecondary);
        }
    }

    private static void renderOrbsMode(SphereBatch3D spheres, float scaleAnim, float size,
                                       int accent, int accentBright, int accentSecondary) {
        float orbitRadius = Math.max(0.45f, cachedWidth * 0.88f) * size * scaleAnim;
        double baseY = cachedY + cachedHeight * 0.52;
        double ampY = cachedHeight * 0.36;

        float baseAngle = animTime * 2.25f;
        float baseWave = animTime * 2.8f;

        for (int i = 0; i < ORB_COUNT; i++) {
            float phaseOffset = i * (TWO_PI / ORB_COUNT);
            float waveOffset = i * 1.5707964f;

            for (int t = 0; t < ORB_TRAIL_STEPS; t++) {
                float u = 1.0f - (t / (float) ORB_TRAIL_STEPS);
                float lag = t * 0.055f;

                float angle = baseAngle + phaseOffset - lag * 2.25f;
                double ox = cachedX + Math.cos(angle) * orbitRadius;
                double oz = cachedZ + Math.sin(angle) * orbitRadius;
                double oy = baseY + Math.sin(baseWave + waveOffset - lag * 2.8f) * ampY;

                float radius;
                float alpha;
                float glow;
                int orbColor;

                if (t == 0) {
                    radius = 0.068f * size * scaleAnim;
                    alpha = fadeAlpha * 0.95f;
                    glow = 1.0f;
                    orbColor = ColorUtil.lerp(accent, accentBright, 0.45f);
                } else {
                    radius = 0.058f * size * scaleAnim * (0.28f + 0.72f * u);
                    alpha = fadeAlpha * (u * u) * 0.55f;
                    glow = 0.9f;
                    orbColor = ColorUtil.lerp(accentSecondary, accentBright, u * 0.65f);
                }

                spheres.drawSphere(
                        ox, oy, oz,
                        radius,
                        ColorUtil.withAlpha(orbColor, alpha),
                        glow,
                        BlendMode.ADDITIVE
                );
            }
        }
    }

    private static void renderRingMode(RenderBatch3D batch3D, float scaleAnim, float size,
                                       int accent, int accentBright) {
        double scanHeight = Math.max(0.35, cachedHeight - 0.16);
        float scanPhase = animTime * 2.5f;
        double scan = 0.5 + 0.5 * Math.sin(scanPhase);
        double ringY = cachedY + 0.08 + scan * scanHeight;

        float pulse = 1.0f + 0.05f * (float) Math.sin(animTime * 4.5f);
        float radius = Math.max(0.44f, cachedWidth * 0.84f) * size * scaleAnim * pulse;

        // Узкий вертикальный градиентный шлейф (не более 0.15 - 0.22 блока), тянущийся за направлением скана
        float maxGlowHeight = Math.clamp(0.16f * size, 0.14f, 0.22f) * scaleAnim;
        float glowHeight = -(float) Math.cos(scanPhase) * maxGlowHeight;

        if (Math.abs(glowHeight) > 0.01f) {
            batch3D.drawVerticalGlowRing(
                    cachedX, ringY, cachedZ,
                    radius,
                    glowHeight,
                    ColorUtil.withAlpha(accent, fadeAlpha * 0.55f),
                    ColorUtil.withAlpha(accent, 0.0f),
                    64,
                    BlendMode.ADDITIVE
            );
        }

        // Один чёткий тонкий горизонтальный контур без дублирующего кольца
        batch3D.drawCircleOutline(
                cachedX, ringY, cachedZ,
                radius,
                ColorUtil.withAlpha(accentBright, fadeAlpha * 0.95f),
                64,
                2.0f,
                BlendMode.ADDITIVE
        );
    }

    private static void renderSpiralsMode(RenderBatch3D batch3D, float scaleAnim, float size,
                                          int accent, int accentBright, int accentSecondary) {
        float radius = Math.max(0.45f, cachedWidth * 0.86f) * size * scaleAnim;
        double minY = cachedY + 0.10;
        double ampY = Math.max(0.30, cachedHeight - 0.22);
        float headPhase = animTime * 2.8f;

        for (int h = 0; h < 2; h++) {
            float phaseShift = h * PI;
            for (int i = 0; i < SPIRAL_POINTS; i++) {
                float u = i / (float) (SPIRAL_POINTS - 1); // 0 = хвост, 1 = голова ленты
                float tau = headPhase - (1.0f - u) * 3.2f;
                float angle = tau + phaseShift;

                SPIRAL_XS[i] = cachedX + Math.cos(angle) * radius;
                SPIRAL_ZS[i] = cachedZ + Math.sin(angle) * radius;
                SPIRAL_YS[i] = minY + (0.5 + 0.5 * Math.sin(tau * 0.85f + phaseShift)) * ampY;

                float tailFade = u * u;
                int baseColor = ColorUtil.lerp(accentSecondary, u > 0.8f ? accentBright : accent, u);
                SPIRAL_COLORS[i] = ColorUtil.withAlpha(baseColor, fadeAlpha * tailFade * 0.92f);
                SPIRAL_WIDTHS[i] = (0.008f + 0.052f * u) * size * scaleAnim;
            }

            batch3D.drawTrail(SPIRAL_XS, SPIRAL_YS, SPIRAL_ZS, SPIRAL_COLORS, SPIRAL_WIDTHS, SPIRAL_POINTS, BlendMode.ADDITIVE);
        }
    }

    private static void renderHaloMode(RenderBatch3D batch3D, float scaleAnim, float size,
                                       int accent, int accentBright, int accentSecondary) {
        // Размещаем ауру строго под ногами цели (tickDelta-интерполированный Y + 0.04 без перекрытия корпуса/головы)
        double haloY = cachedY + 0.04 + 0.015 * Math.sin(animTime * 2.6f);
        float pulse = 1.0f + 0.07f * (float) Math.sin(animTime * 3.0f);
        float innerRadius = Math.max(0.42f, cachedWidth * 0.78f) * size * scaleAnim * pulse;

        // Мягкое наземное свечение-подложка и чёткий внутренний контур вокруг ног цели
        batch3D.drawHaloRing(
                cachedX, haloY - 0.004, cachedZ,
                innerRadius,
                0.14f * size,
                ColorUtil.withAlpha(accent, fadeAlpha * 0.42f),
                ColorUtil.withAlpha(accent, 0.0f),
                56,
                BlendMode.ADDITIVE
        );
        batch3D.drawRing(
                cachedX, haloY, cachedZ,
                Math.max(0.05f, innerRadius - 0.04f * size),
                innerRadius,
                ColorUtil.withAlpha(accent, 0.0f),
                ColorUtil.withAlpha(accentBright, fadeAlpha * 0.85f),
                56,
                BlendMode.ADDITIVE
        );
        batch3D.drawCircleOutline(
                cachedX, haloY + 0.002, cachedZ,
                innerRadius,
                ColorUtil.withAlpha(accentBright, fadeAlpha * 0.95f),
                56,
                1.8f,
                BlendMode.ADDITIVE
        );

        // Внешняя вращающаяся корона из 6 сегментов у земли
        int arcCount = 6;
        float step = TWO_PI / arcCount;
        float sweep = step * 0.56f;
        float rot = animTime * 1.45f;
        float outerRadius = innerRadius * 1.18f;
        int arcBandInner = ColorUtil.withAlpha(accentSecondary, 0.0f);
        int arcBandOuter = ColorUtil.withAlpha(accent, fadeAlpha * 0.68f);
        int arcLineColor = ColorUtil.withAlpha(accentBright, fadeAlpha * 0.9f);

        for (int i = 0; i < arcCount; i++) {
            float start = rot + i * step;
            batch3D.drawArcRing(
                    cachedX, haloY + 0.006, cachedZ,
                    Math.max(0.05f, outerRadius - 0.035f * size),
                    outerRadius,
                    start,
                    sweep,
                    arcBandInner,
                    arcBandOuter,
                    10,
                    BlendMode.ADDITIVE
            );
            batch3D.drawArcOutline(
                    cachedX, haloY + 0.008, cachedZ,
                    outerRadius,
                    start,
                    sweep,
                    arcLineColor,
                    10,
                    1.6f,
                    BlendMode.ADDITIVE
            );
        }
    }

    private static void renderFrameMode(RenderBatch3D batch3D, float scaleAnim, float size,
                                        int accent, int accentBright) {
        float compressXZ = 1.0f - hurtAnim * 0.16f;
        double compressY = hurtAnim * 0.08 * cachedHeight;

        double halfW = Math.max(0.32f, cachedWidth * 0.62f) * size * scaleAnim * compressXZ;
        double minX = cachedX - halfW;
        double maxX = cachedX + halfW;
        double minZ = cachedZ - halfW;
        double maxZ = cachedZ + halfW;
        double minY = cachedY + compressY;
        double maxY = cachedY + cachedHeight * (0.85 + 0.15 * scaleAnim) - compressY;

        double armXZ = halfW * 0.36;
        double armY = Math.min(0.28, (maxY - minY) * 0.22);

        int baseColor = ColorUtil.lerp(accent, accentBright, 0.25f + 0.75f * hurtAnim);
        int lineColor = ColorUtil.withAlpha(baseColor, fadeAlpha * 0.95f);
        float lineWidth = 2.0f + hurtAnim * 0.8f;

        drawCornerBracket(batch3D, minX, minY, minZ, +armXZ, +armY, +armXZ, lineColor, lineWidth);
        drawCornerBracket(batch3D, maxX, minY, minZ, -armXZ, +armY, +armXZ, lineColor, lineWidth);
        drawCornerBracket(batch3D, minX, minY, maxZ, +armXZ, +armY, -armXZ, lineColor, lineWidth);
        drawCornerBracket(batch3D, maxX, minY, maxZ, -armXZ, +armY, -armXZ, lineColor, lineWidth);

        drawCornerBracket(batch3D, minX, maxY, minZ, +armXZ, -armY, +armXZ, lineColor, lineWidth);
        drawCornerBracket(batch3D, maxX, maxY, minZ, -armXZ, -armY, +armXZ, lineColor, lineWidth);
        drawCornerBracket(batch3D, minX, maxY, maxZ, +armXZ, -armY, -armXZ, lineColor, lineWidth);
        drawCornerBracket(batch3D, maxX, maxY, maxZ, -armXZ, -armY, -armXZ, lineColor, lineWidth);
    }

    private static void drawCornerBracket(RenderBatch3D batch3D,
                                          double x, double y, double z,
                                          double dx, double dy, double dz,
                                          int argb, float lineWidth) {
        batch3D.drawLine(x, y, z, x + dx, y, z, argb, argb, lineWidth, BlendMode.ADDITIVE);
        batch3D.drawLine(x, y, z, x, y + dy, z, argb, argb, lineWidth, BlendMode.ADDITIVE);
        batch3D.drawLine(x, y, z, x, y, z + dz, argb, argb, lineWidth, BlendMode.ADDITIVE);
    }
}
