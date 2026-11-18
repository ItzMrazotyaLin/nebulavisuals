package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.JumpCirclesModule;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine3d.BlendMode;
import me.itzmr.nebulavisuals.render.engine3d.RenderBatch3D;
import me.itzmr.nebulavisuals.render.engine3d.SphereBatch3D;
import me.itzmr.nebulavisuals.util.EntityVisibilityUtil;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 3D-рендерер модуля {@link JumpCirclesModule} на базе фиксированного пула колец без аллокаций в кадре.
 * Поддерживает режимы Classic, Ripple, Polygon и Segmented.
 */
public final class JumpCirclesRenderer {
    private static final int MAX_CIRCLES = 64;
    private static final long SPAWN_COOLDOWN_NS = 180_000_000L;
    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private static final boolean[] ACTIVE = new boolean[MAX_CIRCLES];
    private static final double[] CX = new double[MAX_CIRCLES];
    private static final double[] CY = new double[MAX_CIRCLES];
    private static final double[] CZ = new double[MAX_CIRCLES];
    private static final long[] START_NS = new long[MAX_CIRCLES];
    private static final float[] MAX_RADIUS = new float[MAX_CIRCLES];
    private static final float[] DURATION = new float[MAX_CIRCLES];

    private static int nextSlot = 0;

    private static final Map<Integer, PlayerJumpState> PLAYER_STATES = new HashMap<>();
    private static ClientWorld lastWorld = null;
    private static int tickCounter = 0;

    private JumpCirclesRenderer() {}

