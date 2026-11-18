package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.KillEffectsModule;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine3d.BlendMode;
import me.itzmr.nebulavisuals.render.engine3d.RenderBatch3D;
import me.itzmr.nebulavisuals.render.engine3d.SphereBatch3D;
import me.itzmr.nebulavisuals.util.EntityVisibilityUtil;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 3D-рендерер модуля {@link KillEffectsModule}:
 * поддерживает 5 стилизованных 3D-эффектов смерти существ без аллокаций в кадре:
 * {@code Soul Rise}, {@code Lightning}, {@code Particles Burst}, {@code Helix Vortex} и {@code Shockwave Nova}.
 */
public final class KillEffectsRenderer {
    private static final double TRIGGER_RANGE_SQ = 64.0 * 64.0;

    // --- Soul Rise ---
    private static final int MAX_SOULS = 24;
    private static final float SOUL_DURATION = 1.45f;
    private static final boolean[] SOUL_ACTIVE = new boolean[MAX_SOULS];
    private static final double[] SOUL_X = new double[MAX_SOULS];
    private static final double[] SOUL_Y = new double[MAX_SOULS];
    private static final double[] SOUL_Z = new double[MAX_SOULS];
    private static final float[] SOUL_HEIGHT = new float[MAX_SOULS];
    private static final long[] SOUL_START_NS = new long[MAX_SOULS];
    private static int nextSoulSlot = 0;

    // --- Lightning ---
    private static final int MAX_BOLTS = 16;
    private static final int BOLT_MAIN_SEGMENTS = 10;
    private static final int BOLT_BRANCH_SEGMENTS = 6;
    private static final int BOLT_TOTAL_SEGMENTS = BOLT_MAIN_SEGMENTS + BOLT_BRANCH_SEGMENTS;
    private static final float BOLT_DURATION = 0.62f;
    private static final boolean[] BOLT_ACTIVE = new boolean[MAX_BOLTS];
    private static final double[] BOLT_BASE_X = new double[MAX_BOLTS];
    private static final double[] BOLT_BASE_Y = new double[MAX_BOLTS];
    private static final double[] BOLT_BASE_Z = new double[MAX_BOLTS];
    private static final long[] BOLT_START_NS = new long[MAX_BOLTS];
    private static final double[] SEG_X1 = new double[MAX_BOLTS * BOLT_TOTAL_SEGMENTS];
    private static final double[] SEG_Y1 = new double[MAX_BOLTS * BOLT_TOTAL_SEGMENTS];
    private static final double[] SEG_Z1 = new double[MAX_BOLTS * BOLT_TOTAL_SEGMENTS];
    private static final double[] SEG_X2 = new double[MAX_BOLTS * BOLT_TOTAL_SEGMENTS];
    private static final double[] SEG_Y2 = new double[MAX_BOLTS * BOLT_TOTAL_SEGMENTS];
    private static final double[] SEG_Z2 = new double[MAX_BOLTS * BOLT_TOTAL_SEGMENTS];
    private static int nextBoltSlot = 0;

    // --- Particles Burst ---
    private static final int MAX_BURST_PARTICLES = 640;
    private static final boolean[] PARTICLE_ACTIVE = new boolean[MAX_BURST_PARTICLES];
    private static final double[] PARTICLE_X = new double[MAX_BURST_PARTICLES];
    private static final double[] PARTICLE_Y = new double[MAX_BURST_PARTICLES];
    private static final double[] PARTICLE_Z = new double[MAX_BURST_PARTICLES];
    private static final float[] PARTICLE_VX = new float[MAX_BURST_PARTICLES];
    private static final float[] PARTICLE_VY = new float[MAX_BURST_PARTICLES];
    private static final float[] PARTICLE_VZ = new float[MAX_BURST_PARTICLES];
    private static final float[] PARTICLE_AGE = new float[MAX_BURST_PARTICLES];
    private static final float[] PARTICLE_MAX_AGE = new float[MAX_BURST_PARTICLES];
    private static final float[] PARTICLE_RADIUS = new float[MAX_BURST_PARTICLES];
    private static final boolean[] PARTICLE_CORE_WHITE = new boolean[MAX_BURST_PARTICLES];
    private static int nextParticleSlot = 0;

