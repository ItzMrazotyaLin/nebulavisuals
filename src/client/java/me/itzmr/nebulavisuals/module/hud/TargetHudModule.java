package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;

/**
 * HUD-модуль TargetHUD: перетаскиваемая стеклянная карточка с аватаром цели,
 * её здоровьем (включая золотые сердца Absorption), дистанцией, статусом боя (WIN/LOSE)
 * и плавной полоской HP.
 */
public class TargetHudModule extends HudModule {
    public final BooleanSetting showAbsorption = new BooleanSetting(
            "Show Absorption",
            "Display golden absorption hearts alongside health",
            true
    );
    public final BooleanSetting showDistance = new BooleanSetting(
            "Show Distance",
            "Display distance badge in meters to current target",
            true
    );
    public final BooleanSetting showStatus = new BooleanSetting(
            "Show Status",
            "Display WIN / LOSE / DRAW combat badge",
            true
    );
    public final SliderSetting smoothSpeed = new SliderSetting(
            "Smooth Speed",
            "Health bar interpolation speed",
            12.0, 5.0, 25.0, 0.5, ""
    );

    public TargetHudModule() {
        super("TargetHUD", "Frosted glass combat card with target health, skin & distance", false, 10.0f, 48.0f);
        addSettings(
                showAbsorption,
                showDistance,
                showStatus,
                smoothSpeed
        );
    }
}
