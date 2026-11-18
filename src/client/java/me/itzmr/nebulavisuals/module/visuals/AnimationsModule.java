package me.itzmr.nebulavisuals.module.visuals;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import net.minecraft.util.math.MathHelper;

/**
 * Модуль Animations (категория Visuals): добавляет плавные анимации для контейнеров/чата,
 * плавный инерционный скролл списков/чата/креатива, плавный Tab и переход перспективы F5.
 */
public class AnimationsModule extends VisualModule {
    public static final float HAND_HIDE_DISTANCE_THRESHOLD = 0.35f;

    public final BooleanSetting guiAnimations = new BooleanSetting(
            "GUI Animations",
            "Smoothly animate inventories, chat, Tab & vanilla list scrolling",
            true
    );
    public final BooleanSetting creativeScroll = new BooleanSetting(
            "Creative Scroll",
            "Smoothly interpolate scrolling and tab transitions in Creative Inventory",
            true
    );
    public final BooleanSetting f5Transition = new BooleanSetting(
            "F5 Transition",
            "Smoothly animate camera distance when toggling perspective (F5)",
            true
    );
    public final BooleanSetting smoothHotbar = new BooleanSetting(
            "Smooth Hotbar",
            "Smoothly slide the selected hotbar slot frame",
            true
    );
    public final SliderSetting speed = new SliderSetting(
            "Speed",
            "Animation speed multiplier",
            1.2, 0.5, 2.5, 0.1, "x"
    );

    private static float currentF5Distance = 0.0f;

    public AnimationsModule() {
        super("Animations", "Smooth animations for inventories, lists, chat, hotbar, creative & F5 camera", Category.VISUALS, false);
        addSettings(guiAnimations, creativeScroll, f5Transition, smoothHotbar, speed);
    }

    @Override
    protected void onDisable() {
        currentF5Distance = 0.0f;
    }

    public static float easeOutCubic(float t) {
        float clamped = MathHelper.clamp(t, 0.0f, 1.0f);
        float inv = 1.0f - clamped;
        return 1.0f - inv * inv * inv;
    }

    public static float easeOutQuart(float t) {
        float clamped = MathHelper.clamp(t, 0.0f, 1.0f);
        float inv = 1.0f - clamped;
        float inv2 = inv * inv;
        return 1.0f - inv2 * inv2;
    }

    public float getSpeedMultiplier() {
        return MathHelper.clamp(speed.getValue().floatValue(), 0.5f, 2.5f);
    }

    public static boolean isGuiAnimationsActive() {
        AnimationsModule module = ModuleManager.getAnimationsModule();
        return module != null && module.isEnabled() && module.guiAnimations.getValue();
    }

    public static boolean isCreativeScrollActive() {
        AnimationsModule module = ModuleManager.getAnimationsModule();
        return module != null && module.isEnabled() && module.creativeScroll.getValue();
    }

    public static boolean isF5TransitionActive() {
        AnimationsModule module = ModuleManager.getAnimationsModule();
        return module != null && module.isEnabled() && module.f5Transition.getValue();
    }

    public static boolean isSmoothHotbarActive() {
        AnimationsModule module = ModuleManager.getAnimationsModule();
        return module != null && module.isEnabled() && module.smoothHotbar.getValue();
    }

    public static void setCurrentF5Distance(float distance) {
        currentF5Distance = Math.max(0.0f, distance);
    }

    public static float getCurrentF5Distance() {
        return currentF5Distance;
    }

    public static boolean shouldHideFirstPersonHand() {
        return isF5TransitionActive() && currentF5Distance > HAND_HIDE_DISTANCE_THRESHOLD;
    }
}
