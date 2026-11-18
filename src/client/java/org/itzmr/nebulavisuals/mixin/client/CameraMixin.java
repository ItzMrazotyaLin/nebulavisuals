package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.utilities.FreelookModule;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Управляет плавной камерой {@code Freelook} (вход/выход без рывков и разворота)
 * и быстрой отзывчивой кривой {@code EaseOutQuart} для {@code F5 Transition}.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    private static final float F5_BASE_SPEED = 5.0f; // ~167ms при Speed=1.2x
    private static final float F5_STOP_THRESHOLD = 0.01f;

    @Shadow private Entity focusedEntity;
    @Shadow private boolean thirdPerson;
    @Shadow private float yaw;
    @Shadow private float pitch;

    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract float clipToSpace(float desiredCameraDistance);
    @Shadow protected abstract void moveBy(float x, float y, float z);

    @Unique private Perspective nebulavisuals$lastPerspective = null;
    @Unique private float nebulavisuals$f5AnimT = 1.0f;
    @Unique private float nebulavisuals$f5StartFactor = 0.0f;
    @Unique private float nebulavisuals$f5TargetFactor = 0.0f;
    @Unique private float nebulavisuals$f5CurrentFactor = 0.0f;
    @Unique private boolean nebulavisuals$f5LastInverse = false;
    @Unique private boolean nebulavisuals$wasFreelook = false;
    @Unique private long nebulavisuals$f5LastNs = 0L;

    @Inject(method = "update", at = @At("HEAD"))
    private void nebulavisuals$updateCameraTransitions(
            World area,
            Entity focusedEntity,
            boolean thirdPerson,
            boolean inverseView,
            float tickProgress,
            CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || focusedEntity != client.player || client.options == null) {
            return;
        }

        FreelookModule freelook = ModuleManager.getFreelookModule();
        if (freelook != null) {
            freelook.onCameraFrame(client);
        }

        boolean freelookControlling = freelook != null && freelook.isControllingCamera();
        Perspective currentPerspective = client.options.getPerspective();
        if (freelookControlling) {
            this.nebulavisuals$wasFreelook = true;
            this.nebulavisuals$f5LastNs = System.nanoTime();
            AnimationsModule.setCurrentF5Distance(0.0f);
            return;
        }

        if (this.nebulavisuals$wasFreelook) {
            this.nebulavisuals$wasFreelook = false;
            float resting = currentPerspective.isFirstPerson() ? 0.0f : 1.0f;
            this.nebulavisuals$lastPerspective = currentPerspective;
            this.nebulavisuals$f5StartFactor = resting;
            this.nebulavisuals$f5TargetFactor = resting;
            this.nebulavisuals$f5CurrentFactor = resting;
            this.nebulavisuals$f5AnimT = 1.0f;
            this.nebulavisuals$f5LastInverse = currentPerspective.isFrontView();
            this.nebulavisuals$f5LastNs = System.nanoTime();
            AnimationsModule.setCurrentF5Distance(resting * 4.0f);
            return;
        }

        if (!AnimationsModule.isF5TransitionActive()) {
            float resting = currentPerspective.isFirstPerson() ? 0.0f : 1.0f;
            this.nebulavisuals$lastPerspective = currentPerspective;
            this.nebulavisuals$f5StartFactor = resting;
            this.nebulavisuals$f5TargetFactor = resting;
            this.nebulavisuals$f5CurrentFactor = resting;
            this.nebulavisuals$f5AnimT = 1.0f;
            this.nebulavisuals$f5LastInverse = currentPerspective.isFrontView();
            this.nebulavisuals$f5LastNs = 0L;
            AnimationsModule.setCurrentF5Distance(0.0f);
            return;
        }

        if (this.nebulavisuals$lastPerspective == null) {
            float resting = currentPerspective.isFirstPerson() ? 0.0f : 1.0f;
            this.nebulavisuals$lastPerspective = currentPerspective;
            this.nebulavisuals$f5StartFactor = resting;
            this.nebulavisuals$f5TargetFactor = resting;
            this.nebulavisuals$f5CurrentFactor = resting;
            this.nebulavisuals$f5AnimT = 1.0f;
            this.nebulavisuals$f5LastInverse = currentPerspective.isFrontView();
            this.nebulavisuals$f5LastNs = System.nanoTime();
            AnimationsModule.setCurrentF5Distance(resting * 4.0f);
            return;
        }

        if (currentPerspective != this.nebulavisuals$lastPerspective) {
            // Запускаем новую анимацию 0.0 -> 1.0 от текущей дистанции к целевой без мёртвой зоны в начале
            if (this.nebulavisuals$lastPerspective == Perspective.THIRD_PERSON_BACK
                    && currentPerspective == Perspective.THIRD_PERSON_FRONT) {
                this.nebulavisuals$f5StartFactor = 0.0f;
            } else {
                this.nebulavisuals$f5StartFactor = this.nebulavisuals$f5CurrentFactor;
            }
            this.nebulavisuals$f5TargetFactor = currentPerspective.isFirstPerson() ? 0.0f : 1.0f;
            this.nebulavisuals$f5AnimT = 0.0f;
            if (!currentPerspective.isFirstPerson()) {
                this.nebulavisuals$f5LastInverse = currentPerspective.isFrontView();
            }
            this.nebulavisuals$lastPerspective = currentPerspective;
        }

        long now = System.nanoTime();
        float deltaTime = this.nebulavisuals$f5LastNs == 0L
                ? 0.0f
                : MathHelper.clamp((now - this.nebulavisuals$f5LastNs) / 1_000_000_000.0f, 0.0f, 0.05f);
        this.nebulavisuals$f5LastNs = now;

        if (this.nebulavisuals$f5AnimT < 1.0f) {
            AnimationsModule animations = ModuleManager.getAnimationsModule();
            float speed = animations != null ? animations.getSpeedMultiplier() : 1.2f;
            this.nebulavisuals$f5AnimT = Math.min(1.0f, this.nebulavisuals$f5AnimT + deltaTime * F5_BASE_SPEED * speed);
        }

        float eased = AnimationsModule.easeOutQuart(this.nebulavisuals$f5AnimT);
        this.nebulavisuals$f5CurrentFactor = MathHelper.lerp(eased, this.nebulavisuals$f5StartFactor, this.nebulavisuals$f5TargetFactor);
        if (this.nebulavisuals$f5TargetFactor == 0.0f && this.nebulavisuals$f5CurrentFactor <= F5_STOP_THRESHOLD) {
            this.nebulavisuals$f5CurrentFactor = 0.0f;
        }
        AnimationsModule.setCurrentF5Distance(4.0f * this.nebulavisuals$f5CurrentFactor);
    }

    @Inject(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void nebulavisuals$applyFreelookRotation(
            World area,
            Entity focusedEntity,
            boolean thirdPerson,
            boolean inverseView,
            float tickProgress,
            CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || this.focusedEntity != client.player) {
            return;
        }
        FreelookModule freelook = ModuleManager.getFreelookModule();
        if (freelook != null && freelook.isControllingCamera()) {
            this.setRotation(freelook.getCameraYaw(), freelook.getCameraPitch());
        }
    }

    @ModifyArg(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/Camera;clipToSpace(F)F"
            ),
            index = 0
    )
    private float nebulavisuals$scaleThirdPersonDistance(float desiredDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || this.focusedEntity != client.player) {
            return desiredDistance;
        }
        FreelookModule freelook = ModuleManager.getFreelookModule();
        if (freelook != null && freelook.isControllingCamera()) {
            return desiredDistance * freelook.getDistanceScale();
        }
        if (AnimationsModule.isF5TransitionActive()) {
            float scaled = desiredDistance * this.nebulavisuals$f5CurrentFactor;
            AnimationsModule.setCurrentF5Distance(scaled);
            return scaled;
        }
        return desiredDistance;
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void nebulavisuals$applyFirstPersonReturnDistance(
            World area,
            Entity focusedEntity,
            boolean thirdPerson,
            boolean inverseView,
            float tickProgress,
            CallbackInfo ci) {
        if (thirdPerson) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || this.focusedEntity != client.player) {
            return;
        }
        FreelookModule freelook = ModuleManager.getFreelookModule();
        if (freelook != null && freelook.isControllingCamera()) {
            return;
        }
        if (AnimationsModule.isF5TransitionActive() && this.nebulavisuals$f5CurrentFactor > F5_STOP_THRESHOLD) {
            if (this.nebulavisuals$f5LastInverse) {
                this.setRotation(this.yaw + 180.0f, -this.pitch);
            }
            float desiredDist = 4.0f * this.nebulavisuals$f5CurrentFactor;
            float clippedDist = this.clipToSpace(desiredDist);
            AnimationsModule.setCurrentF5Distance(clippedDist);
            // Модель игрока остаётся видимой в мире, а рука скрыта в HeldItemRendererMixin, пока дистанция > 0.35f
            this.thirdPerson = clippedDist > AnimationsModule.HAND_HIDE_DISTANCE_THRESHOLD;
            this.moveBy(-clippedDist, 0.0f, 0.0f);
        } else if (AnimationsModule.isF5TransitionActive()) {
            AnimationsModule.setCurrentF5Distance(0.0f);
        }
    }
}
