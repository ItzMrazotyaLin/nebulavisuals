package me.itzmr.nebulavisuals.module.optimization;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.AttachedStemBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.CartographyTableBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.DryVegetationBlock;
import net.minecraft.block.FlowerBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.KelpBlock;
import net.minecraft.block.KelpPlantBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.MushroomPlantBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.PaleMossCarpetBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.SeagrassBlock;
import net.minecraft.block.ShortPlantBlock;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.block.StainedGlassPaneBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.TallSeagrassBlock;
import net.minecraft.block.TintedGlassBlock;
import net.minecraft.block.TransparentBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BedBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;

/**
 * Модуль BlockCulling (категория Optimization):
 * отсекает по дистанции (до 256 блоков) BlockEntity и запекаемые в чанки блоки без аллокаций в горячем цикле.
 */
public class BlockCullingModule extends VisualModule {
    private static final long REBUILD_DEBOUNCE_MS = 150L;

    public final BooleanSetting signs = new BooleanSetting(
            "Signs",
            "Cull distant signs and hanging signs",
            true
    );
    public final SliderSetting signsDist = new SliderSetting(
            "Signs Dist",
            "Max render distance for signs",
            24.0, 4.0, 256.0, 4.0, "m",
            signs::getValue
    );

    public final BooleanSetting chests = new BooleanSetting(
            "Chests",
            "Cull distant chests, ender chests, barrels & shulkers",
            true
    );
    public final SliderSetting chestsDist = new SliderSetting(
            "Chests Dist",
            "Max render distance for chests & storage block entities",
            48.0, 8.0, 256.0, 4.0, "m",
            chests::getValue
    );

    public final BooleanSetting banners = new BooleanSetting(
            "Banners",
            "Cull distant banners",
            true
    );
    public final SliderSetting bannersDist = new SliderSetting(
            "Banners Dist",
            "Max render distance for banners",
            28.0, 4.0, 256.0, 4.0, "m",
            banners::getValue
    );

    public final BooleanSetting foliage = new BooleanSetting(
            "Foliage & Flowers",
            "Cull distant grass, ferns, dead bushes, flowers & mushrooms in chunk meshes",
            true
    );
    public final SliderSetting foliageDist = new SliderSetting(
            "Foliage Dist",
            "Max mesh distance for foliage & flowers",
            64.0, 8.0, 256.0, 4.0, "m",
            foliage::getValue
    );

    public final BooleanSetting flowerPots = new BooleanSetting(
            "Flower Pots",
            "Cull distant flower pots in chunk meshes",
            true
    );
    public final SliderSetting potsDist = new SliderSetting(
            "Pots Dist",
            "Max mesh distance for flower pots",
            24.0, 4.0, 256.0, 4.0, "m",
            flowerPots::getValue
    );

    public final BooleanSetting seagrassAndKelp = new BooleanSetting(
            "Seagrass & Kelp",
            "Cull distant underwater seagrass & kelp while keeping water intact",
            true
    );
    public final SliderSetting seagrassDist = new SliderSetting(
            "Seagrass Dist",
            "Max mesh distance for seagrass & kelp",
            64.0, 8.0, 256.0, 4.0, "m",
            seagrassAndKelp::getValue
    );

    public final BooleanSetting glass = new BooleanSetting(
            "Glass & Panes",
            "Cull distant glass blocks and glass panes in chunk meshes",
            true
    );
    public final SliderSetting glassDist = new SliderSetting(
            "Glass Dist",
            "Max mesh distance for glass blocks and panes",
            64.0, 8.0, 256.0, 4.0, "m",
            glass::getValue
    );

    public final BooleanSetting doors = new BooleanSetting(
            "Doors & Trapdoors",
            "Cull distant doors and trapdoors in chunk meshes",
            true
    );
    public final SliderSetting doorsDist = new SliderSetting(
            "Doors Dist",
            "Max mesh distance for doors and trapdoors",
            36.0, 8.0, 256.0, 4.0, "m",
            doors::getValue
    );

    public final BooleanSetting beds = new BooleanSetting(
            "Beds",
            "Cull distant beds",
            true
    );
    public final SliderSetting bedsDist = new SliderSetting(
            "Beds Dist",
            "Max render distance for beds",
            36.0, 8.0, 256.0, 4.0, "m",
            beds::getValue
    );

    public final BooleanSetting workstations = new BooleanSetting(
            "Workstations",
            "Cull distant crafting tables, furnaces, cauldrons & villager job blocks",
            false
    );
    public final SliderSetting workstationsDist = new SliderSetting(
            "Workstations Dist",
            "Max mesh distance for workstations",
            32.0, 8.0, 256.0, 4.0, "m",
            workstations::getValue
    );

