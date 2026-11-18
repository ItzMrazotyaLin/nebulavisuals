package me.itzmr.nebulavisuals.module.optimization;

import me.itzmr.nebulavisuals.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import org.itzmr.nebulavisuals.mixin.client.WorldRendererAccessor;

/**
 * Потокобезопасный помощник отсечения блоков и сущностей для главного и фоновых потоков рендера.
 * Хранит актуальную позицию камеры в volatile-полях и перестраивает локальные чанки только при смещении
 * игрока на целый чанк (>= 16 блоков), экономя ресурсы ChunkBuilder.
 */
public final class CullingHelper {
    public static final boolean IS_SODIUM_LOADED = FabricLoader.getInstance().isModLoaded("sodium");
    private static final double MOVE_REBUILD_DIST_SQ = 256.0; // >= 16.0 блоков (1 чанк)

    public static volatile double camX = 0.0;
    public static volatile double camY = 0.0;
    public static volatile double camZ = 0.0;
    public static volatile boolean hasCamera = false;

    private static double lastCullUpdateX = Double.NaN;
    private static double lastCullUpdateY = Double.NaN;
    private static double lastCullUpdateZ = Double.NaN;
    private static int moveCheckTicks = 0;

    private CullingHelper() {}

    public static void updateCameraPosition(MinecraftClient client) {
        if (client == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
            return;
        }
        if (client.world == null) {
            hasCamera = false;
            lastCullUpdateX = Double.NaN;
            return;
        }
        Vec3d pos = client.gameRenderer.getCamera().getCameraPos();
        if (pos != null) {
            camX = pos.x;
            camY = pos.y;
            camZ = pos.z;
            hasCamera = true;
        }
    }

    /**
     * Быстрая проверка отсечения блока в потоках ChunkBuilder (Vanilla + Indigo) без создания временных объектов.
     */
    public static boolean shouldCullBlock(BlockState state, BlockPos pos) {
        if (!hasCamera || state == null || pos == null) {
            return false;
        }

        BlockCullingModule blockCulling = ModuleManager.getBlockCullingModule();
        EntityCullingModule entityCulling = ModuleManager.getEntityCullingModule();
        boolean blockCullOn = blockCulling != null && blockCulling.hasActiveTerrainCulling();
        boolean fireCullOn = entityCulling != null && entityCulling.isFireCullEnabled();
        if (!blockCullOn && !fireCullOn) {
            return false;
        }

        double cx = camX;
        double cy = camY;
        double cz = camZ;

        if (fireCullOn && state.getBlock() instanceof AbstractFireBlock) {
            if (entityCulling.shouldCullFireBlock(pos, cx, cy, cz)) {
                return true;
            }
        }

        return blockCullOn && blockCulling.shouldCullTerrainBlock(state, pos, cx, cy, cz);
    }

    public static boolean shouldCullEntity(Entity entity, double x, double y, double z) {
        if (entity == null) {
            return false;
        }
        EntityCullingModule entityCulling = ModuleManager.getEntityCullingModule();
        return entityCulling != null && entityCulling.shouldCullEntity(entity, x, y, z);
    }

    public static boolean shouldCullEntity(Entity entity) {
        if (!hasCamera || entity == null) {
            return false;
        }
        return shouldCullEntity(entity, camX, camY, camZ);
    }

    public static void scheduleAllChunksRebuild(MinecraftClient client) {
        if (IS_SODIUM_LOADED || client == null || client.worldRenderer == null) {
            return;
        }
        BuiltChunkStorage storage = ((WorldRendererAccessor) client.worldRenderer).nebulavisuals$getChunks();
        if (storage != null && storage.chunks != null) {
            for (ChunkBuilder.BuiltChunk chunk : storage.chunks) {
                if (chunk != null) {
                    chunk.scheduleRebuild(false);
                }
            }
        }
        client.worldRenderer.scheduleTerrainUpdate();
    }

    public static void onClientTick(MinecraftClient client) {
        updateCameraPosition(client);

        if (IS_SODIUM_LOADED || client == null || client.world == null || client.worldRenderer == null || !hasCamera) {
            return;
        }

        moveCheckTicks++;
        if (moveCheckTicks < 10) {
            return;
        }
        moveCheckTicks = 0;

        double maxActiveDist = getMaxActiveBlockCullDistance();
        if (maxActiveDist <= 0.0) {
            lastCullUpdateX = Double.NaN;
            return;
        }

        double px = camX;
        double py = camY;
        double pz = camZ;
        if (Double.isNaN(lastCullUpdateX)) {
            lastCullUpdateX = px;
            lastCullUpdateY = py;
            lastCullUpdateZ = pz;
            return;
        }

        double dx = px - lastCullUpdateX;
        double dy = py - lastCullUpdateY;
        double dz = pz - lastCullUpdateZ;
        if ((dx * dx + dy * dy + dz * dz) < MOVE_REBUILD_DIST_SQ) {
            return;
        }

        lastCullUpdateX = px;
        lastCullUpdateY = py;
        lastCullUpdateZ = pz;

        int secX = ChunkSectionPos.getSectionCoord(px);
        int secY = ChunkSectionPos.getSectionCoord(py);
        int secZ = ChunkSectionPos.getSectionCoord(pz);
        int secRadius = Math.max(2, Math.min(8, (int) Math.ceil((maxActiveDist + 16.0) / 16.0)));
        int minSecY = Math.max(client.world.getBottomSectionCoord(), secY - 3);
        int maxSecY = Math.min(client.world.getTopSectionCoord() - 1, secY + 3);
        if (maxSecY < minSecY) {
            minSecY = client.world.getBottomSectionCoord();
            maxSecY = minSecY;
        }

        client.worldRenderer.scheduleChunkRenders(
                secX - secRadius, minSecY, secZ - secRadius,
                secX + secRadius, maxSecY, secZ + secRadius
        );
        client.worldRenderer.scheduleTerrainUpdate();
    }

    private static double getMaxActiveBlockCullDistance() {
        double maxDist = 0.0;
        EntityCullingModule entityCulling = ModuleManager.getEntityCullingModule();
        if (entityCulling != null && entityCulling.isFireCullEnabled()) {
            maxDist = Math.max(maxDist, entityCulling.fireDist.getValue());
        }
        BlockCullingModule blockCulling = ModuleManager.getBlockCullingModule();
        if (blockCulling != null && blockCulling.hasActiveTerrainCulling()) {
            maxDist = Math.max(maxDist, blockCulling.getMaxActiveTerrainDist());
        }
        return maxDist;
    }
}