    // --- Helix Vortex ---
    private static final int MAX_HELIX = 24;
    private static final float HELIX_DURATION = 1.45f;
    private static final boolean[] HELIX_ACTIVE = new boolean[MAX_HELIX];
    private static final double[] HELIX_X = new double[MAX_HELIX];
    private static final double[] HELIX_Y = new double[MAX_HELIX];
    private static final double[] HELIX_Z = new double[MAX_HELIX];
    private static final float[] HELIX_HEIGHT = new float[MAX_HELIX];
    private static final long[] HELIX_START_NS = new long[MAX_HELIX];
    private static int nextHelixSlot = 0;

    // --- Shockwave Nova ---
    private static final int MAX_NOVAS = 24;
    private static final float NOVA_DURATION = 1.15f;
    private static final boolean[] NOVA_ACTIVE = new boolean[MAX_NOVAS];
    private static final double[] NOVA_X = new double[MAX_NOVAS];
    private static final double[] NOVA_Y = new double[MAX_NOVAS];
    private static final double[] NOVA_Z = new double[MAX_NOVAS];
    private static final long[] NOVA_START_NS = new long[MAX_NOVAS];
    private static int nextNovaSlot = 0;

    private static final Set<Integer> DEAD_TRACKED = new HashSet<>();
    private static ClientWorld lastWorld = null;
    private static int tickCounter = 0;
    private static long lastFrameTimeNs = 0L;

    private KillEffectsRenderer() {}

    public static void onClientTick(MinecraftClient client) {
        KillEffectsModule module = ModuleManager.getKillEffectsModule();
        if (client == null || client.world == null || client.player == null || module == null || !module.isEnabled()) {
            DEAD_TRACKED.clear();
            lastWorld = client != null ? client.world : null;
            return;
        }

        if (lastWorld != client.world) {
            clearAll();
            lastWorld = client.world;
        }

        tickCounter++;
        boolean onlyPlayers = module.onlyPlayers.getValue();
        String mode = module.mode.getValue();
        long now = System.nanoTime();

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living == client.player || living.isSpectator()) {
                continue;
            }
            if (!EntityVisibilityUtil.canRenderVisualsOn(living)) {
                continue;
            }
            if (onlyPlayers && !(living instanceof PlayerEntity)) {
                continue;
            }
            if (living.squaredDistanceTo(client.player) > TRIGGER_RANGE_SQ) {
                continue;
            }

