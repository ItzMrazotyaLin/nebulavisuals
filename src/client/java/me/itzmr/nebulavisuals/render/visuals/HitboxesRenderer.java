package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.HitboxesModule;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine3d.BlendMode;
import me.itzmr.nebulavisuals.render.engine3d.Camera3D;
import me.itzmr.nebulavisuals.render.engine3d.RenderBatch3D;
import me.itzmr.nebulavisuals.render.engine3d.SphereBatch3D;
import me.itzmr.nebulavisuals.util.EntityVisibilityUtil;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.FrustumIntersection;

/**
 * Оптимизированный 3D-рендерер модуля {@link HitboxesModule} без лишних draw call'ов и аллокаций:
 * <ul>
 *     <li>Проход 1: все полупрозрачные грани {@code QUADS} (в одном {@code GL_TRIANGLES + ALPHA} буфере за 1 draw call).</li>
 *     <li>Проход 2: все рёбра, линии глаз и векторы взгляда (в одном {@code GL_LINES + ALPHA} буфере за 1 draw call).</li>
 * </ul>
 * Выполняет отсечение по {@code Max Distance} и {@link FrustumIntersection} с плавным затуханием вдали.
 */
public final class HitboxesRenderer {
    private static final int MAX_ENTITIES = 512;
    private static final int HURT_RED_COLOR = 0xFFFF3B4E;
    private static final int EYE_LINE_COLOR = 0xFFE2E8F0;
    private static final int LOOK_VEC_COLOR = 0xFF38BDF8;

    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();

    private static final double[] BOX_MIN_X = new double[MAX_ENTITIES];
    private static final double[] BOX_MIN_Y = new double[MAX_ENTITIES];
    private static final double[] BOX_MIN_Z = new double[MAX_ENTITIES];
    private static final double[] BOX_MAX_X = new double[MAX_ENTITIES];
    private static final double[] BOX_MAX_Y = new double[MAX_ENTITIES];
    private static final double[] BOX_MAX_Z = new double[MAX_ENTITIES];
    private static final int[] FILL_ARGB = new int[MAX_ENTITIES];
    private static final int[] LINE_ARGB = new int[MAX_ENTITIES];

    private static final boolean[] HAS_EYE = new boolean[MAX_ENTITIES];
    private static final double[] EYE_Y = new double[MAX_ENTITIES];
    private static final int[] EYE_ARGB = new int[MAX_ENTITIES];
    private static final double[] LOOK_DX = new double[MAX_ENTITIES];
    private static final double[] LOOK_DY = new double[MAX_ENTITIES];
    private static final double[] LOOK_DZ = new double[MAX_ENTITIES];
    private static final int[] LOOK_START_ARGB = new int[MAX_ENTITIES];
    private static final int[] LOOK_END_ARGB = new int[MAX_ENTITIES];

    private HitboxesRenderer() {}

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        HitboxesModule module = ModuleManager.getHitboxesModule();
        if (module == null || !module.isEnabled()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null || !Camera3D.isValidFrame()) {
            return;
        }

        boolean showPlayers = module.players.getValue();
        boolean showMobs = module.mobs.getValue();
        boolean showItems = module.items.getValue();
        boolean showSelf = module.self.getValue();
        boolean isFirstPerson = client.options.getPerspective().isFirstPerson();
        float baseFillAlpha = module.fillAlpha.getValue().floatValue();
        float lineWidth = module.lineWidth.getValue().floatValue();
        float maxDist = module.maxDistance.getValue().floatValue();
        double maxDistSq = (double) maxDist * maxDist;
        float fadeStart = maxDist * 0.5f;
        float fadeRange = Math.max(1.0f, maxDist - fadeStart);
        boolean damageHighlight = module.damageHighlight.getValue();
        boolean showEyeAndLook = module.eyeAndLookVector.getValue();
        float tickProgress = Camera3D.getTickProgress();

        double camX = Camera3D.getCamX();
        double camY = Camera3D.getCamY();
        double camZ = Camera3D.getCamZ();
        FRUSTUM.set(Camera3D.getViewProjMatrix());

        int accent = ClientSettings.getAccentColor();
        int count = 0;

