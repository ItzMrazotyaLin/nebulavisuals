package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.config.ClientSettings;
import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import me.itzmr.nebulavisuals.render.engine2d.util.ColorUtil;

import java.util.List;

/**
 * Модуль CustomSky (категория Visuals): процедурный GLSL-скайбокс в мировых координатах
 * с различными геометрическими паттернами (Nebula, Galactic Core, Northern Lights, Solar Eclipse)
 * и автоматической синхронизацией дальнего тумана с горизонтом.
 */
public class CustomSkyModule extends VisualModule {
    public final ModeSetting skyMode = new ModeSetting(
            "Sky Mode",
            "Procedural skybox pattern and atmospheric palette",
            "Nebula Core",
            List.of(
                    "Nebula Core",
                    "Accent Nebula",
                    "Galactic Core",
                    "Northern Lights",
                    "Solar Eclipse",
                    "Aurora",
                    "Cyberpunk",
                    "Deep Space",
                    "Blood Moon"
            )
    );
    public final SliderSetting starsIntensity = new SliderSetting(
            "Stars Intensity",
            "Brightness and density of procedural stars",
            1.0, 0.0, 2.0, 0.05, "x"
    );
    public final SliderSetting nebulaIntensity = new SliderSetting(
            "Nebula Intensity",
            "Saturation and glow of procedural sky patterns",
            1.0, 0.0, 2.0, 0.05, "x"
    );
    public final BooleanSetting fogSync = new BooleanSetting(
            "Fog Sync",
            "Match distant world fog color with the skybox horizon",
            true
    );
    public final BooleanSetting customTime = new BooleanSetting(
            "Custom Time",
            "Override client world time of day",
            true
    );
    public final SliderSetting time = new SliderSetting(
            "Time",
            "Client world time of day (ticks)",
            18000, 0, 24000, 500, "t"
    );
    public final SliderSetting fogDistance = new SliderSetting(
            "Fog Distance",
            "Atmospheric fog distance multiplier",
            1.0, 0.3, 2.0, 0.05, "x"
    );

    public CustomSkyModule() {
        super("CustomSky", "World-locked procedural shader skybox with multiple cosmic patterns", Category.VISUALS, false);
        addSettings(
                skyMode,
                starsIntensity,
                nebulaIntensity,
                fogSync,
                customTime,
                time,
                fogDistance
        );
    }

    public boolean hasCustomAtmosphere() {
        return isEnabled();
    }

    public boolean isFogSyncEnabled() {
        return isEnabled() && fogSync.getValue();
    }

    public int resolvePatternMode() {
        return switch (skyMode.getValue()) {
            case "Galactic Core" -> 1;
            case "Northern Lights" -> 2;
            case "Solar Eclipse" -> 3;
            default -> 0;
        };
    }

    public int resolveZenithColorArgb() {
        int accent = ClientSettings.getAccentColor();
        return switch (skyMode.getValue()) {
            case "Accent Nebula" -> ColorUtil.lerp(0xFF040510, accent, 0.10f);
            case "Galactic Core" -> 0xFF03040E;
            case "Northern Lights" -> 0xFF020814;
            case "Solar Eclipse" -> 0xFF050209;
            case "Aurora" -> 0xFF030B14;
            case "Cyberpunk" -> 0xFF04071B;
            case "Deep Space" -> 0xFF020308;
            case "Blood Moon" -> 0xFF0D0206;
            default -> 0xFF040310; // Nebula Core
        };
    }

    public int resolveHorizonColorArgb() {
        int accent = ClientSettings.getAccentColor();
        return switch (skyMode.getValue()) {
            case "Accent Nebula" -> ColorUtil.lerp(0xFF101324, accent, 0.42f);
            case "Galactic Core" -> 0xFF13102B;
            case "Northern Lights" -> 0xFF08242C;
            case "Solar Eclipse" -> 0xFF280E18;
            case "Aurora" -> 0xFF0D3B3E;
            case "Cyberpunk" -> 0xFF2E124D;
            case "Deep Space" -> 0xFF090E1C;
            case "Blood Moon" -> 0xFF3E0A16;
            default -> 0xFF1A1142; // Nebula Core
        };
    }

    public int resolveNebulaColor1Argb() {
        int accent = ClientSettings.getAccentColor();
        return switch (skyMode.getValue()) {
            case "Accent Nebula" -> ColorUtil.lerp(0xFF181A36, accent, 0.65f);
            case "Galactic Core" -> 0xFF4338CA;
            case "Northern Lights" -> 0xFF10F59A;
            case "Solar Eclipse" -> 0xFFEA580C;
            case "Aurora" -> 0xFF10B981;
            case "Cyberpunk" -> 0xFFD926A9;
            case "Deep Space" -> 0xFF17223B;
            case "Blood Moon" -> 0xFF9F1239;
            default -> 0xFF4B2AD9; // Nebula Core
        };
    }

    public int resolveNebulaColor2Argb() {
        int accent = ClientSettings.getAccentColor();
        return switch (skyMode.getValue()) {
            case "Accent Nebula" -> ColorUtil.lerp(accent, 0xFFFFFFFF, 0.18f);
            case "Galactic Core" -> 0xFFF5C57A;
            case "Northern Lights" -> 0xFF18E0D0;
            case "Solar Eclipse" -> 0xFFFBBF24;
            case "Aurora" -> 0xFF22E3D4;
            case "Cyberpunk" -> 0xFF06D6FF;
            case "Deep Space" -> 0xFF2A3C61;
            case "Blood Moon" -> 0xFFF43F5E;
            default -> 0xFF8B5CFF; // Nebula Core
        };
    }
}