            if (!living.isAlive() || living.getHealth() <= 0.0f || living.deathTime > 0) {
                if (DEAD_TRACKED.add(living.getId())) {
                    spawnEffect(mode, living.getX(), living.getY(), living.getZ(), living.getHeight(), now);
                }
            } else {
                DEAD_TRACKED.remove(living.getId());
            }
        }

        if ((tickCounter & 31) == 0 && !DEAD_TRACKED.isEmpty()) {
            Iterator<Integer> it = DEAD_TRACKED.iterator();
            while (it.hasNext()) {
                Entity entity = client.world.getEntityById(it.next());
                if (entity == null || (entity.isAlive() && entity instanceof LivingEntity living && living.deathTime == 0 && living.getHealth() > 0.0f)) {
                    it.remove();
                }
            }
        }
    }

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        KillEffectsModule module = ModuleManager.getKillEffectsModule();
        if (module == null || !module.isEnabled()) {
            lastFrameTimeNs = 0L;
            return;
        }

        long now = System.nanoTime();
        float dt = lastFrameTimeNs == 0L ? 0.016f : Math.clamp((now - lastFrameTimeNs) * 1e-9f, 0.0f, 0.05f);
        lastFrameTimeNs = now;

        int accent = ClientSettings.getAccentColor();
        int accentBright = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.65f);

        renderSouls(spheres, batch3D, now, accent, accentBright);
        renderBolts(spheres, batch3D, now, accent, accentBright);
        renderBurstParticles(spheres, dt, accent, accentBright);
        renderHelices(spheres, batch3D, now, accent, accentBright);
        renderNovas(spheres, batch3D, now, accent, accentBright);
    }

    private static void spawnEffect(String mode, double x, double y, double z, float entityHeight, long now) {
        float h = Math.max(0.8f, entityHeight);
        switch (mode) {
            case "Lightning" -> spawnLightning(x, y, z, now);
            case "Particles Burst" -> spawnBurst(x, y + h * 0.52f, z);
            case "Helix Vortex" -> spawnHelix(x, y, z, h, now);
            case "Shockwave Nova" -> spawnNova(x, y, z, now);
            default -> spawnSoulRise(x, y, z, h, now);
        }
    }

    private static void spawnSoulRise(double x, double y, double z, float height, long now) {
        int slot = nextSoulSlot;
        nextSoulSlot = (nextSoulSlot + 1) % MAX_SOULS;
        SOUL_ACTIVE[slot] = true;
        SOUL_X[slot] = x;
        SOUL_Y[slot] = y;
        SOUL_Z[slot] = z;
        SOUL_HEIGHT[slot] = height;
        SOUL_START_NS[slot] = now;
    }

    private static void spawnLightning(double x, double y, double z, long now) {
        int slot = nextBoltSlot;
        nextBoltSlot = (nextBoltSlot + 1) % MAX_BOLTS;
        BOLT_ACTIVE[slot] = true;
        BOLT_BASE_X[slot] = x;
        BOLT_BASE_Y[slot] = y;
        BOLT_BASE_Z[slot] = z;
        BOLT_START_NS[slot] = now;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int baseSeg = slot * BOLT_TOTAL_SEGMENTS;
        double topHeight = 18.0;

        double prevX = x + rng.nextDouble(-1.3, 1.3);
        double prevY = y + topHeight;
        double prevZ = z + rng.nextDouble(-1.3, 1.3);

        int branchIdx = 0;
        for (int s = 0; s < BOLT_MAIN_SEGMENTS; s++) {
            float progress = (float) (s + 1) / BOLT_MAIN_SEGMENTS;
            double nextY = y + topHeight * (1.0 - progress);
            double jitter = (s == BOLT_MAIN_SEGMENTS - 1) ? 0.0 : (1.0 - progress * 0.40) * 0.92;
            double nextX = x + (s == BOLT_MAIN_SEGMENTS - 1 ? 0.0 : rng.nextDouble(-jitter, jitter));
            double nextZ = z + (s == BOLT_MAIN_SEGMENTS - 1 ? 0.0 : rng.nextDouble(-jitter, jitter));

            int idx = baseSeg + s;
            SEG_X1[idx] = prevX;
            SEG_Y1[idx] = prevY;
            SEG_Z1[idx] = prevZ;
            SEG_X2[idx] = nextX;
            SEG_Y2[idx] = nextY;
            SEG_Z2[idx] = nextZ;

            if ((s == 2 || s == 5 || s == 7) && branchIdx < 3) {
                double bX1 = nextX;
                double bY1 = nextY;
                double bZ1 = nextZ;
                double dirX = rng.nextDouble(-1.4, 1.4);
                double dirZ = rng.nextDouble(-1.4, 1.4);
                for (int bs = 0; bs < 2; bs++) {
                    double bX2 = bX1 + dirX * (0.70 - bs * 0.15) + rng.nextDouble(-0.35, 0.35);
                    double bY2 = bY1 - rng.nextDouble(0.95, 1.75);
                    double bZ2 = bZ1 + dirZ * (0.70 - bs * 0.15) + rng.nextDouble(-0.35, 0.35);
                    int bSeg = baseSeg + BOLT_MAIN_SEGMENTS + branchIdx * 2 + bs;
                    SEG_X1[bSeg] = bX1;
                    SEG_Y1[bSeg] = bY1;
                    SEG_Z1[bSeg] = bZ1;
                    SEG_X2[bSeg] = bX2;
                    SEG_Y2[bSeg] = bY2;
                    SEG_Z2[bSeg] = bZ2;
                    bX1 = bX2;
                    bY1 = bY2;
                    bZ1 = bZ2;
                }
                branchIdx++;
            }

            prevX = nextX;
            prevY = nextY;
            prevZ = nextZ;
        }
    }

    private static void spawnBurst(double x, double y, double z) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < 42; i++) {
            int slot = nextParticleSlot;
            nextParticleSlot = (nextParticleSlot + 1) % MAX_BURST_PARTICLES;

            double theta = rng.nextDouble(0.0, Math.PI * 2.0);
            double phi = Math.acos(rng.nextDouble(-0.75, 1.0));
            float speed = (float) rng.nextDouble(2.6, 6.4);

            PARTICLE_ACTIVE[slot] = true;
            PARTICLE_X[slot] = x;
            PARTICLE_Y[slot] = y;
            PARTICLE_Z[slot] = z;
            PARTICLE_VX[slot] = (float) (Math.sin(phi) * Math.cos(theta) * speed);
            PARTICLE_VY[slot] = (float) (Math.cos(phi) * speed + 1.8f);
            PARTICLE_VZ[slot] = (float) (Math.sin(phi) * Math.sin(theta) * speed);
            PARTICLE_AGE[slot] = 0.0f;
            PARTICLE_MAX_AGE[slot] = (float) rng.nextDouble(0.75, 1.25);
            PARTICLE_RADIUS[slot] = (float) rng.nextDouble(0.055, 0.115);
            PARTICLE_CORE_WHITE[slot] = (i & 2) == 0;
        }
    }

    private static void spawnHelix(double x, double y, double z, float height, long now) {
        int slot = nextHelixSlot;
        nextHelixSlot = (nextHelixSlot + 1) % MAX_HELIX;
        HELIX_ACTIVE[slot] = true;
        HELIX_X[slot] = x;
        HELIX_Y[slot] = y;
        HELIX_Z[slot] = z;
        HELIX_HEIGHT[slot] = height;
        HELIX_START_NS[slot] = now;
    }

    private static void spawnNova(double x, double y, double z, long now) {
        int slot = nextNovaSlot;
        nextNovaSlot = (nextNovaSlot + 1) % MAX_NOVAS;
        NOVA_ACTIVE[slot] = true;
        NOVA_X[slot] = x;
        NOVA_Y[slot] = y;
        NOVA_Z[slot] = z;
        NOVA_START_NS[slot] = now;
    }

    private static void renderSouls(SphereBatch3D spheres, RenderBatch3D batch3D, long now, int accent, int accentBright) {
        for (int i = 0; i < MAX_SOULS; i++) {
            if (!SOUL_ACTIVE[i]) {
                continue;
            }
            float elapsed = (now - SOUL_START_NS[i]) * 1e-9f;
            if (elapsed < 0.0f || elapsed >= SOUL_DURATION) {
                SOUL_ACTIVE[i] = false;
                continue;
            }

            float t = elapsed / SOUL_DURATION;
            float fade = t < 0.14f ? (t / 0.14f) : (1.0f - (t - 0.14f) / 0.86f);
            fade = Math.clamp(fade, 0.0f, 1.0f);
            float ease = 1.0f - (float) Math.pow(1.0f - t, 2.2);

            double x = SOUL_X[i];
            double baseY = SOUL_Y[i];
            double z = SOUL_Z[i];
            float climb = ease * (SOUL_HEIGHT[i] + 2.2f);
            double y = baseY + climb;

            // Яркий поднимающийся полупрозрачный столб света в Accent Color
            float beamRadius = 0.34f + 0.16f * (1.0f - t);
            batch3D.drawVerticalGlowRing(
                    x, baseY + 0.02, z,
                    beamRadius,
                    2.6f + climb * 0.6f,
                    ColorUtil.withAlpha(accent, fade * 0.42f),
                    ColorUtil.withAlpha(accent, 0.0f),
                    28,
                    BlendMode.ADDITIVE
            );
            batch3D.drawVerticalGlowRing(
                    x, baseY + 0.02, z,
                    beamRadius * 0.55f,
                    2.0f + climb * 0.5f,
                    ColorUtil.withAlpha(accentBright, fade * 0.46f),
                    ColorUtil.withAlpha(accent, 0.0f),
                    24,
                    BlendMode.ADDITIVE
            );

            // Несколько расширяющихся спиральных 3D-колец
            for (int ringIdx = 0; ringIdx < 3; ringIdx++) {
                float rt = Math.clamp((t - ringIdx * 0.14f) / 0.72f, 0.0f, 1.0f);
                if (rt <= 0.0f || rt >= 1.0f) {
                    continue;
                }
                float rAlpha = (1.0f - rt) * fade;
                float ringR = 0.36f + rt * (0.58f + ringIdx * 0.12f);
                double ry = baseY + climb * (0.55 + ringIdx * 0.22);
                float rot = t * 4.5f + ringIdx * 1.1f;

                batch3D.drawHaloRing(
                        x, ry, z,
                        ringR, 0.14f,
                        ColorUtil.withAlpha(accent, rAlpha * 0.65f),
                        ColorUtil.withAlpha(accent, 0.0f),
                        32, rot, BlendMode.ADDITIVE
                );
                batch3D.drawCircleOutline(x, ry, z, ringR, ColorUtil.withAlpha(accentBright, rAlpha * 0.92f), 32, rot, 2.0f, BlendMode.ADDITIVE);
            }

            // Восходящие светящиеся сферы
            for (int k = 0; k < 7; k++) {
                float orbProgress = Math.max(0.0f, t - k * 0.042f);
                double angle = orbProgress * Math.PI * 5.6 + k * 0.90;
                double orbitR = 0.48 * (1.0 - k * 0.06);
                double oy = y - k * 0.18;
                double ox = x + Math.cos(angle) * orbitR;
                double oz = z + Math.sin(angle) * orbitR;
                float orbSize = (0.085f - k * 0.008f) * (0.45f + 0.55f * fade);
                int orbColor = ColorUtil.lerp(accentBright, accent, k / 6.0f);
                spheres.drawSphere(ox, oy, oz, orbSize, ColorUtil.withAlpha(orbColor, fade * 0.95f), 1.8f, BlendMode.ADDITIVE);
            }
        }
    }

    private static void renderBolts(SphereBatch3D spheres, RenderBatch3D batch3D, long now, int accent, int accentBright) {
        for (int i = 0; i < MAX_BOLTS; i++) {
            if (!BOLT_ACTIVE[i]) {
                continue;
            }
            float elapsed = (now - BOLT_START_NS[i]) * 1e-9f;
            if (elapsed < 0.0f || elapsed >= BOLT_DURATION) {
                BOLT_ACTIVE[i] = false;
                continue;
            }

            float t = elapsed / BOLT_DURATION;
            float fade = (float) Math.pow(1.0f - t, 1.55);
            float flicker = 0.78f + 0.22f * (float) Math.sin(elapsed * 105.0f);
            float alpha = Math.clamp(fade * flicker, 0.0f, 1.0f);

            // Многослойная молния: широкая аура -> насыщенный Accent -> чистое белое ядро
            int auraColor = ColorUtil.withAlpha(accent, alpha * 0.26f);
            int glowColor = ColorUtil.withAlpha(accent, alpha * 0.72f);
            int coreColor = ColorUtil.withAlpha(0xFFFFFFFF, alpha);

            int baseSeg = i * BOLT_TOTAL_SEGMENTS;
            for (int s = 0; s < BOLT_MAIN_SEGMENTS; s++) {
                int idx = baseSeg + s;
                batch3D.drawLine(SEG_X1[idx], SEG_Y1[idx], SEG_Z1[idx], SEG_X2[idx], SEG_Y2[idx], SEG_Z2[idx], auraColor, auraColor, 10.5f, BlendMode.ADDITIVE);
                batch3D.drawLine(SEG_X1[idx], SEG_Y1[idx], SEG_Z1[idx], SEG_X2[idx], SEG_Y2[idx], SEG_Z2[idx], glowColor, glowColor, 5.4f, BlendMode.ADDITIVE);
                batch3D.drawLine(SEG_X1[idx], SEG_Y1[idx], SEG_Z1[idx], SEG_X2[idx], SEG_Y2[idx], SEG_Z2[idx], coreColor, coreColor, 2.2f, BlendMode.ADDITIVE);
            }
            for (int b = BOLT_MAIN_SEGMENTS; b < BOLT_TOTAL_SEGMENTS; b++) {
                int idx = baseSeg + b;
                batch3D.drawLine(SEG_X1[idx], SEG_Y1[idx], SEG_Z1[idx], SEG_X2[idx], SEG_Y2[idx], SEG_Z2[idx], ColorUtil.withAlpha(accent, alpha * 0.45f), ColorUtil.withAlpha(accent, alpha * 0.16f), 4.8f, BlendMode.ADDITIVE);
                batch3D.drawLine(SEG_X1[idx], SEG_Y1[idx], SEG_Z1[idx], SEG_X2[idx], SEG_Y2[idx], SEG_Z2[idx], ColorUtil.withAlpha(0xFFFFFFFF, alpha * 0.92f), ColorUtil.withAlpha(accentBright, alpha * 0.45f), 1.7f, BlendMode.ADDITIVE);
            }

            // Вспышка и двойная ударная волна-кольцо в точке удара
            double bx = BOLT_BASE_X[i];
            double by = BOLT_BASE_Y[i] + 0.03;
            double bz = BOLT_BASE_Z[i];
            float waveRadius = 0.28f + (1.0f - (1.0f - t) * (1.0f - t)) * 2.55f;
            batch3D.drawHaloRing(bx, by, bz, waveRadius, 0.24f, ColorUtil.withAlpha(accent, alpha * 0.78f), ColorUtil.withAlpha(accent, 0.0f), 40, 0.0f, BlendMode.ADDITIVE);
            batch3D.drawCircleOutline(bx, by + 0.002, bz, waveRadius, ColorUtil.withAlpha(0xFFFFFFFF, alpha * 0.88f), 40, 2.2f, BlendMode.ADDITIVE);
            if (t < 0.42f) {
                float flashScale = 1.0f - (t / 0.42f);
                spheres.drawSphere(bx, by + 0.16, bz, 0.56f * flashScale, ColorUtil.withAlpha(0xFFFFFFFF, flashScale * 0.95f), 2.2f, BlendMode.ADDITIVE);
            }
        }
    }

    private static void renderBurstParticles(SphereBatch3D spheres, float dt, int accent, int accentBright) {
        float drag = (float) Math.pow(0.24, dt);
        for (int i = 0; i < MAX_BURST_PARTICLES; i++) {
            if (!PARTICLE_ACTIVE[i]) {
                continue;
            }
            float age = PARTICLE_AGE[i] + dt;
            float maxAge = PARTICLE_MAX_AGE[i];
            if (age >= maxAge) {
                PARTICLE_ACTIVE[i] = false;
                continue;
            }
            PARTICLE_AGE[i] = age;

            PARTICLE_VY[i] -= 6.8f * dt;
            PARTICLE_VX[i] *= drag;
            PARTICLE_VY[i] *= drag;
            PARTICLE_VZ[i] *= drag;

            PARTICLE_X[i] += PARTICLE_VX[i] * dt;
            PARTICLE_Y[i] += PARTICLE_VY[i] * dt;
            PARTICLE_Z[i] += PARTICLE_VZ[i] * dt;

            float t = age / maxAge;
            float fade = 1.0f - (t * t);
            int startCol = PARTICLE_CORE_WHITE[i] ? 0xFFFFFFFF : accentBright;
            int color = ColorUtil.lerp(startCol, accent, Math.min(1.0f, t * 1.3f));
            spheres.drawSphere(
                    PARTICLE_X[i], PARTICLE_Y[i], PARTICLE_Z[i],
                    PARTICLE_RADIUS[i] * (0.45f + 0.60f * (1.0f - t)),
                    ColorUtil.withAlpha(color, fade * 0.98f),
                    1.9f,
                    BlendMode.ADDITIVE
            );
        }
    }

    private static void renderHelices(SphereBatch3D spheres, RenderBatch3D batch3D, long now, int accent, int accentBright) {
        final int steps = 28;
        final double totalHeight = 3.1;
        final double turns = Math.PI * 5.0;

        for (int i = 0; i < MAX_HELIX; i++) {
            if (!HELIX_ACTIVE[i]) {
                continue;
            }
            float elapsed = (now - HELIX_START_NS[i]) * 1e-9f;
            if (elapsed < 0.0f || elapsed >= HELIX_DURATION) {
                HELIX_ACTIVE[i] = false;
                continue;
            }

            float t = elapsed / HELIX_DURATION;
            float fade = t < 0.12f ? (t / 0.12f) : (1.0f - (t - 0.12f) / 0.88f);
            fade = Math.clamp(fade, 0.0f, 1.0f);

            double x = HELIX_X[i];
            double y = HELIX_Y[i] + 0.04;
            double z = HELIX_Z[i];
            float headFraction = Math.min(1.0f, t * 1.35f);
            double spinOffset = t * Math.PI * 3.5;

            int visibleSteps = Math.max(2, Math.round(steps * headFraction));
            double prevAx = x, prevAy = y, prevAz = z;
            double prevBx = x, prevAy2 = y, prevBz = z;
            boolean hasPrev = false;

            for (int s = 0; s <= visibleSteps; s++) {
                float frac = (float) s / steps;
                double radius = 0.62 * (1.0 - frac * 0.25);
                double angle = frac * turns + spinOffset;
                double py = y + frac * totalHeight;

                double ax = x + Math.cos(angle) * radius;
                double az = z + Math.sin(angle) * radius;
                double bx = x - Math.cos(angle) * radius;
                double bz = z - Math.sin(angle) * radius;

                if (hasPrev) {
                    float segFade = fade * (0.35f + 0.65f * (1.0f - Math.abs(headFraction - frac)));
                    int glowA = ColorUtil.withAlpha(accent, segFade * 0.45f);
                    int coreA = ColorUtil.withAlpha(accentBright, segFade * 0.95f);
                    int glowB = ColorUtil.withAlpha(accentBright, segFade * 0.45f);
                    int coreB = ColorUtil.withAlpha(0xFFFFFFFF, segFade * 0.90f);

                    batch3D.drawLine(prevAx, prevAy, prevAz, ax, py, az, glowA, glowA, 5.2f, BlendMode.ADDITIVE);
                    batch3D.drawLine(prevAx, prevAy, prevAz, ax, py, az, coreA, coreA, 2.0f, BlendMode.ADDITIVE);

                    batch3D.drawLine(prevBx, prevAy2, prevBz, bx, py, bz, glowB, glowB, 5.2f, BlendMode.ADDITIVE);
                    batch3D.drawLine(prevBx, prevAy2, prevBz, bx, py, bz, coreB, coreB, 2.0f, BlendMode.ADDITIVE);

                    // Поперечные ДНК-мостики на каждом 4-м шаге
                    if ((s & 3) == 0) {
                        int linkCol = ColorUtil.withAlpha(accent, segFade * 0.42f);
                        batch3D.drawLine(ax, py, az, bx, py, bz, linkCol, linkCol, 1.6f, BlendMode.ADDITIVE);
                    }
                }

                prevAx = ax;
                prevAy = py;
                prevAz = az;
                prevBx = bx;
                prevAy2 = py;
                prevBz = bz;
                hasPrev = true;
            }

            // Две ведущие светящиеся сферы на концах ДНК-спирали
            spheres.drawSphere(prevAx, prevAy, prevAz, 0.11f * fade, ColorUtil.withAlpha(0xFFFFFFFF, fade), 2.0f, BlendMode.ADDITIVE);
            spheres.drawSphere(prevBx, prevAy2, prevBz, 0.11f * fade, ColorUtil.withAlpha(accentBright, fade), 2.0f, BlendMode.ADDITIVE);

            batch3D.drawHaloRing(x, y, z, 0.58f + t * 0.35f, 0.16f, ColorUtil.withAlpha(accent, fade * 0.55f), ColorUtil.withAlpha(accent, 0.0f), 32, (float) spinOffset, BlendMode.ADDITIVE);
        }
    }

    private static void renderNovas(SphereBatch3D spheres, RenderBatch3D batch3D, long now, int accent, int accentBright) {
        for (int i = 0; i < MAX_NOVAS; i++) {
            if (!NOVA_ACTIVE[i]) {
                continue;
            }
            float elapsed = (now - NOVA_START_NS[i]) * 1e-9f;
            if (elapsed < 0.0f || elapsed >= NOVA_DURATION) {
                NOVA_ACTIVE[i] = false;
                continue;
            }

            float t = elapsed / NOVA_DURATION;
            float ease = 1.0f - (float) Math.pow(1.0f - t, 3.0);
            float fade = (float) Math.pow(1.0f - t, 1.45);

            double x = NOVA_X[i];
            double y = NOVA_Y[i] + 0.03;
            double z = NOVA_Z[i];

            // Основная мощная ударная волна по земле
            float waveRadius = 0.25f + ease * 3.6f;
            batch3D.drawHaloRing(x, y, z, waveRadius, 0.34f, ColorUtil.withAlpha(accent, fade * 0.85f), ColorUtil.withAlpha(accent, 0.0f), 48, 0.0f, BlendMode.ADDITIVE);
            batch3D.drawRing(x, y + 0.002, z, Math.max(0.05f, waveRadius - 0.14f), waveRadius, ColorUtil.withAlpha(accent, 0.0f), ColorUtil.withAlpha(accentBright, fade * 0.92f), 48, 0.0f, BlendMode.ADDITIVE);
            batch3D.drawCircleOutline(x, y + 0.003, z, waveRadius, ColorUtil.withAlpha(0xFFFFFFFF, fade * 0.95f), 48, 2.4f, BlendMode.ADDITIVE);

            // Вторичная запаздывающая ударная волна
            if (t > 0.14f) {
                float t2 = (t - 0.14f) / 0.86f;
                float ease2 = 1.0f - (float) Math.pow(1.0f - t2, 2.6);
                float fade2 = (1.0f - t2);
                float wave2 = 0.20f + ease2 * 2.4f;
                batch3D.drawHaloRing(x, y + 0.004, z, wave2, 0.20f, ColorUtil.withAlpha(accentBright, fade2 * 0.65f), ColorUtil.withAlpha(accent, 0.0f), 40, 0.0f, BlendMode.ADDITIVE);
                batch3D.drawCircleOutline(x, y + 0.005, z, wave2, ColorUtil.withAlpha(accentBright, fade2 * 0.80f), 40, 1.8f, BlendMode.ADDITIVE);
            }

            // Вертикальный световой луч в момент убийства
            float beamRadius = 0.42f * (1.0f - t * 0.55f);
            float beamAlpha = (float) Math.pow(1.0f - t, 1.8);
            batch3D.drawVerticalGlowRing(x, y, z, beamRadius, 7.2f * (1.0f - t * 0.25f), ColorUtil.withAlpha(accent, beamAlpha * 0.68f), ColorUtil.withAlpha(accent, 0.0f), 32, BlendMode.ADDITIVE);
            batch3D.drawVerticalGlowRing(x, y, z, beamRadius * 0.42f, 6.4f * (1.0f - t * 0.25f), ColorUtil.withAlpha(0xFFFFFFFF, beamAlpha * 0.72f), ColorUtil.withAlpha(accentBright, 0.0f), 24, BlendMode.ADDITIVE);

            if (t < 0.35f) {
                float flash = 1.0f - (t / 0.35f);
                spheres.drawSphere(x, y + 0.22, z, 0.55f * flash, ColorUtil.withAlpha(0xFFFFFFFF, flash), 2.2f, BlendMode.ADDITIVE);
            }
        }
    }

    private static void clearAll() {
        DEAD_TRACKED.clear();
        for (int i = 0; i < MAX_SOULS; i++) SOUL_ACTIVE[i] = false;
        for (int i = 0; i < MAX_BOLTS; i++) BOLT_ACTIVE[i] = false;
        for (int i = 0; i < MAX_BURST_PARTICLES; i++) PARTICLE_ACTIVE[i] = false;
        for (int i = 0; i < MAX_HELIX; i++) HELIX_ACTIVE[i] = false;
        for (int i = 0; i < MAX_NOVAS; i++) NOVA_ACTIVE[i] = false;
    }
}
