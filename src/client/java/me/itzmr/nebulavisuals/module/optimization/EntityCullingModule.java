package me.itzmr.nebulavisuals.module.optimization;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/**
 * Модуль EntityCulling (категория Optimization):
 * отсекает по кэшированным квадратам дистанций рамки, стойки, дроп/опыт, мобов и блоки огня,
 * никогда не скрывая игроков, снаряды и эндер-кристаллы.
 */
public class EntityCullingModule extends VisualModule {
    private static final long REBUILD_DEBOUNCE_MS = 150L;

    public static volatile double cachedCamX = 0.0;
    public static volatile double cachedCamY = 0.0;
    public static volatile double cachedCamZ = 0.0;
    public static volatile boolean hasCachedCam = false;

    public final BooleanSetting itemFrames = new BooleanSetting(
            "Item Frames",
            "Cull distant item frames and glow item frames",
            true
    );
    public final SliderSetting framesDist = new SliderSetting(
            "Frames Dist",
            "Max render distance for item frames",
            20.0, 4.0, 128.0, 2.0, "m",
            itemFrames::getValue
    );

    public final BooleanSetting armorStands = new BooleanSetting(
            "Armor Stands",
            "Cull distant armor stands",
            true
    );
    public final SliderSetting standsDist = new SliderSetting(
            "Stands Dist",
            "Max render distance for armor stands",
            20.0, 4.0, 128.0, 2.0, "m",
            armorStands::getValue
    );

    public final BooleanSetting droppedItems = new BooleanSetting(
            "Dropped Items",
            "Cull distant dropped items and XP orbs",
            true
    );
    public final SliderSetting itemsDist = new SliderSetting(
            "Items Dist",
            "Max render distance for dropped items and XP orbs",
            24.0, 4.0, 128.0, 2.0, "m",
            droppedItems::getValue
    );

    public final BooleanSetting mobs = new BooleanSetting(
            "Mobs & Monsters",
            "Cull distant mobs and monsters (never hides players)",
            true
    );
    public final SliderSetting mobsDist = new SliderSetting(
            "Mobs Dist",
            "Max render distance for mobs and monsters",
            36.0, 8.0, 128.0, 2.0, "m",
            mobs::getValue
    );

    public final BooleanSetting fireBlocks = new BooleanSetting(
            "Fire Blocks",
            "Skip distant fire and soul fire blocks during chunk section meshing",
            true
    );
    public final SliderSetting fireDist = new SliderSetting(
            "Fire Dist",
            "Max mesh distance for fire and soul fire blocks",
            24.0, 4.0, 128.0, 2.0, "m",
            fireBlocks::getValue
    );

    private int lastTerrainSig = Integer.MIN_VALUE;
    private int lastWorldHash = 0;
    private boolean pendingTerrainUpdate = false;
    private long pendingChangeMs = 0L;

    public EntityCullingModule() {
        super(
                "EntityCulling",
                "Distance-cull frames, armor stands, items, mobs & fire while keeping combat safe",
                Category.OPTIMIZATION,
                false
        );
        addSettings(
                itemFrames, framesDist,
                armorStands, standsDist,
                droppedItems, itemsDist,
                mobs, mobsDist,
                fireBlocks, fireDist
        );
    }

    public boolean isFireCullEnabled() {
        return isEnabled() && fireBlocks.getValue();
    }

    public static void updateCameraPosition(MinecraftClient client) {
        if (client == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
            return;
        }
        if (client.world == null) {
            hasCachedCam = false;
            return;
        }
        Vec3d pos = client.gameRenderer.getCamera().getCameraPos();
        if (pos != null) {
            cachedCamX = pos.x;
            cachedCamY = pos.y;
            cachedCamZ = pos.z;
            hasCachedCam = true;
        }
    }

    public static void onClientTick(MinecraftClient client) {
        updateCameraPosition(client);

        EntityCullingModule module = ModuleManager.getEntityCullingModule();
        if (CullingHelper.IS_SODIUM_LOADED || module == null || client == null || client.worldRenderer == null) {
            return;
        }
        if (client.world == null) {
            module.lastWorldHash = 0;
            return;
        }

        int worldHash = System.identityHashCode(client.world);
        if (worldHash != module.lastWorldHash) {
            module.lastWorldHash = worldHash;
            if (module.isFireCullEnabled()) {
                module.pendingTerrainUpdate = true;
                module.pendingChangeMs = System.currentTimeMillis();
            }
        }

        long now = System.currentTimeMillis();
        int sig = Objects.hash(module.isEnabled(), module.fireBlocks.getValue(), Math.round(module.fireDist.getValue()));
        if (sig != module.lastTerrainSig) {
            if (module.lastTerrainSig != Integer.MIN_VALUE) {
                module.pendingTerrainUpdate = true;
                module.pendingChangeMs = now;
            }
            module.lastTerrainSig = sig;
        }

        if (module.fireDist.isDragging()) {
            module.pendingChangeMs = now;
        } else if (module.pendingTerrainUpdate && (now - module.pendingChangeMs) >= REBUILD_DEBOUNCE_MS) {
            module.pendingTerrainUpdate = false;
            CullingHelper.scheduleAllChunksRebuild(client);
        }
    }

    public boolean shouldCullEntity(Entity entity, double camX, double camY, double camZ) {
        if (!isEnabled() || entity == null) {
            return false;
        }
        if (entity instanceof PlayerEntity
                || entity instanceof ProjectileEntity
                || entity instanceof EndCrystalEntity) {
            return false;
        }

        double maxDistSq = -1.0;
        if (entity instanceof ItemFrameEntity && itemFrames.getValue()) {
            maxDistSq = framesDist.getValueSq();
        } else if (entity instanceof ArmorStandEntity && armorStands.getValue()) {
            maxDistSq = standsDist.getValueSq();
        } else if ((entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity) && droppedItems.getValue()) {
            maxDistSq = itemsDist.getValueSq();
        } else if (entity instanceof MobEntity && mobs.getValue()) {
            maxDistSq = mobsDist.getValueSq();
        }

        return maxDistSq > 0.0 && entity.squaredDistanceTo(camX, camY, camZ) > maxDistSq;
    }

    public boolean shouldCullFireBlock(BlockPos pos, double camX, double camY, double camZ) {
        if (!isEnabled() || !fireBlocks.getValue() || pos == null) {
            return false;
        }
        double dx = (pos.getX() + 0.5) - camX;
        double dy = (pos.getY() + 0.5) - camY;
        double dz = (pos.getZ() + 0.5) - camZ;
        return (dx * dx + dy * dy + dz * dz) > fireDist.getValueSq();
    }
}