    public static void onClientTick(MinecraftClient client) {
        JumpCirclesModule module = ModuleManager.getJumpCirclesModule();
        if (client == null || client.world == null || client.player == null || module == null || !module.isEnabled()) {
            if (!PLAYER_STATES.isEmpty()) {
                PLAYER_STATES.clear();
            }
            if (client == null || client.world == null) {
                clearCircles();
                lastWorld = null;
            }
            return;
        }

        if (client.world != lastWorld) {
            lastWorld = client.world;
            PLAYER_STATES.clear();
            clearCircles();
        }

        long now = System.nanoTime();
        tickCounter++;
        float radius = module.maxRadius.getValue().floatValue();
        float duration = module.duration.getValue().floatValue();

        if (module.otherPlayers.getValue()) {
            List<? extends PlayerEntity> players = client.world.getPlayers();
            for (int i = 0, size = players.size(); i < size; i++) {
                trackPlayer(players.get(i), client.player, now, tickCounter, radius, duration);
            }
            if ((tickCounter & 63) == 0) {
                Iterator<Map.Entry<Integer, PlayerJumpState>> it = PLAYER_STATES.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().lastSeenTick != tickCounter) {
                        it.remove();
                    }
                }
            }
        } else {
            trackPlayer(client.player, client.player, now, tickCounter, radius, duration);
            if (PLAYER_STATES.size() > 1) {
                int selfId = client.player.getId();
                PLAYER_STATES.keySet().removeIf(id -> id != selfId);
            }
        }
    }

    private static void trackPlayer(PlayerEntity player, PlayerEntity localPlayer, long now, int tick, float radius, float duration) {
        if (player == null || player.isSpectator() || !player.isAlive()) {
            return;
        }
        if (player != localPlayer && !EntityVisibilityUtil.canRenderVisualsOn(player)) {
            PLAYER_STATES.remove(player.getId());
            return;
        }

        PlayerJumpState state = PLAYER_STATES.get(player.getId());
        if (state == null) {
            state = new PlayerJumpState();
            PLAYER_STATES.put(player.getId(), state);
        }
        state.lastSeenTick = tick;

        boolean nowOnGround = player.isOnGround();
        double curY = player.getY();

        if (state.initialized
                && state.prevOnGround
                && !nowOnGround
                && (player.getVelocity().y > 0.1 || (curY - state.prevY) > 0.08)
                && !player.isTouchingWater()
                && !player.isClimbing()
                && !player.hasVehicle()
                && (now - state.lastSpawnNs) >= SPAWN_COOLDOWN_NS) {
            state.lastSpawnNs = now;
            spawnCircle(player.getX(), state.prevY + 0.02, player.getZ(), radius, duration, now);
        }

        state.prevOnGround = nowOnGround;
        state.prevY = curY;
        state.initialized = true;
    }

    private static void spawnCircle(double x, double y, double z, float maxRadius, float duration, long now) {
        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % MAX_CIRCLES;

        ACTIVE[slot] = true;
        CX[slot] = x;
        CY[slot] = y;
        CZ[slot] = z;
        START_NS[slot] = now;
        MAX_RADIUS[slot] = maxRadius;
        DURATION[slot] = Math.max(0.1f, duration);
    }

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        JumpCirclesModule module = ModuleManager.getJumpCirclesModule();
        if (module == null || !module.isEnabled()) {
            return;
        }

        long now = System.nanoTime();
        String mode = module.mode.getValue();
        int accent = ClientSettings.getAccentColor();
        int accentBright = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.45f);

        for (int i = 0; i < MAX_CIRCLES; i++) {
            if (!ACTIVE[i]) {
                continue;
            }

            float elapsed = (now - START_NS[i]) * 1e-9f;
            float dur = DURATION[i];
            if (elapsed < 0.0f || elapsed >= dur) {
                ACTIVE[i] = false;
                continue;
            }

            float t = elapsed / dur;
            double x = CX[i];
            double y = CY[i];
            double z = CZ[i];
            float maxR = MAX_RADIUS[i];

            switch (mode) {
                case "Ripple" -> {
                    if (t < 0.84f) {
                        renderRingGeometry(batch3D, x, y, z, maxR, t / 0.84f, 64, 0.0f, 1.0f, accent, accentBright);
                    }
                    if (t > 0.16f) {
                        renderRingGeometry(batch3D, x, y + 0.001, z, maxR, (t - 0.16f) / 0.84f, 64, 0.0f, 0.78f, accent, accentBright);
                    }
                }
                case "Polygon" -> {
                    float rot = t * 1.35f;
                    renderRingGeometry(batch3D, x, y, z, maxR, t, 6, rot, 1.0f, accent, accentBright);
                }
                case "Segmented" -> renderSegmentedRing(batch3D, x, y, z, maxR, t, accent, accentBright);
                default -> renderRingGeometry(batch3D, x, y, z, maxR, t, 64, 0.0f, 1.0f, accent, accentBright);
            }
        }
    }

    private static void renderRingGeometry(RenderBatch3D batch3D, double cx, double cy, double cz,
                                           float maxRadius, float t, int segments, float rotationRad,
                                           float alphaMult, int accent, int accentBright) {
        float clampedT = Math.clamp(t, 0.0f, 1.0f);
        float easeOutCubic = 1.0f - (float) Math.pow(1.0f - clampedT, 3.0);
        float radius = 0.1f + (maxRadius - 0.1f) * easeOutCubic;
        float alpha = computeWaveAlpha(clampedT) * alphaMult;
        if (alpha <= 0.004f) {
            return;
        }

        float haloWidth = 0.20f * (0.65f + 0.35f * (1.0f - clampedT));
        batch3D.drawHaloRing(
                cx, cy, cz,
                radius,
                haloWidth,
                ColorUtil.withAlpha(accent, alpha * 0.62f),
                ColorUtil.withAlpha(accent, 0.0f),
                segments,
                rotationRad,
                BlendMode.ADDITIVE
        );

        float rimWidth = Math.min(0.08f, radius * 0.35f);
        batch3D.drawRing(
                cx, cy + 0.002, cz,
                Math.max(0.02f, radius - rimWidth),
                radius,
                ColorUtil.withAlpha(accent, 0.0f),
                ColorUtil.withAlpha(accentBright, alpha * 0.90f),
                segments,
                rotationRad,
                BlendMode.ADDITIVE
        );

        batch3D.drawCircleOutline(
                cx, cy + 0.003, cz,
                radius,
                ColorUtil.withAlpha(accentBright, alpha),
                segments,
                rotationRad,
                2.0f,
                BlendMode.ADDITIVE
        );
    }

    private static void renderSegmentedRing(RenderBatch3D batch3D, double cx, double cy, double cz,
                                            float maxRadius, float t, int accent, int accentBright) {
        float easeOutCubic = 1.0f - (float) Math.pow(1.0f - t, 3.0);
        float radius = 0.1f + (maxRadius - 0.1f) * easeOutCubic;
        float alpha = computeWaveAlpha(t);
        if (alpha <= 0.004f) {
            return;
        }

        int arcCount = 8;
        float step = TWO_PI / arcCount;
        float sweep = step * 0.62f;
        float rot = t * 1.55f;

        float rimWidth = Math.min(0.08f, radius * 0.35f);
        int innerColor = ColorUtil.withAlpha(accent, 0.0f);
        int outerColor = ColorUtil.withAlpha(accentBright, alpha * 0.85f);
        int lineColor = ColorUtil.withAlpha(accentBright, alpha * 0.95f);

        for (int a = 0; a < arcCount; a++) {
            float start = rot + a * step;
            batch3D.drawArcRing(
                    cx, cy + 0.001, cz,
                    Math.max(0.02f, radius - rimWidth),
                    radius,
                    start,
                    sweep,
                    innerColor,
                    outerColor,
                    8,
                    BlendMode.ADDITIVE
            );
            batch3D.drawArcOutline(
                    cx, cy + 0.002, cz,
                    radius,
                    start,
                    sweep,
                    lineColor,
                    8,
                    2.0f,
                    BlendMode.ADDITIVE
            );
        }
    }

    private static float computeWaveAlpha(float t) {
        float fadeIn = t < 0.06f ? (t / 0.06f) : 1.0f;
        float fadeOut = 1.0f - t;
        return fadeIn * fadeOut * fadeOut * 0.9f + fadeIn * fadeOut * 0.1f;
    }

    private static void clearCircles() {
        for (int i = 0; i < MAX_CIRCLES; i++) {
            ACTIVE[i] = false;
        }
    }

    private static final class PlayerJumpState {
        boolean initialized = false;
        boolean prevOnGround = true;
        double prevY = 0.0;
        long lastSpawnNs = 0L;
        int lastSeenTick = 0;
    }
}
