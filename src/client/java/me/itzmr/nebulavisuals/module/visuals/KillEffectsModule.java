package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * Модуль KillEffects (категория Visuals): воспроизводит 3D-эффект при смерти существа в радиусе видимости
 * (Soul Rise, стилизованная молния Lightning или взрыв частиц Particles Burst).
 */
public class KillEffectsModule extends VisualModule {
    public final ModeSetting mode = new ModeSetting(
            "Mode",
            "3D kill visual effect style",
            "Soul Rise",
            List.of("Soul Rise", "Lightning", "Particles Burst", "Helix Vortex", "Shockwave Nova")
    );
    public final BooleanSetting onlyPlayers = new BooleanSetting(
            "Only Players",
            "Trigger kill effects only when players die",
            false
    );

    public KillEffectsModule() {
        super("KillEffects", "Spawns stylized 3D visual effects when nearby entities die", Category.VISUALS, false);
        addSettings(mode, onlyPlayers);
    }
}
