package me.itzmr.nebulavisuals.module;

import me.itzmr.nebulavisuals.module.hud.ArmorHudModule;
import me.itzmr.nebulavisuals.module.hud.BossbarChangerModule;
import me.itzmr.nebulavisuals.module.hud.CooldownsHudModule;
import me.itzmr.nebulavisuals.module.hud.CrosshairModule;
import me.itzmr.nebulavisuals.module.hud.HudModule;
import me.itzmr.nebulavisuals.module.hud.KeystrokesModule;
import me.itzmr.nebulavisuals.module.hud.NotificationsHudModule;
import me.itzmr.nebulavisuals.module.hud.PotionsHudModule;
import me.itzmr.nebulavisuals.module.hud.TargetHudModule;
import me.itzmr.nebulavisuals.module.hud.WatermarkModule;
import me.itzmr.nebulavisuals.module.optimization.AnimationsCullingModule;
import me.itzmr.nebulavisuals.module.optimization.BlockCullingModule;
import me.itzmr.nebulavisuals.module.optimization.EntityCullingModule;
import me.itzmr.nebulavisuals.module.optimization.MemoryCleanerModule;
import me.itzmr.nebulavisuals.module.optimization.ParticlesOptimizerModule;
import me.itzmr.nebulavisuals.module.optimization.UnfocusModeModule;
import me.itzmr.nebulavisuals.module.utilities.AutoSprintModule;
import me.itzmr.nebulavisuals.module.utilities.FreelookModule;
import me.itzmr.nebulavisuals.module.utilities.FullbrightModule;
import me.itzmr.nebulavisuals.module.utilities.NoRenderModule;
import me.itzmr.nebulavisuals.module.utilities.ZoomModule;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import me.itzmr.nebulavisuals.module.visuals.BlockOverlayModule;
import me.itzmr.nebulavisuals.module.visuals.ChinaHatModule;
import me.itzmr.nebulavisuals.module.visuals.CustomSkyModule;
import me.itzmr.nebulavisuals.module.visuals.HitParticlesModule;
import me.itzmr.nebulavisuals.module.visuals.HitboxesModule;
import me.itzmr.nebulavisuals.module.visuals.ItemPhysicsModule;
import me.itzmr.nebulavisuals.module.visuals.JumpCirclesModule;
import me.itzmr.nebulavisuals.module.visuals.KillEffectsModule;
import me.itzmr.nebulavisuals.module.visuals.MotionBlurModule;
import me.itzmr.nebulavisuals.module.visuals.PredictionsModule;
import me.itzmr.nebulavisuals.module.visuals.SelfNametagModule;
import me.itzmr.nebulavisuals.module.visuals.SmallerRainModule;
import me.itzmr.nebulavisuals.module.visuals.TargetEspModule;
import me.itzmr.nebulavisuals.module.visuals.TrailsModule;
import me.itzmr.nebulavisuals.module.visuals.ViewModelModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Реестр и менеджер модулей Nebula Visuals.
 */
public final class ModuleManager {
    private static final List<VisualModule> MODULES = new ArrayList<>();
    private static final List<HudModule> HUD_MODULES = new ArrayList<>();
    private static WatermarkModule watermarkModule;
    private static TargetHudModule targetHudModule;
    private static KeystrokesModule keystrokesModule;
    private static CrosshairModule crosshairModule;
    private static BossbarChangerModule bossbarChangerModule;
    private static CooldownsHudModule cooldownsHudModule;
    private static ArmorHudModule armorHudModule;
    private static PotionsHudModule potionsHudModule;
    private static NotificationsHudModule notificationsHudModule;
    private static TargetEspModule targetEspModule;
    private static JumpCirclesModule jumpCirclesModule;
    private static HitParticlesModule hitParticlesModule;
    private static SelfNametagModule selfNametagModule;
    private static TrailsModule trailsModule;
    private static ViewModelModule viewModelModule;
    private static ChinaHatModule chinaHatModule;
    private static BlockOverlayModule blockOverlayModule;
    private static CustomSkyModule customSkyModule;
    private static SmallerRainModule smallerRainModule;
    private static MotionBlurModule motionBlurModule;
    private static ItemPhysicsModule itemPhysicsModule;
    private static PredictionsModule predictionsModule;
    private static KillEffectsModule killEffectsModule;
    private static HitboxesModule hitboxesModule;
    private static AnimationsModule animationsModule;
    private static AutoSprintModule autoSprintModule;
    private static FreelookModule freelookModule;
    private static FullbrightModule fullbrightModule;
    private static NoRenderModule noRenderModule;
    private static ZoomModule zoomModule;
    private static EntityCullingModule entityCullingModule;
    private static BlockCullingModule blockCullingModule;
    private static ParticlesOptimizerModule particlesOptimizerModule;
    private static AnimationsCullingModule animationsCullingModule;
    private static UnfocusModeModule unfocusModeModule;
    private static MemoryCleanerModule memoryCleanerModule;
    private static boolean initialized = false;

