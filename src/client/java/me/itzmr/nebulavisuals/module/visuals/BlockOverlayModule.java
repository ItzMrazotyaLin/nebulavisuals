package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * Модуль BlockOverlay (категория Visuals): современная неоновая 3D-подсветка выделенного блока
 * с плавным перетеканием координат при наведении на соседние блоки.
 */
public class BlockOverlayModule extends VisualModule {
    public final ModeSetting renderMode = new ModeSetting(
            "Render Mode",
            "Block highlight rendering style",
            "Both",
            List.of("Outline", "Fill", "Both")
    );
    public final SliderSetting fillOpacity = new SliderSetting(
            "Fill Opacity",
            "Opacity of filled block faces",
            0.18, 0.05, 0.6, 0.02, ""
    );
    public final BooleanSetting smoothSlide = new BooleanSetting(
            "Smooth Slide",
            "Smoothly interpolate box bounds between targeted blocks",
            true
    );

    public BlockOverlayModule() {
        super("BlockOverlay", "Smooth neon 3D block highlight with interpolated box sliding", Category.VISUALS, false);
        addSettings(
                renderMode,
                fillOpacity,
                smoothSlide
        );
    }
}
