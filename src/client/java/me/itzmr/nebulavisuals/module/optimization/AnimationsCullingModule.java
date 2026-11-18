package me.itzmr.nebulavisuals.module.optimization;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * Модуль AnimationsCulling (категория Optimization):
 * отключает затратные анимации текстур воды/лавы, морской травы/ламинарии, порталов/костров,
 * книги чародейки, луча маяка / якоря возрождения и управляет режимами перелива чар (Enchant Glint).
 */
public class AnimationsCullingModule extends VisualModule {
    public static final String GLINT_NORMAL = "Normal";
    public static final String GLINT_STATIC = "Static";
    public static final String GLINT_DISABLED = "Disabled";

    public final BooleanSetting waterAndLava = new BooleanSetting(
            "Water & Lava",
            "Freeze animated water and lava textures",
            true
    );
    public final BooleanSetting seagrassAndKelp = new BooleanSetting(
            "Seagrass & Kelp",
            "Freeze animated underwater seagrass and kelp textures while keeping plants visible",
            true
    );
    public final BooleanSetting portalsAndCampfires = new BooleanSetting(
            "Portals & Campfires",
            "Freeze portal/campfire sprite animations and hide their ambient particles",
            true
    );
    public final BooleanSetting enchantingTable = new BooleanSetting(
            "Enchanting Table",
            "Skip spinning 3D book above enchanting tables & rune glyph particles",
            true
    );
    public final BooleanSetting beaconAndAnchor = new BooleanSetting(
            "Beacon & Anchor",
            "Skip beacon beam rendering and respawn anchor texture animation",
            true
    );
    public final ModeSetting enchantGlint = new ModeSetting(
            "Enchant Glint",
            "Normal: animated glint; Static: frozen glint without scrolling; Disabled: hide glint",
            GLINT_STATIC,
            List.of(GLINT_NORMAL, GLINT_STATIC, GLINT_DISABLED)
    );

    public AnimationsCullingModule() {
        super(
                "AnimationsCulling",
                "Toggle expensive animated textures, 3D books, beacon beams & item glint",
                Category.OPTIMIZATION,
                false
        );
        addSettings(waterAndLava, seagrassAndKelp, portalsAndCampfires, enchantingTable, beaconAndAnchor, enchantGlint);
    }

    public boolean shouldFreezeSprite(String spritePath) {
        if (!isEnabled() || spritePath == null || spritePath.isEmpty()) {
            return false;
        }
        if (waterAndLava.getValue() && (spritePath.contains("water_") || spritePath.contains("lava_"))) {
            return true;
        }
        if (seagrassAndKelp.getValue() && (spritePath.contains("seagrass") || spritePath.contains("kelp"))) {
            return true;
        }
        if (portalsAndCampfires.getValue() && (spritePath.contains("portal") || spritePath.contains("campfire"))) {
            return true;
        }
        return beaconAndAnchor.getValue() && spritePath.contains("respawn_anchor");
    }

    public boolean shouldSkipEnchantingBook() {
        return isEnabled() && enchantingTable.getValue();
    }

    public boolean shouldSkipBeaconBeam() {
        return isEnabled() && beaconAndAnchor.getValue();
    }

    public boolean shouldFreezeEnchantGlint() {
        return isEnabled() && GLINT_STATIC.equals(enchantGlint.getValue());
    }

    public boolean shouldDisableEnchantGlint() {
        return isEnabled() && GLINT_DISABLED.equals(enchantGlint.getValue());
    }
}
