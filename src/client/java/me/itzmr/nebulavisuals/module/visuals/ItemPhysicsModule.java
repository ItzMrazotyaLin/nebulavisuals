package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль ItemPhysics (категория Visuals): убирает ванильное парение и вращение по Y у выброшенных предметов,
 * заставляя их реалистично крутиться в полёте и плоско лежать на земле.
 */
public class ItemPhysicsModule extends VisualModule {
    public final SliderSetting rotationSpeed = new SliderSetting(
            "Rotation Speed",
            "Airborne 3D tumble rotation speed multiplier",
            1.0, 0.0, 2.0, 0.05, "x"
    );

    public ItemPhysicsModule() {
        super("ItemPhysics", "Makes dropped items tumble in mid-air and lie flat on the ground", Category.VISUALS, false);
        addSettings(rotationSpeed);
    }
}
