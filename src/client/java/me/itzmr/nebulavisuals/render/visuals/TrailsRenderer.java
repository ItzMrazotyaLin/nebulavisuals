package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.TrailsModule;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine3d.BlendMode;
import me.itzmr.nebulavisuals.render.engine3d.Camera3D;
import me.itzmr.nebulavisuals.render.engine3d.RenderBatch3D;
import me.itzmr.nebulavisuals.render.engine3d.SphereBatch3D;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.math.Vec3d;

/**
 * 3D-рендерер модуля {@link TrailsModule} с неизменяемыми мировыми точками ({@code worldX/Y/Z},
 * {@code spawnTimeNs}, {@code accumulatedDistance}) и без аллокаций в кадре.
 */
public final class TrailsRenderer {
    private static final int MAX_PLAYER_POINTS = 512;
    private static final int MAX_PROJECTILES = 32;
    private static final int MAX_PROJ_POINTS = 96;

    // Минимальный шаг записи точки для игрока (0.10 блока -> без спама на месте и с жёсткой фиксацией в мире)
    private static final double MIN_PLAYER_STEP = 0.10;
    private static final double MIN_PLAYER_STEP_SQ = MIN_PLAYER_STEP * MIN_PLAYER_STEP;
    private static final double TELEPORT_RESET_DIST_SQ = 64.0;
    private static final double MIN_PROJ_STEP_SQ = 0.04 * 0.04;

    private static final double FIRST_PERSON_CLIP_DIST = 0.4;
    private static final double FIRST_PERSON_FADE_END = 0.72;
    private static final double ORB_FIRST_PERSON_DIST_SQ = 0.5 * 0.5;
    private static final double HELIX_PHASE_PER_BLOCK = 4.5;
    private static final float PI = (float) Math.PI;

    private static final TrailRing PLAYER_TRAIL = new TrailRing(MAX_PLAYER_POINTS);
    private static final TrailRing[] PROJ_TRAILS = new TrailRing[MAX_PROJECTILES];
    private static final int[] PROJ_ENTITY_IDS = new int[MAX_PROJECTILES];

    // Общие временные буферы для отрисовки (без аллокаций в кадре)
    private static final double[] OUT_XS = new double[MAX_PLAYER_POINTS];
    private static final double[] OUT_YS = new double[MAX_PLAYER_POINTS];
    private static final double[] OUT_ZS = new double[MAX_PLAYER_POINTS];
    private static final float[] OUT_LIFE = new float[MAX_PLAYER_POINTS];
    private static final float[] OUT_CAM_FADE = new float[MAX_PLAYER_POINTS];
    private static final double[] OUT_DIST = new double[MAX_PLAYER_POINTS];
    private static final int[] OUT_BOT_COLORS = new int[MAX_PLAYER_POINTS];
    private static final int[] OUT_TOP_COLORS = new int[MAX_PLAYER_POINTS];
    private static final float[] OUT_HEIGHTS = new float[MAX_PLAYER_POINTS];
    private static final float[] OUT_WIDTHS = new float[MAX_PLAYER_POINTS];

    private static final double[] HELIX_XS = new double[MAX_PLAYER_POINTS];
    private static final double[] HELIX_YS = new double[MAX_PLAYER_POINTS];
    private static final double[] HELIX_ZS = new double[MAX_PLAYER_POINTS];

    private static ClientWorld lastWorld = null;
    private static int lastPlayerId = -1;

    static {
        for (int i = 0; i < MAX_PROJECTILES; i++) {
            PROJ_TRAILS[i] = new TrailRing(MAX_PROJ_POINTS);
            PROJ_ENTITY_IDS[i] = -1;
        }
    }

    private TrailsRenderer() {}

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        MinecraftClient client = MinecraftClient.getInstance();
        TrailsModule module = ModuleManager.getTrailsModule();
        if (client == null || client.world == null || client.player == null) {
            clearAll();
            lastWorld = null;
            lastPlayerId = -1;
            return;
        }

