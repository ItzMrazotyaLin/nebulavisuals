package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль Hitboxes (категория Visuals): заменяет белые ванильные хитбоксы F3+B на
 * стильные 3D-рамки с полупрозрачной заливкой, подсветкой урона, линией глаз и вектором взгляда.
 */
public class HitboxesModule extends VisualModule {
    public final BooleanSetting players = new BooleanSetting(
            "Players",
            "Render custom 3D hitboxes for players",
            true
    );
    public final BooleanSetting mobs = new BooleanSetting(
            "Mobs",
            "Render custom 3D hitboxes for living mobs and animals",
            true
    );
    public final BooleanSetting items = new BooleanSetting(
            "Items",
            "Render custom 3D hitboxes for dropped item entities",
            false
    );
    public final BooleanSetting self = new BooleanSetting(
            "Self",
            "Render custom hitbox for your own player in third-person",
            false
    );
    public final SliderSetting fillAlpha = new SliderSetting(
            "Fill Alpha",
            "Translucent inner box fill opacity",
            0.12, 0.0, 0.5, 0.02, ""
    );
    public final SliderSetting lineWidth = new SliderSetting(
            "Line Width",
            "3D hitbox outline thickness",
            1.8, 1.0, 4.0, 0.1, ""
    );
    public final SliderSetting maxDistance = new SliderSetting(
            "Max Distance",
            "Maximum render distance and smooth fade cutoff",
            64.0, 16.0, 128.0, 2.0, "m"
    );
    public final BooleanSetting damageHighlight = new BooleanSetting(
            "Damage Highlight",
            "Highlight hitboxes red while taking damage",
            true
    );
    public final BooleanSetting eyeAndLookVector = new BooleanSetting(
            "Eye & Look Vector",
            "Render eye-level horizontal ring and look direction vector",
            false
    );

    public HitboxesModule() {
        super("Hitboxes", "Custom translucent glowing 3D hitboxes with optional eye line & hurt flash", Category.VISUALS, false);
        addSettings(players, mobs, items, self, fillAlpha, lineWidth, maxDistance, damageHighlight, eyeAndLookVector);
    }
}
