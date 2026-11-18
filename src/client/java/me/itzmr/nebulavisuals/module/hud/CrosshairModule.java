package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * Модуль Crosshair (категория HUD): векторный прицел поверх кастомного 2D-движка
 * с опциональной обводкой, динамическим расширением и подсветкой при наведении на игрока.
 */
public class CrosshairModule extends VisualModule {
    public final ModeSetting style = new ModeSetting(
            "Style",
            "Crosshair vector shape style",
            "Cross + Dot",
            List.of("Cross", "Dot", "Cross + Dot", "Circle")
    );
    public final SliderSetting length = new SliderSetting(
            "Length",
            "Length of crosshair arms (or ring radius for Circle)",
            6.0, 2.0, 14.0, 0.5, "px"
    );
    public final SliderSetting thickness = new SliderSetting(
            "Thickness",
            "Line thickness of crosshair elements",
            1.5, 1.0, 4.0, 0.25, "px"
    );
    public final SliderSetting gap = new SliderSetting(
            "Gap",
            "Base gap from screen center",
            3.0, 1.0, 10.0, 0.5, "px"
    );
    public final BooleanSetting dynamic = new BooleanSetting(
            "Dynamic",
            "Smoothly expand gap on movement, jump, and attack cooldown",
            true
    );
    public final BooleanSetting outline = new BooleanSetting(
            "Outline",
            "Draw 1px dark outline around crosshair elements",
            false
    );
    public final BooleanSetting showInThirdPerson = new BooleanSetting(
            "Show In Third Person",
            "Keep crosshair visible in third-person perspectives",
            false
    );
    public final ModeSetting colorMode = new ModeSetting(
            "Color Mode",
            "Crosshair color behavior",
            "White + Accent on Target",
            List.of("White + Accent on Target", "Always Accent", "White")
    );

    public CrosshairModule() {
        super("Crosshair", "Crisp vector crosshair with dynamic gap and target highlight", Category.HUD, false);
        addSettings(
                style,
                length,
                thickness,
                gap,
                dynamic,
                outline,
                showInThirdPerson,
                colorMode
        );
    }
}