        if (client.world != lastWorld || client.player.getId() != lastPlayerId) {
            lastWorld = client.world;
            lastPlayerId = client.player.getId();
            clearAll();
        }

        if (module == null || !module.isEnabled()) {
            clearAll();
            return;
        }

        long now = System.nanoTime();
        long lengthNs = Math.max(100_000_000L, (long) (module.length.getValue() * 1_000_000_000.0));
        float ribbonHeight = module.height.getValue().floatValue();
        float tickProgress = Camera3D.getTickProgress();

        int accent = ClientSettings.getAccentColor();
        int accentBright = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.35f);

        // 1. Записываем неизменяемые мировые координаты точки только при сдвиге >= 0.10 блока
        Vec3d playerPos = client.player.getLerpedPos(tickProgress);
        PLAYER_TRAIL.sample(playerPos.x, playerPos.y, playerPos.z, now, MIN_PLAYER_STEP_SQ, TELEPORT_RESET_DIST_SQ);
        PLAYER_TRAIL.pruneExpired(now, lengthNs);

        boolean isFirstPerson = client.options.getPerspective().isFirstPerson();
        if (!isFirstPerson || module.firstPerson.getValue()) {
            String style = module.style.getValue();
            if ("Orbs".equals(style)) {
                renderOrbsStyle(spheres, now, lengthNs, isFirstPerson, accent, accentBright);
            } else {
                renderPathRibbonStyle(batch3D, style, now, lengthNs, ribbonHeight, isFirstPerson, accent, accentBright);
            }
        }

        // 2. Шлейфы за жемчугом Эндера и стрелами
        if (module.projectiles.getValue()) {
            sampleProjectiles(client.world, tickProgress, now);
            renderProjectileTrails(batch3D, now, lengthNs, accent, accentBright);
        } else {
            clearProjectiles();
        }
    }

    private static void renderOrbsStyle(SphereBatch3D spheres, long now, long lengthNs,
                                        boolean isFirstPerson, int accent, int accentBright) {
        int count = PLAYER_TRAIL.size;
        if (count == 0) {
            return;
        }

        double camX = Camera3D.getCamX();
        double camY = Camera3D.getCamY();
        double camZ = Camera3D.getCamZ();

        for (int i = 0; i < count; i++) {
            double wx = PLAYER_TRAIL.getX(i);
            double wy = PLAYER_TRAIL.getY(i) + 0.25;
            double wz = PLAYER_TRAIL.getZ(i);
            long spawnNs = PLAYER_TRAIL.getTime(i);

            float lifeProgress = Math.clamp((float) ((now - spawnNs) / (double) lengthNs), 0.0f, 1.0f);
            float life = 1.0f - lifeProgress;
            if (life <= 0.01f) {
                continue;
            }

            if (isFirstPerson) {
                if (lifeProgress < 0.08f) {
                    continue;
                }
                double dx = wx - camX;
                double dy = wy - camY;
                double dz = wz - camZ;
                if (dx * dx + dy * dy + dz * dz < ORB_FIRST_PERSON_DIST_SQ) {
                    continue;
                }
            }

            float radius = 0.048f * life;
            float alpha = life * life * 0.90f;
            int orbColor = ColorUtil.lerp(accent, accentBright, life * 0.7f);
            spheres.drawSphere(wx, wy, wz, radius, ColorUtil.withAlpha(orbColor, alpha), 0.95f, BlendMode.ADDITIVE);
        }
    }

    private static void renderPathRibbonStyle(RenderBatch3D batch3D, String style, long now, long lengthNs,
                                              float ribbonHeight, boolean isFirstPerson,
                                              int accent, int accentBright) {
        int rawCount = PLAYER_TRAIL.size;
        if (rawCount < 2) {
            return;
        }

        double yCenterOffset = switch (style) {
            case "Floor" -> 0.03;
            case "Helix" -> 0.15 + ribbonHeight * 0.45;
            default -> 0.15 + ribbonHeight * 0.5;
        };

        double camX = Camera3D.getCamX();
        double camY = Camera3D.getCamY();
        double camZ = Camera3D.getCamZ();

        // В первом лице не рисуем свежайшие сегменты прямо перед камерой (< 0.4 блока)
        int limit = rawCount;
        if (isFirstPerson) {
            while (limit > 0) {
                int idx = limit - 1;
                double dx = PLAYER_TRAIL.getX(idx) - camX;
                double dy = (PLAYER_TRAIL.getY(idx) + yCenterOffset) - camY;
                double dz = PLAYER_TRAIL.getZ(idx) - camZ;
                if (dx * dx + dy * dy + dz * dz < FIRST_PERSON_CLIP_DIST * FIRST_PERSON_CLIP_DIST) {
                    limit--;
                } else {
                    break;
                }
            }
        }
        if (limit < 2) {
            return;
        }

        for (int i = 0; i < limit; i++) {
            double x = PLAYER_TRAIL.getX(i);
            double y = PLAYER_TRAIL.getY(i);
            double z = PLAYER_TRAIL.getZ(i);
            long ts = PLAYER_TRAIL.getTime(i);

            float life = Math.clamp(1.0f - (float) ((now - ts) / (double) lengthNs), 0.0f, 1.0f);
            float camFade = 1.0f;
            if (isFirstPerson) {
                double dx = x - camX;
                double dy = (y + yCenterOffset) - camY;
                double dz = z - camZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist <= FIRST_PERSON_CLIP_DIST) {
                    camFade = 0.0f;
                } else if (dist < FIRST_PERSON_FADE_END) {
                    camFade = (float) ((dist - FIRST_PERSON_CLIP_DIST) / (FIRST_PERSON_FADE_END - FIRST_PERSON_CLIP_DIST));
                }
            }

            OUT_XS[i] = x;
            OUT_YS[i] = y;
            OUT_ZS[i] = z;
            OUT_DIST[i] = PLAYER_TRAIL.getDistance(i);
            OUT_LIFE[i] = life;
            OUT_CAM_FADE[i] = camFade;
        }

        switch (style) {
            case "Floor" -> renderFloorStyle(batch3D, limit, accent, accentBright);
            case "Helix" -> renderHelixStyle(batch3D, limit, ribbonHeight, accent, accentBright);
            default -> renderRibbonStyle(batch3D, limit, ribbonHeight, accent, accentBright);
        }
    }

    private static void renderRibbonStyle(RenderBatch3D batch3D, int count, float ribbonHeight,
                                          int accent, int accentBright) {
        for (int i = 0; i < count; i++) {
            float life = OUT_LIFE[i];
            float alphaCurve = life * life * OUT_CAM_FADE[i];
            OUT_YS[i] += 0.15;
            OUT_HEIGHTS[i] = ribbonHeight * (0.28f + 0.72f * life);
            OUT_BOT_COLORS[i] = ColorUtil.withAlpha(accentBright, alphaCurve * 0.62f);
            OUT_TOP_COLORS[i] = ColorUtil.withAlpha(accent, alphaCurve * 0.16f);
        }
        batch3D.drawVerticalRibbon(
                OUT_XS, OUT_YS, OUT_ZS,
                OUT_BOT_COLORS, OUT_TOP_COLORS, OUT_HEIGHTS,
                count, BlendMode.ADDITIVE
        );
    }

    private static void renderFloorStyle(RenderBatch3D batch3D, int count, int accent, int accentBright) {
        for (int i = 0; i < count; i++) {
            float life = OUT_LIFE[i];
            float alphaCurve = life * life * OUT_CAM_FADE[i];
            OUT_YS[i] += 0.03;
            OUT_WIDTHS[i] = 0.225f * (0.35f + 0.65f * life);
            OUT_BOT_COLORS[i] = ColorUtil.withAlpha(accentBright, alphaCurve * 0.78f);
            OUT_TOP_COLORS[i] = ColorUtil.withAlpha(accent, 0.0f);
        }
        batch3D.drawHorizontalRibbon(
                OUT_XS, OUT_YS, OUT_ZS,
                OUT_BOT_COLORS, OUT_TOP_COLORS, OUT_WIDTHS,
                count, BlendMode.ADDITIVE
        );
    }

    private static void renderHelixStyle(RenderBatch3D batch3D, int count, float ribbonHeight,
                                         int accent, int accentBright) {
        double yCenter = 0.15 + ribbonHeight * 0.45;
        float baseHelixRadius = Math.clamp(ribbonHeight * 0.30f, 0.16f, 0.38f);

        for (int i = 0; i < count; i++) {
            float life = OUT_LIFE[i];
            float alphaCurve = life * life * OUT_CAM_FADE[i];
            OUT_BOT_COLORS[i] = ColorUtil.withAlpha(ColorUtil.lerp(accent, accentBright, life), alphaCurve * 0.88f);
            OUT_WIDTHS[i] = 0.05f * (0.2f + 0.8f * life);
        }

        for (int h = 0; h < 2; h++) {
            double phaseShift = h * Math.PI;
            double lastSideX = 1.0;
            double lastSideZ = 0.0;

            for (int i = 0; i < count; i++) {
                int prev = Math.max(0, i - 1);
                int next = Math.min(count - 1, i + 1);
                double tx = OUT_XS[next] - OUT_XS[prev];
                double tz = OUT_ZS[next] - OUT_ZS[prev];
                double lenSq = tx * tx + tz * tz;

                double sideX;
                double sideZ;
                if (lenSq > 1e-8) {
                    double inv = 1.0 / Math.sqrt(lenSq);
                    sideX = -tz * inv;
                    sideZ = tx * inv;
                    lastSideX = sideX;
                    lastSideZ = sideZ;
                } else {
                    sideX = lastSideX;
                    sideZ = lastSideZ;
                }

                float life = OUT_LIFE[i];
                double r = baseHelixRadius * (0.35 + 0.65 * life);

                // Фаза жёстко привязана к неизменяемой пройденной дистанции самой точки
                double phase = OUT_DIST[i] * HELIX_PHASE_PER_BLOCK + phaseShift;
                double cosA = Math.cos(phase);
                double sinA = Math.sin(phase);

                HELIX_XS[i] = OUT_XS[i] + sideX * cosA * r;
                HELIX_YS[i] = OUT_YS[i] + yCenter + sinA * r;
                HELIX_ZS[i] = OUT_ZS[i] + sideZ * cosA * r;
            }

            batch3D.drawTrail(HELIX_XS, HELIX_YS, HELIX_ZS, OUT_BOT_COLORS, OUT_WIDTHS, count, BlendMode.ADDITIVE);
        }
    }

    private static void sampleProjectiles(ClientWorld world, float tickProgress, long now) {
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity) && !(entity instanceof ArrowEntity)) {
                continue;
            }
            if (entity.getVelocity().lengthSquared() < 1e-4) {
                continue;
            }
            TrailRing ring = getOrAllocProjectileRing(entity.getId());
            if (ring != null) {
                Vec3d pos = entity.getLerpedPos(tickProgress);
                ring.sample(pos.x, pos.y + entity.getHeight() * 0.5, pos.z, now, MIN_PROJ_STEP_SQ, TELEPORT_RESET_DIST_SQ);
            }
        }
    }

    private static void renderProjectileTrails(RenderBatch3D batch3D, long now, long lengthNs,
                                               int accent, int accentBright) {
        for (int p = 0; p < MAX_PROJECTILES; p++) {
            if (PROJ_ENTITY_IDS[p] == -1) {
                continue;
            }
            TrailRing ring = PROJ_TRAILS[p];
            ring.pruneExpired(now, lengthNs);
            if (ring.size == 0) {
                PROJ_ENTITY_IDS[p] = -1;
                continue;
            }
            if (ring.size < 2) {
                continue;
            }

            int count = ring.size;
            for (int i = 0; i < count; i++) {
                float life = Math.clamp(1.0f - (float) ((now - ring.getTime(i)) / (double) lengthNs), 0.0f, 1.0f);
                OUT_XS[i] = ring.getX(i);
                OUT_YS[i] = ring.getY(i);
                OUT_ZS[i] = ring.getZ(i);
                OUT_BOT_COLORS[i] = ColorUtil.withAlpha(ColorUtil.lerp(accent, accentBright, life), life * life * 0.88f);
                OUT_WIDTHS[i] = 0.065f * life;
            }

            batch3D.drawTrail(OUT_XS, OUT_YS, OUT_ZS, OUT_BOT_COLORS, OUT_WIDTHS, count, BlendMode.ADDITIVE);
        }
    }

    private static TrailRing getOrAllocProjectileRing(int entityId) {
        int freeSlot = -1;
        for (int i = 0; i < MAX_PROJECTILES; i++) {
            if (PROJ_ENTITY_IDS[i] == entityId) {
                return PROJ_TRAILS[i];
            }
            if (freeSlot == -1 && PROJ_ENTITY_IDS[i] == -1) {
                freeSlot = i;
            }
        }
        if (freeSlot != -1) {
            PROJ_ENTITY_IDS[freeSlot] = entityId;
            PROJ_TRAILS[freeSlot].clear();
            return PROJ_TRAILS[freeSlot];
        }
        return null;
    }

    private static void clearProjectiles() {
        for (int i = 0; i < MAX_PROJECTILES; i++) {
            PROJ_ENTITY_IDS[i] = -1;
            PROJ_TRAILS[i].clear();
        }
    }

    private static void clearAll() {
        PLAYER_TRAIL.clear();
        clearProjectiles();
    }

    private static final class TrailRing {
        private final int capacity;
        private final double[] xs;
        private final double[] ys;
        private final double[] zs;
        private final long[] times;
        private final double[] distances;

        private int head = 0;
        private int size = 0;
        private double totalDistance = 0.0;

        TrailRing(int capacity) {
            this.capacity = capacity;
            this.xs = new double[capacity];
            this.ys = new double[capacity];
            this.zs = new double[capacity];
            this.times = new long[capacity];
            this.distances = new double[capacity];
        }

        void clear() {
            head = 0;
            size = 0;
            totalDistance = 0.0;
        }

        void sample(double x, double y, double z, long now, double minStepSq, double teleportResetDistSq) {
            if (size > 0) {
                int lastIdx = (head + size - 1) % capacity;
                double dx = x - xs[lastIdx];
                double dy = y - ys[lastIdx];
                double dz = z - zs[lastIdx];
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > teleportResetDistSq) {
                    clear();
                } else if (distSq < minStepSq) {
                    return;
                } else {
                    totalDistance += Math.sqrt(distSq);
                }
            }
            if (size == capacity) {
                head = (head + 1) % capacity;
                size--;
            }
            int writeIdx = (head + size) % capacity;
            xs[writeIdx] = x;
            ys[writeIdx] = y;
            zs[writeIdx] = z;
            times[writeIdx] = now;
            distances[writeIdx] = totalDistance;
            size++;
        }

        void pruneExpired(long now, long maxAgeNs) {
            while (size > 0 && (now - times[head]) > maxAgeNs) {
                head = (head + 1) % capacity;
                size--;
            }
        }

        double getX(int logicalIdx) {
            return xs[(head + logicalIdx) % capacity];
        }

        double getY(int logicalIdx) {
            return ys[(head + logicalIdx) % capacity];
        }

        double getZ(int logicalIdx) {
            return zs[(head + logicalIdx) % capacity];
        }

        long getTime(int logicalIdx) {
            return times[(head + logicalIdx) % capacity];
        }

        double getDistance(int logicalIdx) {
            return distances[(head + logicalIdx) % capacity];
        }
    }
}