        for (Entity entity : client.world.getEntities()) {
            if (count >= MAX_ENTITIES) {
                break;
            }
            if (entity == null || entity.isRemoved() || entity.isSpectator()) {
                continue;
            }
            if (entity != client.player && !EntityVisibilityUtil.canRenderVisualsOn(entity)) {
                continue;
            }
            if (entity == client.player) {
                if (!showSelf || isFirstPerson) {
                    continue;
                }
            } else if (entity instanceof PlayerEntity) {
                if (!showPlayers) {
                    continue;
                }
            } else if (entity instanceof ItemEntity) {
                if (!showItems) {
                    continue;
                }
            } else if (entity instanceof LivingEntity) {
                if (!showMobs) {
                    continue;
                }
            } else {
                continue;
            }

            Vec3d lerped = entity.getLerpedPos(tickProgress);
            double dCamX = lerped.x - camX;
            double dCamY = lerped.y - camY;
            double dCamZ = lerped.z - camZ;
            double distSq = dCamX * dCamX + dCamY * dCamY + dCamZ * dCamZ;
            if (distSq > maxDistSq) {
                continue;
            }

            double offX = lerped.x - entity.getX();
            double offY = lerped.y - entity.getY();
            double offZ = lerped.z - entity.getZ();

            Box rawBox = entity.getBoundingBox();
            double minX = rawBox.minX + offX;
            double minY = rawBox.minY + offY;
            double minZ = rawBox.minZ + offZ;
            double maxX = rawBox.maxX + offX;
            double maxY = rawBox.maxY + offY;
            double maxZ = rawBox.maxZ + offZ;

            if (!FRUSTUM.testAab(
                    (float) (minX - camX), (float) (minY - camY), (float) (minZ - camZ),
                    (float) (maxX - camX), (float) (maxY - camY), (float) (maxZ - camZ)
            )) {
                continue;
            }

            float dist = (float) Math.sqrt(distSq);
            float distFade = 1.0f;
            if (dist > fadeStart) {
                float u = Math.clamp(1.0f - (dist - fadeStart) / fadeRange, 0.0f, 1.0f);
                distFade = u * u;
            }
            if (distFade <= 0.01f) {
                continue;
            }

            float hurtMix = 0.0f;
            if (damageHighlight && entity instanceof LivingEntity living && living.hurtTime > 0) {
                hurtMix = Math.clamp(living.hurtTime / 10.0f, 0.0f, 1.0f);
            }
            int boxColor = ColorUtil.lerp(accent, HURT_RED_COLOR, hurtMix);

            BOX_MIN_X[count] = minX;
            BOX_MIN_Y[count] = minY;
            BOX_MIN_Z[count] = minZ;
            BOX_MAX_X[count] = maxX;
            BOX_MAX_Y[count] = maxY;
            BOX_MAX_Z[count] = maxZ;

            float fillAlpha = (baseFillAlpha > 0.005f ? Math.min(0.60f, baseFillAlpha + hurtMix * 0.09f) : hurtMix * 0.08f) * distFade;
            FILL_ARGB[count] = fillAlpha > 0.005f ? ColorUtil.withAlpha(boxColor, fillAlpha) : 0;
            LINE_ARGB[count] = ColorUtil.withAlpha(boxColor, 0.90f * distFade);

            if (showEyeAndLook && entity instanceof LivingEntity) {
                HAS_EYE[count] = true;
                EYE_Y[count] = minY + entity.getStandingEyeHeight();
                EYE_ARGB[count] = ColorUtil.withAlpha(EYE_LINE_COLOR, 0.78f * distFade);

                Vec3d look = entity.getRotationVec(tickProgress);
                LOOK_DX[count] = look.x * 2.0;
                LOOK_DY[count] = look.y * 2.0;
                LOOK_DZ[count] = look.z * 2.0;
                LOOK_START_ARGB[count] = ColorUtil.withAlpha(LOOK_VEC_COLOR, 0.92f * distFade);
                LOOK_END_ARGB[count] = ColorUtil.withAlpha(LOOK_VEC_COLOR, 0.12f * distFade);
            } else {
                HAS_EYE[count] = false;
            }

            count++;
        }

        if (count == 0) {
            return;
        }

