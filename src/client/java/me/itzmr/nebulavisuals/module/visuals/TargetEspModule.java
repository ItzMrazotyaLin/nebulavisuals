package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * 3D-маркер цели (TargetESP) в категории Visuals:
 * режим Orbs рисует светящиеся орбитальные сферы с шлейфом через {@code SphereBatch3D},
 * режим Ring рисует пульсирующее вертикально сканирующее кольцо через {@code RenderBatch3D}.
 */
public class TargetEspModule extends VisualModule {
    public final ModeSetting mode = new ModeSetting(
            "Mode",
            "TargetESP 3D rendering style",
            "Orbs",
            List.of("Orbs", "Ring", "Spirals", "Halo", "Frame")
    );
    public final SliderSetting speed = new SliderSetting(
            "Speed",
            "Orbit and scan animation speed multiplier",
            1.2, 0.5, 3.0, 0.05, "x"
    );
    public final SliderSetting size = new SliderSetting(
            "Size",
            "3D marker scale multiplier",
            1.0, 0.5, 2.0, 0.05, "x"
    );
    public final BooleanSetting onlyInCombat = new BooleanSetting(
            "Only In Combat",
            "Show TargetESP only after attacking a target instead of on hover",
            false
    );

    public TargetEspModule() {
        super("TargetESP", "3D orbiting orbs and pulsing scan ring around combat target", Category.VISUALS, false);
        addSettings(
                mode,
                speed,
                size,
                onlyInCombat
        );
    }
}
