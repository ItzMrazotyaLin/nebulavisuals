package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.ModeSetting;

import java.util.List;

/**
 * HUD-модуль Armor HUD: отображает надетую броню и её прочность
 * в горизонтальной или вертикальной стеклянной карточке.
 */
public class ArmorHudModule extends HudModule {
    public final ModeSetting orientation = new ModeSetting(
            "Orientation",
            "Armor HUD layout direction",
            "Horizontal",
            List.of("Horizontal", "Vertical")
    );
    public final ModeSetting durabilityMode = new ModeSetting(
            "Durability Mode",
            "How armor durability is displayed",
            "Bar + Percent",
            List.of("Bar + Percent", "Bar", "Percent", "Number")
    );

    public ArmorHudModule() {
        super("Armor HUD", "Displays equipped armor pieces and remaining durability", false, 10.0f, 255.0f, 1.25f);
        addSettings(orientation, durabilityMode);
    }
}
