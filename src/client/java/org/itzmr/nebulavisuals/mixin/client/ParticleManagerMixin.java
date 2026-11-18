package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.optimization.AnimationsCullingModule;
import me.itzmr.nebulavisuals.module.optimization.ParticlesOptimizerModule;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.SubmittableBatch;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;

/**
 * Подсчитывает видимые частицы кадра для детерминированной квоты {@link ParticlesOptimizerModule},
 * блокирует спавн при {@code Disable All} и скрывает частицы порталов/костров/чар.
 */
@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
    @Shadow @Final private Map<ParticleTextureSheet, ParticleRenderer<?>> particles;
    @Shadow @Final private Queue<Particle> newParticles;

    @Inject(method = "addToBatch", at = @At("HEAD"), require = 0)
    private void nebulavisuals$countVisibleParticles(
            SubmittableBatch batch, Frustum frustum, Camera camera, float tickProgress, CallbackInfo ci) {
        ParticlesOptimizerModule opt = ModuleManager.getParticlesOptimizerModule();
        if (opt == null || !opt.isEnabled() || opt.isDisableAll() || camera == null) {
            ParticlesOptimizerModule.setVisibleParticlesInFrame(0);
            return;
        }

        Vec3d camPos = camera.getCameraPos();
        double yawRad = Math.toRadians(camera.getYaw());
        double pitchRad = Math.toRadians(camera.getPitch());
        double cosPitch = Math.cos(pitchRad);
        double lookX = -Math.sin(yawRad) * cosPitch;
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * cosPitch;

        int visible = 0;
        for (ParticleRenderer<?> renderer : this.particles.values()) {
            for (Particle particle : renderer.getParticles()) {
                if (particle == null) {
                    continue;
                }
                ParticleAccessor acc = (ParticleAccessor) particle;
                double px = acc.nebulavisuals$getX();
                double py = acc.nebulavisuals$getY();
                double pz = acc.nebulavisuals$getZ();
                if (!frustum.intersectPoint(px, py, pz)) {
                    continue;
                }
                double dx = px - camPos.x;
                double dy = py - camPos.y;
                double dz = pz - camPos.z;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > 0.25) {
                    double dot = (dx * lookX + dy * lookY + dz * lookZ) / Math.sqrt(distSq);
                    if (dot < -0.2) {
                        continue;
                    }
                }
                visible++;
            }
        }
        ParticlesOptimizerModule.setVisibleParticlesInFrame(visible);
    }

    @Inject(
            method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nebulavisuals$filterParticleCreation(
            ParticleEffect parameters,
            double x,
            double y,
            double z,
            double velocityX,
            double velocityY,
            double velocityZ,
            CallbackInfoReturnable<Particle> cir) {
        ParticlesOptimizerModule opt = ModuleManager.getParticlesOptimizerModule();
        if (opt != null && opt.isDisableAll()) {
            cir.setReturnValue(null);
            return;
        }

        AnimationsCullingModule anim = ModuleManager.getAnimationsCullingModule();
        if (anim != null && anim.isEnabled() && parameters != null) {
            ParticleType<?> type = parameters.getType();
            if (anim.portalsAndCampfires.getValue()
                    && (type == ParticleTypes.PORTAL
                    || type == ParticleTypes.REVERSE_PORTAL
                    || type == ParticleTypes.CAMPFIRE_COSY_SMOKE
                    || type == ParticleTypes.CAMPFIRE_SIGNAL_SMOKE)) {
                cir.setReturnValue(null);
                return;
            }
            if (anim.enchantingTable.getValue() && type == ParticleTypes.ENCHANT) {
                cir.setReturnValue(null);
                return;
            }
        }
    }

    @Inject(
            method = "addParticle(Lnet/minecraft/client/particle/Particle;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void nebulavisuals$enforceSafetyCeiling(Particle particle, CallbackInfo ci) {
        if (particle == null) {
            return;
        }
        ParticlesOptimizerModule opt = ModuleManager.getParticlesOptimizerModule();
        if (opt == null || !opt.isEnabled()) {
            return;
        }
        if (opt.isDisableAll()) {
            particle.markDead();
            ci.cancel();
            return;
        }

        // Оставляем частицы живыми для детерминированной фильтрации в рендере (без мерцания),
        // ограничивая лишь экстремальный перелив памяти выше 4x квоты.
        int hardCap = Math.max(opt.getMaxParticles() * 4, 4096);
        int total = this.newParticles.size();
        for (ParticleRenderer<?> renderer : this.particles.values()) {
            total += renderer.size();
        }
        if (total >= hardCap) {
            nebulavisuals$trimOldest((total - hardCap) + 1);
        }
    }

    @Unique
    private void nebulavisuals$trimOldest(int remaining) {
        for (ParticleRenderer<?> renderer : this.particles.values()) {
            Iterator<? extends Particle> it = renderer.getParticles().iterator();
            while (it.hasNext() && remaining > 0) {
                Particle existing = it.next();
                if (existing != null) {
                    existing.markDead();
                }
                it.remove();
                remaining--;
            }
            if (remaining <= 0) {
                return;
            }
        }
    }
}
