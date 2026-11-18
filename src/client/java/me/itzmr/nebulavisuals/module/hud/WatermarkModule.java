package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.BooleanSetting;

/**
 * HUD-модуль Watermark: перетаскиваемая стеклянная капсула с брендом NebulaVisuals,
 * телеметрией клиента и умным сдвигом под активные BossBar'ы.
 */
public class WatermarkModule extends HudModule {
    public final BooleanSetting showUsername = new BooleanSetting(
            "Show Username",
            "Display active player account username",
            true
    );
    public final BooleanSetting showServer = new BooleanSetting(
            "Show Server",
            "Display current server IP or Singleplayer",
            true
    );
    public final BooleanSetting showFps = new BooleanSetting(
            "Show FPS",
            "Display current frames per second",
            true
    );
    public final BooleanSetting showPing = new BooleanSetting(
            "Show Ping",
            "Display server latency in ms",
            false
    );
    public final BooleanSetting showSystemTime = new BooleanSetting(
            "Show System Time",
            "Display local PC clock (HH:mm)",
            false
    );
    public final BooleanSetting avoidBossBars = new BooleanSetting(
            "Avoid BossBars",
            "Smoothly shift watermark below active boss bars",
            true
    );

    public WatermarkModule() {
        super("Watermark", "Frosted glass HUD capsule with live client telemetry", true, 10.0f, 10.0f);
        addSettings(
                showUsername,
                showServer,
                showFps,
                showPing,
                showSystemTime,
                avoidBossBars
        );
    }
}
