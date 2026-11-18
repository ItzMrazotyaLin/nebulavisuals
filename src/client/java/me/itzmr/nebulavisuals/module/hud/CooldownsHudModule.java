package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.BooleanSetting;

/**
 * HUD-модуль Cooldowns HUD: отображает активные кулдауны предметов (жемчуг, яблоко, щит и т.д.)
 * в стеклянной карточке с иконкой, названием, таймером в секундах и полосой прогресса.
 */
public class CooldownsHudModule extends HudModule {
    public final BooleanSetting showBar = new BooleanSetting(
            "Show Bar",
            "Show remaining cooldown bar along the bottom of each row",
            true
    );

    public CooldownsHudModule() {
        super("Cooldowns HUD", "Displays active item cooldowns with timers and progress bars", false, 735.0f, 110.0f, 1.25f);
        addSettings(showBar);
    }
}
