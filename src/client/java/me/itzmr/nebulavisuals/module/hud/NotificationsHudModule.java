package me.itzmr.nebulavisuals.module.hud;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;

import java.util.List;

/**
 * HUD-модуль Notifications HUD: отображает всплывающие уведомления о действиях конфигов,
 * критическом износе, заканчивающихся эффектах зелий и переключении модулей.
 */
public class NotificationsHudModule extends HudModule {
    public final ModeSetting style = new ModeSetting(
            "Style",
            "Visual density of notification cards",
            "Default",
            List.of("Default", "Compact")
    );
    public final ModeSetting animation = new ModeSetting(
            "Animation",
            "Enter and exit transition direction or scaling style",
            "Right",
            List.of("Right", "Left", "Bottom", "Top", "Scale")
    );
    public final BooleanSetting configActions = new BooleanSetting(
            "Config Actions",
            "Show notifications for config load, save, create, delete & migration instead of chat messages",
            true
    );
    public final BooleanSetting lowDurability = new BooleanSetting(
            "Low Durability",
            "Warn when equipped armor or held item durability drops below 15% or 20 durability",
            true
    );
    public final BooleanSetting expiringEffects = new BooleanSetting(
            "Expiring Effects",
            "Warn once when an active potion effect reaches 5 seconds remaining",
            true
    );
    public final BooleanSetting moduleToggles = new BooleanSetting(
            "Module Toggles",
            "Show compact notification when enabling or disabling any module",
            false
    );
    public final SliderSetting duration = new SliderSetting(
            "Duration",
            "How long notifications stay visible on screen",
            3.0, 1.5, 6.0, 0.5, "s"
    );

    public NotificationsHudModule() {
        super("Notifications HUD", "Sleek frosted glass toast notifications with slide animations", false, 700.0f, 435.0f, 1.25f);
        addSettings(style, animation, configActions, lowDurability, expiringEffects, moduleToggles, duration);
    }
}
