package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

import java.util.List;

/**
 * Модуль BossbarChanger (категория HUD):
 * перемещает оригинальный ванильный BossBar по горизонтали (Left, Center, Right)
 * и при центральной позиции аккуратно сдвигает его ниже Watermark.
 */
public class BossbarChangerModule extends VisualModule {
    public final ModeSetting position = new ModeSetting(
            "Position",
            "Horizontal alignment for vanilla boss bars",
            "Center",
            List.of("Center", "Left", "Right")
    );

    public BossbarChangerModule() {
        super("BossbarChanger", "Move vanilla boss bars to Left, Center, or Right", Category.HUD, false);
        addSettings(position);
    }
}
