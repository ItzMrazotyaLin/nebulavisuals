package me.itzmr.nebulavisuals.module;

/**
 * Категории модулей в Nebula Visuals.
 */
public enum Category {
    COMBAT("Combat", "Crosshair, TargetHUD & Combat visuals"),
    VISUALS("Visuals", "Player effects, particles & animations"),
    HUD("HUD", "In-game overlays, watermark & telemetry"),
    WORLD("World", "Sky, ambient lighting & environment"),
    VIEWMODEL("ViewModel", "Custom hand & held item positioning"),
    UTILITIES("Utilities", "Movement, camera & utility helpers"),
    OPTIMIZATION("Optimization", "Client FPS, culling & render optimizations"),
    SETTINGS("Settings", "ClickGUI colors, blur & client configs");

    private final String displayName;
    private final String description;

    Category(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
