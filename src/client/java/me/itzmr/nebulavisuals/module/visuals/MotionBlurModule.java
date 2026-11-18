package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import me.itzmr.nebulavisuals.render.visuals.MotionBlurRenderer;

/**
 * Модуль Motion Blur (категория Visuals): Depth-Reprojected Motion Blur
 * по матрицам предыдущего и текущего кадров и буферу глубины (без смазывания руки и HUD).
 */
public class MotionBlurModule extends VisualModule {
    public final SliderSetting strength = new SliderSetting(
            "Strength",
            "Reprojection motion blur intensity multiplier",
            0.5, 0.1, 1.0, 0.05, ""
    );
    public final SliderSetting samples = new SliderSetting(
            "Samples",
            "Number of ray samples along motion velocity vector",
            14.0, 8.0, 24.0, 2.0, ""
    );

    public MotionBlurModule() {
        super("Motion Blur", "Camera matrix & depth-reprojected motion blur for 3D world", Category.VISUALS, false);
        addSettings(strength, samples);
    }

    @Override
    protected void onDisable() {
        MotionBlurRenderer.resetHistory();
    }
}
