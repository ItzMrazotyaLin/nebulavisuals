package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.HitParticlesModule;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine3d.BlendMode;
import me.itzmr.nebulavisuals.render.engine3d.Camera3D;
import me.itzmr.nebulavisuals.render.engine3d.RenderBatch3D;
import me.itzmr.nebulavisuals.render.engine3d.SphereBatch3D;
import me.itzmr.nebulavisuals.util.EntityVisibilityUtil;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Vector3f;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 3D-рендерер модуля {@link HitParticlesModule}: пул светящихся 3D-сфер без аллокаций в кадре,
 * с гравитацией, затуханием скорости и отскоком от коллизий блоков.
 */
public final class HitParticlesRenderer {
    private static final int MAX_PARTICLES = 512;
    private static final float GRAVITY = 9.8f;

    private static final boolean[] ACTIVE = new boolean[MAX_PARTICLES];
    private static final double[] POS_X = new double[MAX_PARTICLES];
    private static final double[] POS_Y = new double[MAX_PARTICLES];
    private static final double[] POS_Z = new double[MAX_PARTICLES];
    private static final float[] VEL_X = new float[MAX_PARTICLES];
    private static final float[] VEL_Y = new float[MAX_PARTICLES];
    private static final float[] VEL_Z = new float[MAX_PARTICLES];
    private static final float[] AGE = new float[MAX_PARTICLES];
    private static final float[] MAX_AGE = new float[MAX_PARTICLES];
    private static final float[] BASE_RADIUS = new float[MAX_PARTICLES];
    private static final int[] COLOR = new int[MAX_PARTICLES];
    private static final float[] ROT_ANGLE = new float[MAX_PARTICLES];
    private static final float[] ROT_SPEED = new float[MAX_PARTICLES];
    private static final float[] ROT_TILT = new float[MAX_PARTICLES];
    private static final float[] ROT_TILT_SPEED = new float[MAX_PARTICLES];

    private static final BlockPos.Mutable TEMP_POS = new BlockPos.Mutable();
    private static final Vector3f CAM_RIGHT = new Vector3f();
    private static final Vector3f CAM_UP = new Vector3f();

    private static int nextSlot = 0;
    private static long lastTimeNs = 0L;
    private static ClientWorld lastWorld = null;

    private HitParticlesRenderer() {}

    public static void onPlayerAttack(PlayerEntity attacker, Entity target) {
        MinecraftClient client = MinecraftClient.getInstance();
        HitParticlesModule module = ModuleManager.getHitParticlesModule();
        if (client == null || client.world == null || client.player == null || attacker != client.player) {
            return;
        }
        if (module == null || !module.isEnabled()) {
            return;
        }
        if (!(target instanceof LivingEntity living) || !living.isAlive()) {
            return;
        }
        if (!EntityVisibilityUtil.canRenderVisualsOn(living)) {
            return;
        }

        if ("Criticals Only".equals(module.trigger.getValue()) && !isCriticalHit(attacker)) {
            return;
        }

        int count = Math.clamp((int) Math.round(module.amount.getValue()), 1, 64);
        float baseLifeTime = module.lifeTime.getValue().floatValue();

        Box box = target.getBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = box.minY + (box.maxY - box.minY) * 0.55;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        double spreadXZ = Math.max(0.12, target.getWidth() * 0.25);

        int accent = ClientSettings.getAccentColor();
        int accentBright = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.45f);
        int accentHover = ClientSettings.getAccentHoverColor();

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % MAX_PARTICLES;

            double angle = rnd.nextDouble(Math.PI * 2.0);
            double horizSpeed = rnd.nextDouble(1.4, 3.8);

            ACTIVE[slot] = true;
            POS_X[slot] = centerX + rnd.nextDouble(-spreadXZ, spreadXZ);
            POS_Y[slot] = centerY + rnd.nextDouble(-0.18, 0.24);
            POS_Z[slot] = centerZ + rnd.nextDouble(-spreadXZ, spreadXZ);

            VEL_X[slot] = (float) (Math.cos(angle) * horizSpeed);
            VEL_Y[slot] = (float) rnd.nextDouble(1.2, 4.1);
            VEL_Z[slot] = (float) (Math.sin(angle) * horizSpeed);