    private ModuleManager() {}

    public static synchronized void init() {
        if (initialized) return;

        // 1. Watermark (HUD)
        watermarkModule = new WatermarkModule();
        register(watermarkModule);

        // 2. TargetHUD (HUD)
        targetHudModule = new TargetHudModule();
        register(targetHudModule);

        // 2b. Keystrokes (HUD)
        keystrokesModule = new KeystrokesModule();
        register(keystrokesModule);

        // 2c. Crosshair (HUD)
        crosshairModule = new CrosshairModule();
        register(crosshairModule);

        // 2d. BossbarChanger (HUD)
        bossbarChangerModule = new BossbarChangerModule();
        register(bossbarChangerModule);

        // 2e-g. Cooldowns HUD, Armor HUD, Potions HUD
        cooldownsHudModule = new CooldownsHudModule();
        register(cooldownsHudModule);

        armorHudModule = new ArmorHudModule();
        register(armorHudModule);

        potionsHudModule = new PotionsHudModule();
        register(potionsHudModule);

        notificationsHudModule = new NotificationsHudModule();
        register(notificationsHudModule);

        // 3. TargetESP (Visuals)
        targetEspModule = new TargetEspModule();
        register(targetEspModule);

        // 4. JumpCircles (Visuals)
        jumpCirclesModule = new JumpCirclesModule();
        register(jumpCirclesModule);

        // 5. HitParticles (Visuals)
        hitParticlesModule = new HitParticlesModule();
        register(hitParticlesModule);

        // 6. SelfNametag (Visuals)
        selfNametagModule = new SelfNametagModule();
        register(selfNametagModule);

        // 7. Trails (Visuals)
        trailsModule = new TrailsModule();
        register(trailsModule);

        // 8. ViewModel (Visuals)
        viewModelModule = new ViewModelModule();
        register(viewModelModule);

        // 9. ChinaHat (Visuals)
        chinaHatModule = new ChinaHatModule();
        register(chinaHatModule);

        // 10. BlockOverlay (Visuals)
        blockOverlayModule = new BlockOverlayModule();
        register(blockOverlayModule);

        // 11. CustomSky (Visuals)
        customSkyModule = new CustomSkyModule();
        register(customSkyModule);

        // 11b-c. Smaller Rain & Motion Blur (Visuals)
        smallerRainModule = new SmallerRainModule();
        register(smallerRainModule);

        motionBlurModule = new MotionBlurModule();
        register(motionBlurModule);

        itemPhysicsModule = new ItemPhysicsModule();
        register(itemPhysicsModule);

        predictionsModule = new PredictionsModule();
        register(predictionsModule);

        killEffectsModule = new KillEffectsModule();
        register(killEffectsModule);

        hitboxesModule = new HitboxesModule();
        register(hitboxesModule);

        animationsModule = new AnimationsModule();
        register(animationsModule);

        // 12-16. Utilities (AutoSprint, Freelook, Fullbright, NoRender, Zoom)
        autoSprintModule = new AutoSprintModule();
        register(autoSprintModule);

        freelookModule = new FreelookModule();
        register(freelookModule);

        fullbrightModule = new FullbrightModule();
        register(fullbrightModule);

        noRenderModule = new NoRenderModule();
        register(noRenderModule);

        zoomModule = new ZoomModule();
        register(zoomModule);

        // 18-23. Optimization (EntityCulling, BlockCulling, ParticlesOptimizer, AnimationsCulling, UnfocusMode, MemoryCleaner)
        entityCullingModule = new EntityCullingModule();
        register(entityCullingModule);

        blockCullingModule = new BlockCullingModule();
        register(blockCullingModule);

        particlesOptimizerModule = new ParticlesOptimizerModule();
        register(particlesOptimizerModule);

        animationsCullingModule = new AnimationsCullingModule();
        register(animationsCullingModule);

        unfocusModeModule = new UnfocusModeModule();
        register(unfocusModeModule);

        memoryCleanerModule = new MemoryCleanerModule();
        register(memoryCleanerModule);

        initialized = true;
    }

    public static WatermarkModule getWatermarkModule() {
        if (!initialized) init();
        return watermarkModule;
    }

    public static TargetHudModule getTargetHudModule() {
        if (!initialized) init();
        return targetHudModule;
    }

    public static KeystrokesModule getKeystrokesModule() {
        if (!initialized) init();
        return keystrokesModule;
    }

    public static CrosshairModule getCrosshairModule() {
        if (!initialized) init();
        return crosshairModule;
    }

