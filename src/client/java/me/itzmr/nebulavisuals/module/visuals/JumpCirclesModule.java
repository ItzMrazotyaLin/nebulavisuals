package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * 3D-кольца отрыва от земли при прыжке (JumpCircles) в категории Visuals.
 * Отрисовываются в мировом пространстве через {@code RenderBatch3D} с выбором визуального режима.
 */
public class JumpCirclesModule extends VisualModule {
    public final ModeSetting mode = new ModeSetting(
            "Mode",
            "JumpCircles 3D visual style",
            "Classic",
            List.of("Classic", "Ripple", "Polygon", "Segmented")
    );
    public final SliderSetting maxRadius = new SliderSetting(
            "Max Radius",
            "Maximum ring expansion radius in blocks",
            1.8, 1.0, 3.5, 0.05, "m"
    );
    public final SliderSetting duration = new SliderSetting(
            "Duration",
            "Ring expansion and fade lifetime in seconds",
            0.9, 0.4, 2.0, 0.05, "s"
    );
    public final BooleanSetting otherPlayers = new BooleanSetting(
            "Other Players",
            "Spawn expanding 3D rings when nearby players jump",
            false
    );

    public JumpCirclesModule() {
        super("JumpCircles", "Smooth expanding 3D energy rings under jumping players", Category.VISUALS, false);
        addSettings(
                mode,
                maxRadius,
                duration,
                otherPlayers
        );
    }
}
