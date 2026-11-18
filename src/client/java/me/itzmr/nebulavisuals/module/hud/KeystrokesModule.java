package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.BooleanSetting;

/**
 * HUD-модуль Keystrokes: минималистичный стеклянный виджет нажатий клавиш
 * (W, A, S, D, LMB, RMB, SPACE) со счётчиком CPS и поддержкой перетаскивания в чате.
 */
public class KeystrokesModule extends HudModule {
    public final BooleanSetting showMouse = new BooleanSetting(
            "Show Mouse",
            "Show LMB and RMB mouse buttons",
            true
    );
    public final BooleanSetting showSpace = new BooleanSetting(
            "Show Space",
            "Show wide Spacebar indicator below mouse row",
            true
    );
    public final BooleanSetting showCps = new BooleanSetting(
            "Show CPS",
            "Display clicks-per-second counter inside LMB and RMB",
            true
    );

    public KeystrokesModule() {
        super("Keystrokes", "Minimalist frosted glass WASD, mouse & CPS indicator", false, 10.0f, 110.0f);
        addSettings(
                showMouse,
                showSpace,
                showCps
        );
    }
}
