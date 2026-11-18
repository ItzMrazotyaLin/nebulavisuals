package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль Smaller Rain (категория Visuals): делает дождь и снег тоньше и прозрачнее,
 * уменьшая визуальный шум в PvP без отключения атмосферы.
 */
public class SmallerRainModule extends VisualModule {
    public final SliderSetting scale = new SliderSetting(
            "Scale",
            "Width multiplier for rain streaks and snowflakes",
            0.45, 0.2, 1.0, 0.05, "x"
    );
    public final SliderSetting opacity = new SliderSetting(
            "Opacity",
            "Alpha transparency multiplier for precipitation",
            0.55, 0.1, 1.0, 0.05, ""
    );

    public SmallerRainModule() {
        super("Smaller Rain", "Make rain and snow thinner and more transparent for clear PvP visibility", Category.VISUALS, false);
        addSettings(scale, opacity);
    }
}
