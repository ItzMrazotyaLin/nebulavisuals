package me.itzmr.nebulavisuals.render.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.visuals.BlockOverlayModule;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;
import me.itzmr.nebulavisuals.render.engine3d.BlendMode;
import me.itzmr.nebulavisuals.render.engine3d.RenderBatch3D;
import me.itzmr.nebulavisuals.render.engine3d.SphereBatch3D;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;

/**
 * 3D-рендерер модуля {@link BlockOverlayModule}: плавно перетекающая между блоками
 * неоновая подсветка формы {@link VoxelShape} на базе {@link RenderBatch3D}.
 */
public final class BlockOverlayRenderer {
    private static final double BOX_EXPAND = 0.002;

    private static long lastTimeNs = 0L;
    private static ClientWorld lastWorld = null;
    private static boolean hasBox = false;
    private static float fadeAlpha = 0.0f;

    private static double curMinX, curMinY, curMinZ;
    private static double curMaxX, curMaxY, curMaxZ;

    private BlockOverlayRenderer() {}

    public static void onRender3D(WorldRenderContext context, SphereBatch3D spheres, RenderBatch3D batch3D) {
        MinecraftClient client = MinecraftClient.getInstance();
        BlockOverlayModule module = ModuleManager.getBlockOverlayModule();
        if (client == null || client.world == null || client.player == null) {
            resetState();
            return;
        }

        if (client.world != lastWorld) {
            resetState();
            lastWorld = client.world;
        }

        if (module == null || !module.isEnabled()) {
            resetState();
            return;
        }

        long now = System.nanoTime();
        float dt = lastTimeNs == 0L ? 0.016f : Math.clamp((now - lastTimeNs) * 1e-9f, 0.0f, 0.05f);
        lastTimeNs = now;

        Box targetWorldBox = resolveTargetedBlockBox(client);
        if (targetWorldBox != null) {
            if (!hasBox || !module.smoothSlide.getValue()) {
                curMinX = targetWorldBox.minX;
                curMinY = targetWorldBox.minY;
                curMinZ = targetWorldBox.minZ;
                curMaxX = targetWorldBox.maxX;
                curMaxY = targetWorldBox.maxY;
                curMaxZ = targetWorldBox.maxZ;
                hasBox = true;
            } else {
                double t = 1.0 - Math.exp(-22.0 * dt);
                curMinX += (targetWorldBox.minX - curMinX) * t;
                curMinY += (targetWorldBox.minY - curMinY) * t;
                curMinZ += (targetWorldBox.minZ - curMinZ) * t;
                curMaxX += (targetWorldBox.maxX - curMaxX) * t;
                curMaxY += (targetWorldBox.maxY - curMaxY) * t;
                curMaxZ += (targetWorldBox.maxZ - curMaxZ) * t;
            }
            fadeAlpha += (1.0f - fadeAlpha) * (1.0f - (float) Math.exp(-18.0f * dt));
        } else {
            fadeAlpha += (0.0f - fadeAlpha) * (1.0f - (float) Math.exp(-16.0f * dt));
            if (fadeAlpha <= 0.005f) {
                hasBox = false;
                return;
            }
        }

        if (!hasBox || fadeAlpha <= 0.005f) {
            return;
        }

        int accent = ClientSettings.getAccentColor();
        int accentBright = ColorUtil.lerp(accent, 0xFFFFFFFF, 0.35f);
        String mode = module.renderMode.getValue();
        float fillOpacity = module.fillOpacity.getValue().floatValue();

        if ("Fill".equals(mode) || "Both".equals(mode)) {
            batch3D.drawFilledBox(
                    curMinX, curMinY, curMinZ,
                    curMaxX, curMaxY, curMaxZ,
                    ColorUtil.withAlpha(accent, fillOpacity * fadeAlpha),
                    BlendMode.ALPHA
            );
        }

        if ("Outline".equals(mode) || "Both".equals(mode)) {
            batch3D.drawBoxOutline(
                    curMinX, curMinY, curMinZ,
                    curMaxX, curMaxY, curMaxZ,
                    ColorUtil.withAlpha(accentBright, 0.92f * fadeAlpha),
                    2.0f,
                    BlendMode.ADDITIVE
            );
        }
    }

    private static Box resolveTargetedBlockBox(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        VoxelShape shape = state.getOutlineShape(client.world, pos, ShapeContext.of(client.player));
        if (shape.isEmpty()) {
            return null;
        }
        Box b = shape.getBoundingBox();
        return new Box(
                pos.getX() + b.minX - BOX_EXPAND,
                pos.getY() + b.minY - BOX_EXPAND,
                pos.getZ() + b.minZ - BOX_EXPAND,
                pos.getX() + b.maxX + BOX_EXPAND,
                pos.getY() + b.maxY + BOX_EXPAND,
                pos.getZ() + b.maxZ + BOX_EXPAND
        );
    }

    private static void resetState() {
        hasBox = false;
        fadeAlpha = 0.0f;
        lastTimeNs = 0L;
        lastWorld = null;
    }
}
