package me.itzmr.nebulavisuals.module.utilities;

import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.KeybindSetting;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * Модуль Zoom (категория Utilities): плавное приближение камеры по удержанию клавиши
 * с регулировкой кратности колёсиком мыши и пропорциональным замедлением чувствительности.
 */
public class ZoomModule extends VisualModule {
    private static final float MIN_WHEEL_ZOOM = 1.5f;
    private static final float MAX_WHEEL_ZOOM = 15.0f;

    public final SliderSetting startZoom = new SliderSetting(
            "Start Zoom",
            "Initial magnification when pressing the zoom key",
            2.0,
            1.5,
            8.0,
            0.25,
            "x"
    );
    public final KeybindSetting zoomKey = new KeybindSetting(
            "Zoom Key",
            "Keyboard key or mouse button held to zoom in",
            GLFW.GLFW_KEY_C
    );
    public final BooleanSetting smoothZoom = new BooleanSetting(
            "Smooth Zoom",
            "Smoothly interpolate FOV transitions on zoom in and out",
            true
    );

    private boolean zooming = false;
    private float targetZoom = 1.0f;
    private float currentZoom = 1.0f;
    private float wheelZoom = 2.0f;
    private long lastFrameNs = 0L;

    public ZoomModule() {
        super(
                "Zoom",
                "Hold key to smoothly zoom FOV and adjust magnification with scroll wheel",
                Category.UTILITIES,
                false
        );
        addSettings(startZoom, zoomKey, smoothZoom);
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    private float getInitialZoom() {
        return Math.clamp(startZoom.getValue().floatValue(), MIN_WHEEL_ZOOM, MAX_WHEEL_ZOOM);
    }

    private void resetState() {
        zooming = false;
        targetZoom = 1.0f;
        currentZoom = 1.0f;
        wheelZoom = getInitialZoom();
        lastFrameNs = 0L;
    }

    public static void onClientTick(MinecraftClient client) {
        ZoomModule module = ModuleManager.getZoomModule();
        if (module == null) {
            return;
        }
        if (!module.isEnabled()) {
            if (module.zooming || module.currentZoom > 1.001f) {
                module.resetState();
            }
            return;
        }
        module.updateHoldState(client);
    }

    private void updateHoldState(MinecraftClient client) {
        boolean held = isZoomKeyDown(client);
        if (held) {
            if (!zooming) {
                zooming = true;
                wheelZoom = getInitialZoom();
            }
            targetZoom = wheelZoom;
        } else {
            zooming = false;
            targetZoom = 1.0f;
            wheelZoom = getInitialZoom();
        }
    }

    /**
     * Обновляет плавный коэффициент приближения в каждом кадре рендера FOV.
     */
    public float updateAndGetZoomFactor() {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0L ? 0.016f : Math.min(0.05f, Math.max(0.0005f, (now - lastFrameNs) / 1_000_000_000.0f));
        lastFrameNs = now;

        if (!isEnabled()) {
            resetState();
            return 1.0f;
        }

        updateHoldState(MinecraftClient.getInstance());

        if (smoothZoom.getValue()) {
            float t = 1.0f - (float) Math.exp(-16.0 * dt);
            currentZoom += (targetZoom - currentZoom) * t;
            if (Math.abs(targetZoom - currentZoom) < 0.002f) {
                currentZoom = targetZoom;
            }
        } else {
            currentZoom = targetZoom;
        }

        return Math.max(1.0f, currentZoom);
    }

    /**
     * Изменяет кратность приближения колёсиком мыши при активном удержании клавиши.
     *
     * @return {@code true}, если прокрутка была перехвачена зумом (хотбар не переключается)
     */
    public boolean handleMouseScroll(double verticalDelta) {
        if (!isEnabled() || Math.abs(verticalDelta) < 1.0e-6) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isZoomKeyDown(client)) {
            return false;
        }
        if (!zooming) {
            zooming = true;
            wheelZoom = getInitialZoom();
        }
        float stepFactor = (float) Math.pow(1.16, Math.abs(verticalDelta));
        if (verticalDelta > 0) {
            wheelZoom *= stepFactor;
        } else {
            wheelZoom /= stepFactor;
        }
        wheelZoom = Math.clamp(wheelZoom, MIN_WHEEL_ZOOM, MAX_WHEEL_ZOOM);
        targetZoom = wheelZoom;
        return true;
    }

    public boolean isZooming() {
        return isEnabled() && currentZoom > 1.02f;
    }

    public double getSensitivityMultiplier() {
        if (!isEnabled() || currentZoom <= 1.02f) {
            return 1.0;
        }
        return 1.0 / currentZoom;
    }

    private boolean isZoomKeyDown(MinecraftClient client) {
        if (client == null || client.player == null || client.getWindow() == null || client.currentScreen != null) {
            return false;
        }
        return zoomKey.isPressed(client.getWindow().getHandle());
    }
}