        int screenW = client.getWindow() != null ? Math.max(1, client.getWindow().getFramebufferWidth()) : 1920;
        int screenH = client.getWindow() != null ? Math.max(1, client.getWindow().getFramebufferHeight()) : 1080;
        float dpiScale = Math.max(1.0f, screenH / 1080.0f);
        float pixelWidth = lineWidth * dpiScale;

        // Проход 1: все полупрозрачные грани в едином GL_TRIANGLES + ALPHA буфере
        for (int i = 0; i < count; i++) {
            int col = FILL_ARGB[i];
            if (col != 0) {
                batch3D.drawFilledBox(
                        BOX_MIN_X[i], BOX_MIN_Y[i], BOX_MIN_Z[i],
                        BOX_MAX_X[i], BOX_MAX_Y[i], BOX_MAX_Z[i],
                        col,
                        BlendMode.ALPHA
                );
            }
        }

        // Проход 2: контуры, линии глаз и векторы взгляда (Core-Profile screen-space линии или 1px GL_LINES без glLineWidth)
        boolean useScreenQuads = lineWidth > 1.05f;
        for (int i = 0; i < count; i++) {
            double minX = BOX_MIN_X[i];
            double minY = BOX_MIN_Y[i];
            double minZ = BOX_MIN_Z[i];
            double maxX = BOX_MAX_X[i];
            double maxY = BOX_MAX_Y[i];
            double maxZ = BOX_MAX_Z[i];

            if (useScreenQuads) {
                batch3D.drawScreenBoxOutline(minX, minY, minZ, maxX, maxY, maxZ, LINE_ARGB[i], pixelWidth, screenW, screenH, BlendMode.ALPHA);
            } else {
                batch3D.drawNativeBoxOutline(minX, minY, minZ, maxX, maxY, maxZ, LINE_ARGB[i], 1.0f, BlendMode.ALPHA);
            }

            if (showEyeAndLook && HAS_EYE[i]) {
                double eyeY = EYE_Y[i];
                int eyeCol = EYE_ARGB[i];
                double cx = (minX + maxX) * 0.5;
                double cz = (minZ + maxZ) * 0.5;

                if (useScreenQuads) {
                    batch3D.drawScreenLine(minX, eyeY, minZ, maxX, eyeY, minZ, eyeCol, eyeCol, pixelWidth, screenW, screenH, BlendMode.ALPHA);
                    batch3D.drawScreenLine(maxX, eyeY, minZ, maxX, eyeY, maxZ, eyeCol, eyeCol, pixelWidth, screenW, screenH, BlendMode.ALPHA);
                    batch3D.drawScreenLine(maxX, eyeY, maxZ, minX, eyeY, maxZ, eyeCol, eyeCol, pixelWidth, screenW, screenH, BlendMode.ALPHA);
                    batch3D.drawScreenLine(minX, eyeY, maxZ, minX, eyeY, minZ, eyeCol, eyeCol, pixelWidth, screenW, screenH, BlendMode.ALPHA);
                    batch3D.drawScreenLine(
                            cx, eyeY, cz,
                            cx + LOOK_DX[i], eyeY + LOOK_DY[i], cz + LOOK_DZ[i],
                            LOOK_START_ARGB[i], LOOK_END_ARGB[i], pixelWidth, screenW, screenH, BlendMode.ALPHA
                    );
                } else {
                    batch3D.drawNativeLine(minX, eyeY, minZ, maxX, eyeY, minZ, eyeCol, 1.0f, BlendMode.ALPHA);
                    batch3D.drawNativeLine(maxX, eyeY, minZ, maxX, eyeY, maxZ, eyeCol, 1.0f, BlendMode.ALPHA);
                    batch3D.drawNativeLine(maxX, eyeY, maxZ, minX, eyeY, maxZ, eyeCol, 1.0f, BlendMode.ALPHA);
                    batch3D.drawNativeLine(minX, eyeY, maxZ, minX, eyeY, minZ, eyeCol, 1.0f, BlendMode.ALPHA);
                    batch3D.drawNativeLine(
                            cx, eyeY, cz,
                            cx + LOOK_DX[i], eyeY + LOOK_DY[i], cz + LOOK_DZ[i],
                            LOOK_START_ARGB[i], LOOK_END_ARGB[i], 1.0f, BlendMode.ALPHA
                    );
                }
            }
        }
    }
}
