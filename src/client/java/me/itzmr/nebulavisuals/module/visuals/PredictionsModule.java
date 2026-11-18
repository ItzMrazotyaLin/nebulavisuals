package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль Predictions (категория Visuals): рассчитывает точную баллистику бросков/выстрелов
 * для эндер-жемчуга, лука, арбалета, трезубца, снежков, яиц и зелий, рисуя объёмную
 * светящуюся 3D-ленту, кольцо по нормали поверхности и индикатор времени полёта.
 */
public class PredictionsModule extends VisualModule {
    public final BooleanSetting showLandingRing = new BooleanSetting(
            "Show Landing Ring",
            "Render glowing 3D landing marker and flight time badge at impact point",
            true
    );
    public final SliderSetting lineWidth = new SliderSetting(
            "Line Width",
            "Predicted trajectory screen-space line thickness (px)",
            2.0, 1.0, 4.0, 0.1, "px"
    );

    public PredictionsModule() {
        super("Predictions", "Simulates accurate 3D trajectories & impact markers for held projectiles", Category.VISUALS, false);
        addSettings(showLandingRing, lineWidth);
    }
}
