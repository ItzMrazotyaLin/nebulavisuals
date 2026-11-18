package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.BooleanSetting;

/**
 * HUD-модуль Potions HUD: отображает активные эффекты зелий с ванильными спрайтами,
 * уровнем и таймером с опциональным разделением на хорошие и плохие эффекты.
 */
public class PotionsHudModule extends HudModule {
    public final BooleanSetting splitGoodBad = new BooleanSetting(
            "Split Good & Bad",
            "Group beneficial/neutral effects separately from harmful effects",
            true
    );

    public PotionsHudModule() {
        super("Potions HUD", "Displays active status effects with timers and category grouping", false, 735.0f, 225.0f, 1.25f);
        addSettings(splitGoodBad);
    }
}