    public final BooleanSetting cropsAndSaplings = new BooleanSetting(
            "Crops & Saplings",
            "Cull distant wheat, crops, stems, nether wart, berries & saplings",
            false
    );
    public final SliderSetting cropsDist = new SliderSetting(
            "Crops Dist",
            "Max mesh distance for crops & saplings",
            32.0, 8.0, 256.0, 4.0, "m",
            cropsAndSaplings::getValue
    );

    public final BooleanSetting carpets = new BooleanSetting(
            "Carpets",
            "Cull distant wool and moss carpets in chunk meshes",
            false
    );
    public final SliderSetting carpetsDist = new SliderSetting(
            "Carpets Dist",
            "Max mesh distance for carpets",
            32.0, 4.0, 256.0, 4.0, "m",
            carpets::getValue
    );

    private int lastTerrainSig = Integer.MIN_VALUE;
    private int lastWorldHash = 0;
    private boolean pendingTerrainUpdate = false;
    private long pendingChangeMs = 0L;

    public BlockCullingModule() {
        super(
                "BlockCulling",
                "Distance-cull block entities, foliage, pots, seagrass, glass, doors, beds & more",
                Category.OPTIMIZATION,
                false
        );
        addSettings(
                signs, signsDist,
                chests, chestsDist,
                banners, bannersDist,
                foliage, foliageDist,
                flowerPots, potsDist,
                seagrassAndKelp, seagrassDist,
                glass, glassDist,
                doors, doorsDist,
                beds, bedsDist,
                workstations, workstationsDist,
                cropsAndSaplings, cropsDist,
                carpets, carpetsDist
        );
    }

    public boolean hasActiveTerrainCulling() {
        return isEnabled() && (
                foliage.getValue()
                        || flowerPots.getValue()
                        || seagrassAndKelp.getValue()
                        || glass.getValue()
                        || doors.getValue()
                        || beds.getValue()
                        || workstations.getValue()
                        || cropsAndSaplings.getValue()
                        || carpets.getValue()
        );
    }

    public static void onClientTick(MinecraftClient client) {
        if (CullingHelper.IS_SODIUM_LOADED) {
            return;
        }
        BlockCullingModule module = ModuleManager.getBlockCullingModule();
        if (module == null || client == null || client.worldRenderer == null) {
            return;
        }
        if (client.world == null) {
            module.lastWorldHash = 0;
            return;
        }

        int worldHash = System.identityHashCode(client.world);
        if (worldHash != module.lastWorldHash) {
            module.lastWorldHash = worldHash;
            if (module.hasActiveTerrainCulling()) {
                module.pendingTerrainUpdate = true;
                module.pendingChangeMs = System.currentTimeMillis();
            }
        }

        long now = System.currentTimeMillis();
        int sig = Objects.hash(
                module.isEnabled(),
                module.foliage.getValue(), Math.round(module.foliageDist.getValue()),
                module.flowerPots.getValue(), Math.round(module.potsDist.getValue()),
                module.seagrassAndKelp.getValue(), Math.round(module.seagrassDist.getValue()),
                module.glass.getValue(), Math.round(module.glassDist.getValue()),
                module.doors.getValue(), Math.round(module.doorsDist.getValue()),
                module.beds.getValue(), Math.round(module.bedsDist.getValue()),
                module.workstations.getValue(), Math.round(module.workstationsDist.getValue()),
                module.cropsAndSaplings.getValue(), Math.round(module.cropsDist.getValue()),
                module.carpets.getValue(), Math.round(module.carpetsDist.getValue())
        );

        if (sig != module.lastTerrainSig) {
            if (module.lastTerrainSig != Integer.MIN_VALUE) {
                module.pendingTerrainUpdate = true;
                module.pendingChangeMs = now;
            }
            module.lastTerrainSig = sig;
        }

        boolean anyDragging = module.foliageDist.isDragging()
                || module.potsDist.isDragging()
                || module.seagrassDist.isDragging()
                || module.glassDist.isDragging()
                || module.doorsDist.isDragging()
                || module.bedsDist.isDragging()
                || module.workstationsDist.isDragging()
                || module.cropsDist.isDragging()
                || module.carpetsDist.isDragging();

        if (anyDragging) {
            module.pendingChangeMs = now;
        } else if (module.pendingTerrainUpdate && (now - module.pendingChangeMs) >= REBUILD_DEBOUNCE_MS) {
            module.pendingTerrainUpdate = false;
            CullingHelper.scheduleAllChunksRebuild(client);
        }
    }

