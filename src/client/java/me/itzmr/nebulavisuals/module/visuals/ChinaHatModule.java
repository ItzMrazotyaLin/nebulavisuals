package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль ChinaHat (категория Visuals): полупрозрачный 3D-конус с градиентом и светящимся ободком
 * над головой игрока (при виде от третьего лица) и опционально над текущей целью.
 */
public class ChinaHatModule extends VisualModule {
    public final SliderSetting radius = new SliderSetting(
            "Radius",
            "Cone base radius in blocks",
            0.65, 0.4, 1.1, 0.05, "m"
    );
    public final SliderSetting height = new SliderSetting(
            "Height",
            "Cone height in blocks",
            0.32, 0.15, 0.6, 0.02, "m"
    );
    public final SliderSetting opacity = new SliderSetting(
            "Opacity",
            "Hat cone transparency",
            0.45, 0.15, 0.9, 0.05, ""
    );
    public final BooleanSetting showOnTarget = new BooleanSetting(
            "Show On Target",
            "Also render ChinaHat above active combat target",
            false
    );

    public ChinaHatModule() {
        super("ChinaHat", "Translucent 3D gradient cone hat above head in third person", Category.VISUALS, false);
        addSettings(
                radius,
                height,
                opacity,
                showOnTarget
        );
    }
}
