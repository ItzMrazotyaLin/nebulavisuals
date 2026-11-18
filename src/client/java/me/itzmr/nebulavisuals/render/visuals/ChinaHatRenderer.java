package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.ChinaHatModule;
import me.itzmr.nebulavisuals.render.engine2d.animation.AnimationEngine;
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
 * 3D-рендерер модуля {@link ChinaHatModule}: полупрозрачный конус с градиентом
 * и светящимся ободком над головой игрока (в F5) и опционально над текущей целью,
 * с плавной анимацией появления и исчезновения.
 */
public final class ChinaHatRenderer {
    private static final int CONE_SEGMENTS = 64;
    private static final float ANIM_SPEED = 11.0f;

    private static final HatAnimState SELF_STATE = new HatAnimState();
    private static final HatAnimState TARGET_STATE = new HatAnimState();
    private static long lastTimeNs = 0L;

    private ChinaHatRenderer() {}

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        MinecraftClient client = MinecraftClient.getInstance();
        ChinaHatModule module = ModuleManager.getChinaHatModule();
        if (client == null || client.world == null || client.player == null || module == null) {
            SELF_STATE.reset();
            TARGET_STATE.reset();
            lastTimeNs = 0L;
            return;
        }

        float dt = computeDeltaTime();
        float tickProgress = Camera3D.getTickProgress();

        boolean firstPerson = client.options.getPerspective().isFirstPerson();
        boolean showSelf = module.isEnabled() && !firstPerson;
        if (firstPerson) {
            SELF_STATE.reset();
        } else {
            // Пока не в F1, обновляем координаты игрока даже на этапе fade-out, чтобы шляпа не зависала в воздухе
            updateStateFromPlayer(SELF_STATE, client.player, tickProgress);
            stepAnim(SELF_STATE, showSelf ? 1.0f : 0.0f, dt);
        }

        PlayerEntity validTarget = null;
        if (module.isEnabled() && module.showOnTarget.getValue()) {
            PlayerEntity candidate = TargetHudRenderer.resolveTarget(client, false);
            if (candidate != null && candidate != client.player && !candidate.isSpectator() && candidate.isAlive()
                    && EntityVisibilityUtil.canRenderVisualsOn(candidate)) {
                validTarget = candidate;
            }
        }

        if (validTarget != null) {
            if (TARGET_STATE.uuid != null && !TARGET_STATE.uuid.equals(validTarget.getUuid())
                    && TARGET_STATE.animProgress > 0.05f) {
                // При резкой смене цели начинаем плавный спаун новой шляпы
                TARGET_STATE.animProgress = 0.0f;
            }
            TARGET_STATE.uuid = validTarget.getUuid();
            updateStateFromPlayer(TARGET_STATE, validTarget, tickProgress);
            stepAnim(TARGET_STATE, 1.0f, dt);
        } else {
            if (TARGET_STATE.uuid != null) {
                PlayerEntity prev = client.world.getPlayerByUuid(TARGET_STATE.uuid);
                if (prev != null && !EntityVisibilityUtil.canRenderVisualsOn(prev)) {
                    TARGET_STATE.reset();
                } else {
                    stepAnim(TARGET_STATE, 0.0f, dt);
                }
            } else {
                stepAnim(TARGET_STATE, 0.0f, dt);
            }
        }

        if (!SELF_STATE.isVisible() && !TARGET_STATE.isVisible()) {
            return;
        }

        float baseRadius = module.radius.getValue().floatValue();
        float baseHeight = module.height.getValue().floatValue();
        float baseOpacity = module.opacity.getValue().floatValue();

        int accent = ClientSettings.getAccentColor();
        int accentBright = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.42f);

        if (SELF_STATE.isVisible()) {
            renderAnimatedHat(SELF_STATE, batch3D, baseRadius, baseHeight, baseOpacity, accent, accentBright);
        }
        if (TARGET_STATE.isVisible()) {
            renderAnimatedHat(TARGET_STATE, batch3D, baseRadius, baseHeight, baseOpacity, accent, accentBright);
        }
    }

    private static void updateStateFromPlayer(HatAnimState state, PlayerEntity player, float tickProgress) {
        Vec3d pos = player.getLerpedPos(tickProgress);
        state.lastX = pos.x;
        state.lastY = pos.y;
        state.lastZ = pos.z;
        state.lastHeadHeight = player.isInSneakingPose()
                ? Math.min(player.getHeight(), 1.50) + 0.03
                : player.getHeight() + 0.05;
        state.hasPos = true;
    }

    private static void stepAnim(HatAnimState state, float target, float dt) {
        state.animProgress = AnimationEngine.expLerp(state.animProgress, target, ANIM_SPEED, dt);
        if (target <= 0.0f && state.animProgress < 0.01f) {
            state.reset();
        }
    }

    private static void renderAnimatedHat(HatAnimState state, RenderBatch3D batch3D,
                                          float baseRadius, float baseHeight, float baseOpacity,
                                          int accent, int accentBright) {
        float p = Math.clamp(state.animProgress, 0.0f, 1.0f);
        float scale = 0.2f + 0.8f * p;
        float alpha = baseOpacity * p;
        double yDropOffset = (1.0f - p) * 0.25;

        float radius = baseRadius * scale;
        float height = baseHeight * scale;

        double rimY = state.lastY + state.lastHeadHeight + yDropOffset;
        double apexY = rimY + height;

        int apexColor = ColorUtil.withAlpha(accentBright, alpha * 0.95f);
        int rimFillColor = ColorUtil.withAlpha(accent, alpha * 0.25f);
        int rimLineColor = ColorUtil.withAlpha(accentBright, Math.min(1.0f, alpha + 0.35f * p));

        // Полупрозрачный конус с мягким градиентом (GL_CULL_FACE отключается в RenderBatch3D)
        batch3D.drawCone(
                state.lastX, apexY, state.lastZ,
                radius, height,
                apexColor, rimFillColor,
                CONE_SEGMENTS,
                BlendMode.ALPHA
        );

        // Тонкий светящийся контур по нижнему ободку
        batch3D.drawCircleOutline(
                state.lastX, rimY, state.lastZ,
                radius,
                rimLineColor,
                CONE_SEGMENTS,
                2.0f,
                BlendMode.ADDITIVE
        );
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

    private static final class HatAnimState {
        UUID uuid;
        float animProgress;
        double lastX;
        double lastY;
        double lastZ;
        double lastHeadHeight = 1.85;
        boolean hasPos;

        boolean isVisible() {
            return hasPos && animProgress >= 0.01f;
        }

        void reset() {
            uuid = null;
            animProgress = 0.0f;
            hasPos = false;
        }
    }
}
