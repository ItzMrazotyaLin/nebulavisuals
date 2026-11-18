package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * 3D-частицы при попадании по цели (HitParticles) в категории Visuals.
 * Поддерживает 6 форм (Orbs, Stars, Hearts, Cubes, Crosses, Sparks) с физикой гравитации, трения и отскока от блоков.
 */
public class HitParticlesModule extends VisualModule {
    public final ModeSetting trigger = new ModeSetting(
            "Trigger",
            "Hits that trigger 3D particle bursts",
            "All Hits",
            List.of("All Hits", "Criticals Only")
    );
    public final ModeSetting shape = new ModeSetting(
            "Shape",
            "3D particle geometry shape",
            "Orbs",
            List.of("Orbs", "Stars", "Hearts", "Cubes", "Crosses", "Sparks")
    );
    public final SliderSetting amount = new SliderSetting(
            "Amount",
            "Number of 3D spheres spawned per hit",
            14.0, 5.0, 30.0, 1.0, ""
    );
    public final SliderSetting size = new SliderSetting(
            "Size",
            "Scale multiplier of spawned 3D impact spheres",
            0.45, 0.2, 1.5, 0.05, "x"
    );
    public final SliderSetting lifeTime = new SliderSetting(
            "Life Time",
            "Particle lifetime in seconds before fading out",
            1.2, 0.5, 2.5, 0.05, "s"
    );
    public final BooleanSetting physicsBounce = new BooleanSetting(
            "Physics Bounce",
            "Bounce particles off solid blocks underneath",
            true
    );

    public HitParticlesModule() {
        super("HitParticles", "Glowing 3D impact particles with shapes, gravity and block bounce", Category.VISUALS, false);
        addSettings(
                trigger,
                shape,
                amount,
                size,
                lifeTime,
                physicsBounce
        );
    }
}
