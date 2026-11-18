package me.itzmr.nebulavisuals.module.utilities;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль Fullbright (категория Utilities): повышает гамму лайтмапа мира
 * без использования зелья ночного зрения и с мгновенным откатом к настройкам игры.
 */
public class FullbrightModule extends VisualModule {
    public final SliderSetting brightness = new SliderSetting(
            "Brightness",
            "Minimum lightmap gamma boost in dark areas",
            3.5, 1.0, 5.0, 0.1, "x"
    );

    public FullbrightModule() {
        super("Fullbright", "See clearly in dark caves and areas without night vision potion", Category.UTILITIES, false);
        addSettings(brightness);
    }
}
