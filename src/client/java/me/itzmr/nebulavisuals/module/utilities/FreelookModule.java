package me.itzmr.nebulavisuals.module.utilities;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.KeybindSetting;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.VisualModule;
import me.itzmr.nebulavisuals.module.visuals.AnimationsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Модуль Freelook (категория Utilities): свободный обзор камерой на 360° при зажатии клавиши
 * без изменения реального yaw/pitch сущности игрока, с плавным входом и выходом камеры.
 */
public class FreelookModule extends VisualModule {
    private static final float TRANSITION_SPEED = 6.5f;
    private static final float RETURN_THRESHOLD = 0.01f;

    public final KeybindSetting holdKey = new KeybindSetting(
            "Hold Key",
            "Keyboard key or mouse button held to rotate camera freely",
            GLFW.GLFW_KEY_LEFT_ALT
    );

    private boolean active = false;
    private boolean returning = false;
    private float freelookTransition = 0.0f;
    private long lastFrameNs = 0L;
    private float cameraYaw = 0.0f;
    private float cameraPitch = 0.0f;
    private Perspective previousPerspective = null;

    public FreelookModule() {
        super("Freelook", "Rotate camera 360 degrees while holding a key without rotating your player", Category.UTILITIES, false);
        addSettings(holdKey);
    }

    @Override
    protected void onDisable() {
        resetImmediate(MinecraftClient.getInstance());
    }

    /**
     * Захватывает ли Freelook движение мыши вместо поворота игрока.
     */
    public boolean isCapturingMouse() {
        return isEnabled() && active;
    }

    /**
     * Управляет ли Freelook углами/дистанцией камеры (во время удержания или плавного возврата).
     */
    public boolean isControllingCamera() {
        return isEnabled() && (active || returning);
    }

    public boolean isActive() {
        return isControllingCamera();
    }

    public float getCameraYaw() {
        return cameraYaw;
    }

    public float getCameraPitch() {
        return cameraPitch;
    }

    public float getDistanceScale() {
        if (previousPerspective == null || previousPerspective == Perspective.FIRST_PERSON) {
            return AnimationsModule.easeOutCubic(freelookTransition);
        }
        return 1.0f;
    }

    public void addDelta(float deltaYaw, float deltaPitch) {
        cameraYaw += deltaYaw;
        cameraPitch = MathHelper.clamp(cameraPitch + deltaPitch, -90.0f, 90.0f);
    }

    /**
     * Покадрово обновляет плавный переход {@code freelookTransition} и интерполированный возврат углов.
     */
    public void onCameraFrame(MinecraftClient client) {
        if (!isEnabled() || (!active && !returning)) {
            lastFrameNs = 0L;
            return;
        }
        if (client == null || client.player == null || client.options == null) {
            resetImmediate(client);
            return;
        }

        long now = System.nanoTime();
        float deltaTime = lastFrameNs == 0L ? 0.0f : MathHelper.clamp((now - lastFrameNs) / 1_000_000_000.0f, 0.0f, 0.05f);
        lastFrameNs = now;

        if (active) {
            freelookTransition = Math.min(1.0f, freelookTransition + deltaTime * TRANSITION_SPEED);
        } else {
            // При отпускании НЕ разворачиваем камеру за спину — фиксируем углы и плавно возвращаемся в глаза игрока
            freelookTransition = Math.max(0.0f, freelookTransition - deltaTime * TRANSITION_SPEED);
            if (freelookTransition <= RETURN_THRESHOLD) {
                resetImmediate(client);
            }
        }
    }

    public static void onClientTick(MinecraftClient client) {
        FreelookModule module = ModuleManager.getFreelookModule();
        if (module == null) {
            return;
        }
        if (!module.isEnabled() || client == null || client.player == null || client.options == null) {
            if (module.active || module.returning || module.previousPerspective != null) {
                module.resetImmediate(client);
            }
            return;
        }

        boolean held = module.isHoldKeyDown(client);
        if (held && !module.active) {
            module.activate(client);
        } else if (!held && module.active) {
            module.beginReturn(client);
        }
    }

    private void activate(MinecraftClient client) {
        if (client == null || client.player == null || client.options == null) {
            return;
        }
        if (!returning) {
            cameraYaw = client.player.getYaw();
            cameraPitch = MathHelper.clamp(client.player.getPitch(), -90.0f, 90.0f);
            previousPerspective = client.options.getPerspective();
            if (previousPerspective != Perspective.THIRD_PERSON_BACK) {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
        }
        active = true;
        returning = false;
        if (lastFrameNs == 0L) {
            lastFrameNs = System.nanoTime();
        }
    }

    private void beginReturn(MinecraftClient client) {
        if (!active) {
            return;
        }
        active = false;
        if (previousPerspective != null && previousPerspective != Perspective.FIRST_PERSON) {
            // Если игрок уже был в 3-м лице до Freelook, возвращаем углы плавно
            returning = freelookTransition > RETURN_THRESHOLD;
        } else {
            returning = true;
        }
        if (!returning || freelookTransition <= RETURN_THRESHOLD) {
            resetImmediate(client);
        }
    }

    private void resetImmediate(MinecraftClient client) {
        active = false;
        returning = false;
        freelookTransition = 0.0f;
        lastFrameNs = 0L;
        if (client != null && client.options != null && previousPerspective != null) {
            client.options.setPerspective(previousPerspective);
        }
        previousPerspective = null;
    }

    private boolean isHoldKeyDown(MinecraftClient client) {
        if (client == null || client.player == null || client.getWindow() == null || client.currentScreen != null) {
            return false;
        }
        return holdKey.isPressed(client.getWindow().getHandle());
    }
}