            AGE[slot] = 0.0f;
            MAX_AGE[slot] = baseLifeTime * (float) rnd.nextDouble(0.75, 1.2);
            BASE_RADIUS[slot] = (float) rnd.nextDouble(0.042, 0.078);

            int variant = i % 3;
            COLOR[slot] = variant == 0 ? accent : (variant == 1 ? accentBright : accentHover);
            ROT_ANGLE[slot] = (float) rnd.nextDouble(Math.PI * 2.0);
            ROT_SPEED[slot] = (rnd.nextBoolean() ? 1.0f : -1.0f) * (float) rnd.nextDouble(2.4, 5.4);
            ROT_TILT[slot] = (float) rnd.nextDouble(Math.PI * 2.0);
            ROT_TILT_SPEED[slot] = (rnd.nextBoolean() ? 1.0f : -1.0f) * (float) rnd.nextDouble(1.8, 4.4);
        }
    }

    private static boolean isCriticalHit(PlayerEntity attacker) {
        return attacker.getAttackCooldownProgress(0.5f) > 0.9f
                && attacker.fallDistance > 0.0
                && !attacker.isOnGround()
                && !attacker.isClimbing()
                && !attacker.isTouchingWater()
                && !attacker.hasStatusEffect(StatusEffects.BLINDNESS)
                && !attacker.hasVehicle()
                && !attacker.isSprinting();
    }

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        MinecraftClient client = MinecraftClient.getInstance();
        HitParticlesModule module = ModuleManager.getHitParticlesModule();
        if (client == null || client.world == null) {
            clearParticles();
            lastWorld = null;
            lastTimeNs = 0L;
            return;
        }
        if (client.world != lastWorld) {
            lastWorld = client.world;
            clearParticles();
        }
        if (module == null || !module.isEnabled()) {
            lastTimeNs = 0L;
            return;
        }

        long now = System.nanoTime();
        float dt = lastTimeNs == 0L ? 0.016f : Math.clamp((now - lastTimeNs) * 1e-9f, 0.0f, 0.05f);
        lastTimeNs = now;

        boolean bounce = module.physicsBounce.getValue();
        float sizeScale = module.size.getValue().floatValue();
        String shapeMode = module.shape.getValue();
        float horizDrag = (float) Math.exp(-1.55f * dt);
        float vertDrag = (float) Math.exp(-0.35f * dt);

        if (!"Orbs".equals(shapeMode)) {
            Camera3D.getRightVector(CAM_RIGHT);
            Camera3D.getUpVector(CAM_UP);
        }

        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!ACTIVE[i]) {
                continue;
            }

            AGE[i] += dt;
            if (AGE[i] >= MAX_AGE[i]) {
                ACTIVE[i] = false;
                continue;
            }

            VEL_Y[i] -= GRAVITY * dt;
            VEL_X[i] *= horizDrag;
            VEL_Z[i] *= horizDrag;
            VEL_Y[i] *= vertDrag;
            ROT_ANGLE[i] += ROT_SPEED[i] * dt;
            ROT_TILT[i] += ROT_TILT_SPEED[i] * dt;

            double nextX = POS_X[i] + VEL_X[i] * dt;
            double nextY = POS_Y[i] + VEL_Y[i] * dt;
            double nextZ = POS_Z[i] + VEL_Z[i] * dt;

            if (bounce && VEL_Y[i] < 0.0f) {
                int bx = MathHelper.floor(nextX);
                int by = MathHelper.floor(nextY - 0.03);
                int bz = MathHelper.floor(nextZ);
                TEMP_POS.set(bx, by, bz);

                BlockState state = client.world.getBlockState(TEMP_POS);
                if (!state.isAir()) {
                    VoxelShape shape = state.getCollisionShape(client.world, TEMP_POS);
                    if (!shape.isEmpty()) {
                        double surfaceY = by + shape.getMax(Direction.Axis.Y);
                        if (nextY <= surfaceY + 0.035) {
                            nextY = surfaceY + 0.035;
                            float bounceVy = -VEL_Y[i] * 0.56f;
                            VEL_Y[i] = bounceVy < 0.45f ? 0.0f : bounceVy;
                            VEL_X[i] *= 0.78f;
                            VEL_Z[i] *= 0.78f;
                            ROT_SPEED[i] *= -0.82f;
                        }
                    }
                }
            }

            POS_X[i] = nextX;
            POS_Y[i] = nextY;
            POS_Z[i] = nextZ;

            float progress = AGE[i] / Math.max(0.05f, MAX_AGE[i]);
            float life = 1.0f - progress;
            float radius = BASE_RADIUS[i] * sizeScale * (1.0f - progress * 0.65f);
            float alpha = life * life * 0.95f;
            int baseColor = COLOR[i];
            int edgeArgb = ColorUtil.withAlpha(baseColor, alpha);
            int coreArgb = ColorUtil.withAlpha(ColorUtil.lerp(baseColor, 0xFFFFFFFF, 0.55f), Math.min(1.0f, alpha * 1.15f));

            switch (shapeMode) {
                case "Stars" -> batch3D.drawBillboardStar(
                        nextX, nextY, nextZ,
                        radius * 1.15f, ROT_ANGLE[i],
                        CAM_RIGHT.x, CAM_RIGHT.y, CAM_RIGHT.z,
                        CAM_UP.x, CAM_UP.y, CAM_UP.z,
                        coreArgb, edgeArgb, BlendMode.ADDITIVE
                );
                case "Hearts" -> batch3D.drawBillboardHeart(
                        nextX, nextY, nextZ,
                        radius * 1.10f, ROT_ANGLE[i] * 0.45f,
                        CAM_RIGHT.x, CAM_RIGHT.y, CAM_RIGHT.z,
                        CAM_UP.x, CAM_UP.y, CAM_UP.z,
                        coreArgb, edgeArgb, BlendMode.ADDITIVE
                );
                case "Cubes" -> batch3D.drawRotatedCubeWithEdges(
                        nextX, nextY, nextZ,
                        radius * 0.85f, ROT_ANGLE[i], ROT_TILT[i],
                        ColorUtil.withAlpha(baseColor, alpha * 0.30f),
                        coreArgb, 1.35f, BlendMode.ADDITIVE
                );
                case "Crosses" -> batch3D.drawBillboardCross(
                        nextX, nextY, nextZ,
                        radius * 1.15f, ROT_ANGLE[i],
                        CAM_RIGHT.x, CAM_RIGHT.y, CAM_RIGHT.z,
                        CAM_UP.x, CAM_UP.y, CAM_UP.z,
                        coreArgb, edgeArgb, BlendMode.ADDITIVE
                );
                case "Sparks" -> {
                    float vx = VEL_X[i];
                    float vy = VEL_Y[i];
                    float vz = VEL_Z[i];
                    float speedSq = vx * vx + vy * vy + vz * vz;
                    float dx, dy, dz, speed;
                    if (speedSq > 1e-4f) {
                        speed = (float) Math.sqrt(speedSq);
                        dx = vx / speed;
                        dy = vy / speed;
                        dz = vz / speed;
                    } else {
                        speed = 0.0f;
                        dx = 0.0f;
                        dy = 1.0f;
                        dz = 0.0f;
                    }
                    float halfLen = radius * Math.clamp(1.25f + speed * 0.36f, 1.2f, 3.1f);
                    float halfW = Math.max(0.006f, radius * 0.32f);
                    int tailArgb = ColorUtil.withAlpha(baseColor, alpha * 0.25f);
                    batch3D.drawVelocitySpark(
                            nextX, nextY, nextZ,
                            dx, dy, dz, halfLen, halfW,
                            coreArgb, edgeArgb, tailArgb, BlendMode.ADDITIVE
                    );
                }
                default -> spheres.drawSphere(
                        nextX, nextY, nextZ,
                        radius,
                        edgeArgb,
                        1.45f * life,
                        BlendMode.ADDITIVE
                );
            }
        }
    }

    private static void clearParticles() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            ACTIVE[i] = false;
        }
    }
}
