package me.itzmr.nebulavisuals.module.utilities;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.VisualModule;

/**
 * Модуль NoRender (категория Utilities): отключает мешающие оверлеи экрана и визуальный шум в PvP.
 */
public class NoRenderModule extends VisualModule {
    public final BooleanSetting noFireOverlay = new BooleanSetting(
            "No Fire Overlay",
            "Hide blinding first-person fire overlay while burning",
            true
    );
    public final BooleanSetting noTotemAnimation = new BooleanSetting(
            "No Totem Animation",
            "Hide 3D floating totem animation on pop (keeps sound and particles)",
            true
    );
    public final BooleanSetting noHurtCam = new BooleanSetting(
            "No Hurt Cam",
            "Disable camera shake tilt when taking damage",
            true
    );
    public final BooleanSetting noPumpkinVignette = new BooleanSetting(
            "No Pumpkin & Vignette",
            "Hide pumpkin blur, dark vignette, portal and nausea overlays",
            true
    );

    public NoRenderModule() {
        super(
                "NoRender",
                "Disable obstructing screen overlays, hurt shake, and totem popup",
                Category.UTILITIES,
                false
        );
        addSettings(noFireOverlay, noTotemAnimation, noHurtCam, noPumpkinVignette);
    }

    public boolean shouldCancelFire() {
        return isEnabled() && noFireOverlay.getValue();
    }

    public boolean shouldCancelTotem() {
        return isEnabled() && noTotemAnimation.getValue();
    }

    public boolean shouldCancelHurtCam() {
        return isEnabled() && noHurtCam.getValue();
    }

    public boolean shouldCancelOverlays() {
        return isEnabled() && noPumpkinVignette.getValue();
    }
}
