package me.itzmr.nebulavisuals.module.optimization;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль UnfocusMode (категория Optimization):
 * снижает FPS когда окно игры не в фокусе и полностью пропускает 3D-рендер мира при сворачивании.
 */
public class UnfocusModeModule extends VisualModule {
    public final SliderSetting unfocusedFps = new SliderSetting(
            "Unfocused FPS",
            "Target FPS cap when the Minecraft window loses focus",
            15.0, 5.0, 60.0, 1.0, " FPS"
    );
    public final BooleanSetting skipWorldRender = new BooleanSetting(
            "Skip World Render",
            "Skip 3D world rendering when the game window is minimized",
            false
    );

    public UnfocusModeModule() {
        super(
                "UnfocusMode",
                "Throttle FPS when unfocused and optionally skip world render while minimized",
                Category.OPTIMIZATION,
                false
        );
        addSettings(unfocusedFps, skipWorldRender);
    }

    public int getUnfocusedFpsCap() {
        return Math.max(5, Math.min(60, (int) Math.round(unfocusedFps.getValue())));
    }
}