    public double getMaxActiveTerrainDist() {
        if (!isEnabled()) {
            return 0.0;
        }
        double maxDist = 0.0;
        if (foliage.getValue()) maxDist = Math.max(maxDist, foliageDist.getValue());
        if (flowerPots.getValue()) maxDist = Math.max(maxDist, potsDist.getValue());
        if (seagrassAndKelp.getValue()) maxDist = Math.max(maxDist, seagrassDist.getValue());
        if (glass.getValue()) maxDist = Math.max(maxDist, glassDist.getValue());
        if (doors.getValue()) maxDist = Math.max(maxDist, doorsDist.getValue());
        if (beds.getValue()) maxDist = Math.max(maxDist, bedsDist.getValue());
        if (workstations.getValue()) maxDist = Math.max(maxDist, workstationsDist.getValue());
        if (cropsAndSaplings.getValue()) maxDist = Math.max(maxDist, cropsDist.getValue());
        if (carpets.getValue()) maxDist = Math.max(maxDist, carpetsDist.getValue());
        return maxDist;
    }

    public boolean shouldCullBlockEntity(BlockEntity be, double camX, double camY, double camZ) {
        if (!isEnabled() || be == null) {
            return false;
        }

        double maxDistSq = -1.0;
        if (be instanceof SignBlockEntity && signs.getValue()) {
            maxDistSq = signsDist.getValueSq();
        } else if ((be instanceof ChestBlockEntity
                || be instanceof EnderChestBlockEntity
                || be instanceof BarrelBlockEntity
                || be instanceof ShulkerBoxBlockEntity) && chests.getValue()) {
            maxDistSq = chestsDist.getValueSq();
        } else if (be instanceof BannerBlockEntity && banners.getValue()) {
            maxDistSq = bannersDist.getValueSq();
        } else if (be instanceof BedBlockEntity && beds.getValue()) {
            maxDistSq = bedsDist.getValueSq();
        }

        if (maxDistSq <= 0.0) {
            return false;
        }
        double distSq = be.getPos().getSquaredDistance(camX, camY, camZ);
        return distSq > maxDistSq;
    }

    public boolean shouldCullTerrainBlock(BlockState state, BlockPos pos) {
        return CullingHelper.hasCamera && shouldCullTerrainBlock(state, pos, CullingHelper.camX, CullingHelper.camY, CullingHelper.camZ);
    }

    public boolean shouldCullTerrainBlock(BlockState state, BlockPos pos, double camX, double camY, double camZ) {
        if (!isEnabled() || state == null || pos == null) {
            return false;
        }

        double maxDistSq = getMaxDistanceSqForTerrainBlock(state.getBlock());
        if (maxDistSq <= 0.0) {
            return false;
        }

        double dx = (pos.getX() + 0.5) - camX;
        double dy = (pos.getY() + 0.5) - camY;
        double dz = (pos.getZ() + 0.5) - camZ;
        return (dx * dx + dy * dy + dz * dz) > maxDistSq;
    }

    private double getMaxDistanceSqForTerrainBlock(Block block) {
        if (block instanceof SeagrassBlock
                || block instanceof TallSeagrassBlock
                || block instanceof KelpBlock
                || block instanceof KelpPlantBlock) {
            return seagrassAndKelp.getValue() ? seagrassDist.getValueSq() : -1.0;
        }

        if (block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof AttachedStemBlock
                || block instanceof NetherWartBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof SaplingBlock) {
            return cropsAndSaplings.getValue() ? cropsDist.getValueSq() : -1.0;
        }

        if (block instanceof FlowerPotBlock) {
            return flowerPots.getValue() ? potsDist.getValueSq() : -1.0;
        }

        if (block instanceof ShortPlantBlock
                || block instanceof TallPlantBlock
                || block instanceof FlowerBlock
                || block instanceof DryVegetationBlock
                || block instanceof MushroomPlantBlock
                || block instanceof PlantBlock) {
            return foliage.getValue() ? foliageDist.getValueSq() : -1.0;
        }

        if (block == Blocks.GLASS
                || block instanceof StainedGlassBlock
                || block instanceof TintedGlassBlock
                || block instanceof TransparentBlock
                || block instanceof StainedGlassPaneBlock
                || (block instanceof PaneBlock && block != Blocks.IRON_BARS)) {
            return glass.getValue() ? glassDist.getValueSq() : -1.0;
        }

        if (block instanceof DoorBlock || block instanceof TrapdoorBlock) {
            return doors.getValue() ? doorsDist.getValueSq() : -1.0;
        }

        if (block instanceof BedBlock) {
            return beds.getValue() ? bedsDist.getValueSq() : -1.0;
        }

        if (block instanceof CraftingTableBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof BrewingStandBlock
                || block instanceof CartographyTableBlock
                || block == Blocks.FLETCHING_TABLE
                || block instanceof GrindstoneBlock
                || block instanceof LecternBlock
                || block instanceof LoomBlock
                || block instanceof StonecutterBlock
                || block instanceof ComposterBlock
                || block instanceof AbstractCauldronBlock) {
            return workstations.getValue() ? workstationsDist.getValueSq() : -1.0;
        }

        if (block instanceof CarpetBlock || block instanceof PaleMossCarpetBlock) {
            return carpets.getValue() ? carpetsDist.getValueSq() : -1.0;
        }

        return -1.0;
    }
}
