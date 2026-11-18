package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль ViewModel (категория Visuals): настройка смещения, масштаба
 * и фильтрации предметов от первого лица с учётом правой/левой руки.
 */
public class ViewModelModule extends VisualModule {
    public final BooleanSetting onlySword = new BooleanSetting(
            "Only Sword",
            "Apply ViewModel offsets and scale only when holding a sword",
            false
    );
    public final SliderSetting mainHandX = new SliderSetting(
            "Main Hand X",
            "Horizontal offset of the main hand",
            0.0, -1.0, 1.0, 0.02, ""
    );
    public final SliderSetting mainHandY = new SliderSetting(
            "Main Hand Y",
            "Vertical offset of the main hand",
            0.0, -1.0, 1.0, 0.02, ""
    );
    public final SliderSetting mainHandZ = new SliderSetting(
            "Main Hand Z",
            "Depth offset of the main hand",
            0.0, -1.0, 1.0, 0.02, ""
    );
    public final SliderSetting offHandX = new SliderSetting(
            "Off Hand X",
            "Horizontal offset of the off hand",
            0.0, -1.0, 1.0, 0.02, ""
    );
    public final SliderSetting offHandY = new SliderSetting(
            "Off Hand Y",
            "Vertical offset of the off hand",
            0.0, -1.0, 1.0, 0.02, ""
    );
    public final SliderSetting offHandZ = new SliderSetting(
            "Off Hand Z",
            "Depth offset of the off hand",
            0.0, -1.0, 1.0, 0.02, ""
    );
    public final SliderSetting itemScale = new SliderSetting(
            "Item Scale",
            "Scale multiplier for first-person held items",
            0.85, 0.3, 1.5, 0.02, "x"
    );

    public ViewModelModule() {
        super("ViewModel", "Customize first-person hand position and held item scale", Category.VISUALS, false);
        addSettings(
                onlySword,
                mainHandX,
                mainHandY,
                mainHandZ,
                offHandX,
                offHandY,
                offHandZ,
                itemScale
        );
    }
}
