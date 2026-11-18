package org.itzmr.nebulavisuals.mixin.client;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.ParticlesOptimizerModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.BillboardParticleRenderer;
import net.minecraft.client.particle.BillboardParticleSubmittable;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.Submittable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Отсекает невидимые billboard-частицы позади камеры и за непрозрачными стенами,
 * а при превышении лимита применяет детерминированный фильтр по identityHashCode без мерцания.
 */
@Mixin(BillboardParticleRenderer.class)
public class BillboardParticleRendererMixin {
    @Unique private static final Long2BooleanOpenHashMap OCCLUSION_CACHE = new Long2BooleanOpenHashMap(256);
    @Unique private static long lastCacheWorldTime = Long.MIN_VALUE;
    @Unique private static double lastCacheCamX = Double.NaN;
    @Unique private static double lastCacheCamY = Double.NaN;
    @Unique private static double lastCacheCamZ = Double.NaN;
    @Unique private static int lastCacheEpoch = -1;

    @Unique private Camera nebulavisuals$activeCamera;

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void nebulavisuals$captureCameraAndRefreshCache(
            Frustum frustum, Camera camera, float tickProgress, CallbackInfoReturnable<Submittable> cir) {
        this.nebulavisuals$activeCamera = camera;

        int epoch = ParticlesOptimizerModule.getCacheEpoch();
        if (epoch != lastCacheEpoch) {
            OCCLUSION_CACHE.clear();
            lastCacheEpoch = epoch;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || camera == null) {
            OCCLUSION_CACHE.clear();
            return;
        }

        long worldTime = client.world.getTime();
        Vec3d camPos = camera.getCameraPos();
        if (worldTime != lastCacheWorldTime
                || Double.isNaN(lastCacheCamX)
                || camPos.squaredDistanceTo(lastCacheCamX, lastCacheCamY, lastCacheCamZ) > 0.0625
                || OCCLUSION_CACHE.size() > 2048) {
            OCCLUSION_CACHE.clear();
            lastCacheWorldTime = worldTime;
            lastCacheCamX = camPos.x;
            lastCacheCamY = camPos.y;
            lastCacheCamZ = camPos.z;
        }
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/Frustum;intersectPoint(DDD)Z"
            ),
            require = 0
    )
    private boolean nebulavisuals$filterParticleVisibility(Frustum frustum, double px, double py, double pz) {
        ParticlesOptimizerModule opt = ModuleManager.getParticlesOptimizerModule();
        if (opt != null && opt.isDisableAll()) {
            return false;
        }
        if (!frustum.intersectPoint(px, py, pz)) {
            return false;
        }
        if (opt == null || !opt.isEnabled() || this.nebulavisuals$activeCamera == null) {
            return true;
        }

        Vec3d camPos = this.nebulavisuals$activeCamera.getCameraPos();
        double dx = px - camPos.x;
        double dy = py - camPos.y;
        double dz = pz - camPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > 0.25) {
            double yawRad = Math.toRadians(this.nebulavisuals$activeCamera.getYaw());
            double pitchRad = Math.toRadians(this.nebulavisuals$activeCamera.getPitch());
            double cosPitch = Math.cos(pitchRad);
            double lookX = -Math.sin(yawRad) * cosPitch;
            double lookY = -Math.sin(pitchRad);
            double lookZ = Math.cos(yawRad) * cosPitch;

            double invDist = 1.0 / Math.sqrt(distSq);
            double dot = (dx * lookX + dy * lookY + dz * lookZ) * invDist;
            if (dot < -0.2) {
                return false;
            }
        }

        if (distSq <= 1.2 || distSq > 4096.0) {
            return true;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) {
            return true;
        }

        int bx = MathHelper.floor(px);
        int by = MathHelper.floor(py);
        int bz = MathHelper.floor(pz);
        long blockKey = BlockPos.asLong(bx, by, bz);

        if (OCCLUSION_CACHE.containsKey(blockKey)) {
            return !OCCLUSION_CACHE.get(blockKey);
        }

        BlockHitResult hit = client.world.raycast(new RaycastContext(
                camPos,
                new Vec3d(px, py, pz),
                RaycastContext.ShapeType.VISUAL,
                RaycastContext.FluidHandling.NONE,
                client.player
        ));

        boolean occluded = false;
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            if (!(hitPos.getX() == bx && hitPos.getY() == by && hitPos.getZ() == bz)
                    && client.world.getBlockState(hitPos).isOpaqueFullCube()) {
                occluded = true;
            }
        }
        OCCLUSION_CACHE.put(blockKey, occluded);
        return !occluded;
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/BillboardParticle;render(Lnet/minecraft/client/particle/BillboardParticleSubmittable;Lnet/minecraft/client/render/Camera;F)V"
            ),
            require = 0
    )
    private void nebulavisuals$renderParticleDeterministicQuota(
            BillboardParticle particle,
            BillboardParticleSubmittable submittable,
            Camera camera,
            float tickProgress) {
        ParticlesOptimizerModule opt = ModuleManager.getParticlesOptimizerModule();
        if (opt != null && !opt.shouldRenderByQuota(particle)) {
            return;
        }
        particle.render(submittable, camera, tickProgress);
    }
}