    public static BossbarChangerModule getBossbarChangerModule() {
        if (!initialized) init();
        return bossbarChangerModule;
    }

    public static CooldownsHudModule getCooldownsHudModule() {
        if (!initialized) init();
        return cooldownsHudModule;
    }

    public static ArmorHudModule getArmorHudModule() {
        if (!initialized) init();
        return armorHudModule;
    }

    public static PotionsHudModule getPotionsHudModule() {
        if (!initialized) init();
        return potionsHudModule;
    }

    public static NotificationsHudModule getNotificationsHudModule() {
        if (!initialized) init();
        return notificationsHudModule;
    }

    public static TargetEspModule getTargetEspModule() {
        if (!initialized) init();
        return targetEspModule;
    }

    public static JumpCirclesModule getJumpCirclesModule() {
        if (!initialized) init();
        return jumpCirclesModule;
    }

    public static HitParticlesModule getHitParticlesModule() {
        if (!initialized) init();
        return hitParticlesModule;
    }

    public static SelfNametagModule getSelfNametagModule() {
        if (!initialized) init();
        return selfNametagModule;
    }

    public static TrailsModule getTrailsModule() {
        if (!initialized) init();
        return trailsModule;
    }

    public static ViewModelModule getViewModelModule() {
        if (!initialized) init();
        return viewModelModule;
    }

    public static ChinaHatModule getChinaHatModule() {
        if (!initialized) init();
        return chinaHatModule;
    }

    public static BlockOverlayModule getBlockOverlayModule() {
        if (!initialized) init();
        return blockOverlayModule;
    }

    public static CustomSkyModule getCustomSkyModule() {
        if (!initialized) init();
        return customSkyModule;
    }

    public static SmallerRainModule getSmallerRainModule() {
        if (!initialized) init();
        return smallerRainModule;
    }

    public static MotionBlurModule getMotionBlurModule() {
        if (!initialized) init();
        return motionBlurModule;
    }

    public static ItemPhysicsModule getItemPhysicsModule() {
        if (!initialized) init();
        return itemPhysicsModule;
    }

    public static PredictionsModule getPredictionsModule() {
        if (!initialized) init();
        return predictionsModule;
    }

    public static KillEffectsModule getKillEffectsModule() {
        if (!initialized) init();
        return killEffectsModule;
    }

    public static HitboxesModule getHitboxesModule() {
        if (!initialized) init();
        return hitboxesModule;
    }

    public static AnimationsModule getAnimationsModule() {
        if (!initialized) init();
        return animationsModule;
    }

    public static AutoSprintModule getAutoSprintModule() {
        if (!initialized) init();
        return autoSprintModule;
    }

    public static FreelookModule getFreelookModule() {
        if (!initialized) init();
        return freelookModule;
    }

    public static FullbrightModule getFullbrightModule() {
        if (!initialized) init();
        return fullbrightModule;
    }

    public static NoRenderModule getNoRenderModule() {
        if (!initialized) init();
        return noRenderModule;
    }

    public static ZoomModule getZoomModule() {
        if (!initialized) init();
        return zoomModule;
    }

    public static EntityCullingModule getEntityCullingModule() {
        if (!initialized) init();
        return entityCullingModule;
    }

    public static BlockCullingModule getBlockCullingModule() {
        if (!initialized) init();
        return blockCullingModule;
    }

    public static ParticlesOptimizerModule getParticlesOptimizerModule() {
        if (!initialized) init();
        return particlesOptimizerModule;
    }

    public static AnimationsCullingModule getAnimationsCullingModule() {
        if (!initialized) init();
        return animationsCullingModule;
    }

    public static UnfocusModeModule getUnfocusModeModule() {
        if (!initialized) init();
        return unfocusModeModule;
    }

    public static MemoryCleanerModule getMemoryCleanerModule() {
        if (!initialized) init();
        return memoryCleanerModule;
    }

    public static void register(VisualModule module) {
        MODULES.add(module);
        if (module instanceof HudModule hm) {
            HUD_MODULES.add(hm);
        }
    }

    public static List<VisualModule> getModules() {
        if (!initialized) init();
        return Collections.unmodifiableList(MODULES);
    }

    public static List<HudModule> getHudModules() {
        if (!initialized) init();
        return Collections.unmodifiableList(HUD_MODULES);
    }

    public static List<VisualModule> getByCategory(Category category) {
        if (!initialized) init();
        List<VisualModule> result = new ArrayList<>();
        for (VisualModule m : MODULES) {
            if (m.getCategory() == category) {
                result.add(m);
            }
        }
        return result;
    }

    public static VisualModule getByName(String name) {
        if (!initialized) init();
        for (VisualModule m : MODULES) {
            if (m.getName().equalsIgnoreCase(name)) {
                return m;
            }
        }
        return null;
    }
}
