package me.itzmr.nebulavisuals.module.optimization;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import net.minecraft.client.particle.Particle;

import java.util.List;

/**
 * Модуль ParticlesOptimizer (категория Optimization):
 * отсекает невидимые частицы за камерой и за непрозрачными стенами,
 * а также применяет стабильный детерминированный лимит по identityHashCode без мерцания.
 */
public class ParticlesOptimizerModule extends VisualModule {
    public static final String MODE_SMART_LIMIT = "Smart Limit";
    public static final String MODE_DISABLE_ALL = "Disable All";
    private static volatile int cacheEpoch = 0;
    private static volatile int visibleParticlesInFrame = 0;

    public final ModeSetting mode = new ModeSetting(
            "Mode",
            "Smart Limit deterministically filters excess particles without flicker; Disable All blocks all",
            MODE_SMART_LIMIT,
            List.of(MODE_SMART_LIMIT, MODE_DISABLE_ALL)
    );

    public final SliderSetting maxParticles = new SliderSetting(
            "Max Particles",
            "Maximum rendered particles in view before deterministic filtering applies",
            600.0, 100.0, 2500.0, 50.0, "",
            () -> MODE_SMART_LIMIT.equals(mode.getValue())
    );

    public ParticlesOptimizerModule() {
        super(
                "ParticlesOptimizer",
                "Frustum/wall-cull hidden particles and deterministically cap excess particles",
                Category.OPTIMIZATION,
                false
        );
        addSettings(mode, maxParticles);
    }

    public boolean isDisableAll() {
        return isEnabled() && MODE_DISABLE_ALL.equals(mode.getValue());
    }

    public int getMaxParticles() {
        return Math.max(50, (int) Math.round(maxParticles.getValue()));
    }

    public static void setVisibleParticlesInFrame(int count) {
        visibleParticlesInFrame = Math.max(0, count);
    }

    /**
     * Детерминированный фильтр по неизменному {@link System#identityHashCode(Object)} частицы:
     * каждая конкретная частица стабильно рисуется или стабильно пропускается на протяжении всей жизни,
     * без привязки к номеру кадра или времени.
     */
    public boolean shouldRenderByQuota(Particle particle) {
        if (!isEnabled() || particle == null) {
            return true;
        }
        if (MODE_DISABLE_ALL.equals(mode.getValue())) {
            return false;
        }
        int totalVisible = visibleParticlesInFrame;
        int limit = getMaxParticles();
        if (totalVisible <= limit) {
            return true;
        }
        float keepRatio = (float) limit / (float) totalVisible;
        int threshold = Math.max(1, Math.min(65536, (int) (keepRatio * 65536.0f)));
        int hash = System.identityHashCode(particle);
        hash ^= (hash >>> 16);
        return (hash & 0xFFFF) < threshold;
    }

    public static void requestCacheClear() {
        cacheEpoch++;
    }

    public static int getCacheEpoch() {
        return cacheEpoch;
    }
}
