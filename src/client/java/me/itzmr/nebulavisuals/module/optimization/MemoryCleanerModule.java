package me.itzmr.nebulavisuals.module.optimization;

import me.itzmr.nebulavisuals.config.LocalizationManager;
import me.itzmr.nebulavisuals.module.ActionSetting;
import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import me.itzmr.nebulavisuals.render.hud.TargetHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Модуль MemoryCleaner (категория Optimization):
 * безопасно очищает RAM вне PvP-боя по порогу или мгновенно по нажатию кнопки {@code Clean Now}.
 */
public class MemoryCleanerModule extends VisualModule {
    private static final long CHECK_INTERVAL_NS = 4_000_000_000L;
    private static final long AUTO_CLEAN_COOLDOWN_NS = 15_000_000_000L;

    public final BooleanSetting autoClean = new BooleanSetting(
            "Auto Clean",
            "Automatically free RAM outside PvP combat when heap usage exceeds threshold",
            true
    );
    public final SliderSetting thresholdPercent = new SliderSetting(
            "Threshold %",
            "Heap usage percentage threshold for automatic cleanup",
            80.0, 60.0, 95.0, 1.0, "%"
    );
    public final ActionSetting cleanNow = new ActionSetting(
            "Clean Now",
            "Immediately free unused memory & temporary caches and report freed MB in chat",
            "Clean",
            () -> performCleanup(MinecraftClient.getInstance(), true)
    );

    private long lastCheckNs = 0L;
    private long lastAutoCleanNs = 0L;

    public MemoryCleanerModule() {
        super(
                "MemoryCleaner",
                "Monitor JVM heap usage and safely free unused memory & temporary caches",
                Category.OPTIMIZATION,
                false
        );
        addSettings(autoClean, thresholdPercent, cleanNow);
    }

    public static void onClientTick(MinecraftClient client) {
        MemoryCleanerModule module = ModuleManager.getMemoryCleanerModule();
        if (module == null || !module.isEnabled() || !module.autoClean.getValue()) {
            return;
        }
        long now = System.nanoTime();
        if ((now - module.lastCheckNs) < CHECK_INTERVAL_NS) {
            return;
        }
        module.lastCheckNs = now;

        if (TargetHudRenderer.isInActiveCombat()) {
            return;
        }
        if ((now - module.lastAutoCleanNs) < AUTO_CLEAN_COOLDOWN_NS) {
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMem = runtime.maxMemory();
        if (maxMem <= 0L) {
            return;
        }
        long usedMem = runtime.totalMemory() - runtime.freeMemory();
        double usedPercent = (usedMem * 100.0) / maxMem;
        if (usedPercent >= module.thresholdPercent.getValue()) {
            module.lastAutoCleanNs = now;
            module.performCleanup(client, false);
        }
    }

    public void performCleanup(MinecraftClient client, boolean manual) {
        Runtime runtime = Runtime.getRuntime();
        long usedBefore = runtime.totalMemory() - runtime.freeMemory();

        ParticlesOptimizerModule.requestCacheClear();
        if (manual && client != null && client.particleManager != null) {
            ParticlesOptimizerModule opt = ModuleManager.getParticlesOptimizerModule();
            if (opt != null && opt.isEnabled()) {
                client.particleManager.clearParticles();
            }
        }

        System.gc();

        long usedAfter = runtime.totalMemory() - runtime.freeMemory();
        long freedMb = Math.max(0L, (usedBefore - usedAfter) / (1024L * 1024L));

        if (manual && client != null) {
            Text msg = Text.literal(LocalizationManager.tr("memory.freed", freedMb));
            if (client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                client.inGameHud.getChatHud().addMessage(msg);
            } else if (client.player != null) {
                client.player.sendMessage(msg, false);
            }
        }
    }
}
