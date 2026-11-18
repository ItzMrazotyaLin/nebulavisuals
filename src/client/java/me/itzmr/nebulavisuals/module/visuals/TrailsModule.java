package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * Модуль Trails (категория Visuals): плавный светящийся 3D-шлейф за игроком
 * в 4 стилях (Ribbon, Floor, Orbs, Helix) и тонкие шлейфы за жемчугом Эндера и стрелами.
 */
public class TrailsModule extends VisualModule {
    public final ModeSetting style = new ModeSetting(
            "Style",
            "Trails 3D rendering style behind the player",
            "Ribbon",
            List.of("Ribbon", "Floor", "Orbs", "Helix")
    );
    public final SliderSetting length = new SliderSetting(
            "Length",
            "Trail segment lifetime in seconds",
            0.8, 0.3, 2.0, 0.05, "s"
    );
    public final SliderSetting height = new SliderSetting(
            "Height",
            "Vertical height of the player 3D ribbon in blocks",
            0.9, 0.2, 1.8, 0.05, "m"
    );
    public final BooleanSetting firstPerson = new BooleanSetting(
            "First Person",
            "Render player trail while in first-person view",
            true
    );
    public final BooleanSetting projectiles = new BooleanSetting(
            "Projectiles",
            "Draw thin 3D trails behind Ender Pearls and Arrows",
            true
    );

    public TrailsModule() {
        super("Trails", "Smooth glowing 3D motion ribbons behind player and projectiles", Category.VISUALS, false);
        addSettings(
                style,
                length,
                height,
                firstPerson,
                projectiles
        );
    }
}
