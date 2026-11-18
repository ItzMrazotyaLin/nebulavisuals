package me.itzmr.nebulavisuals.render.engine2d.animation;

import me.itzmr.nebulavisuals.config.ClientSettings;

/**
 * Высокоточный таймер и математика сглаживания для UI Nebula Visuals.
 * Рассчитывает точный deltaTime на основе System.nanoTime() с учётом Animation Speed.
 */
public final class AnimationEngine {
    private static long lastTime = 0L;
    private static float deltaTime = 0.016f;

    private AnimationEngine() {}

    /**
     * Обновляет deltaTime в секундах. Вызывается перед отрисовкой кадра экрана.
     */
    public static float updateDelta() {
        long currentTime = System.nanoTime();
        double dt = (currentTime - lastTime) / 1_000_000_000.0;
        if (lastTime == 0L || dt <= 0.0 || dt > 0.1) {
            dt = 0.016; // дефолтные 60 FPS для первого кадра или после паузы, чтобы не было скачков
        }
        lastTime = currentTime;

        float rawDt = (float) Math.clamp(dt, 0.0001, 0.05);
        deltaTime = Math.clamp(rawDt * ClientSettings.getAnimationSpeed(), 0.0001f, 0.1f);
        return deltaTime;
    }

    public static float getDeltaTime() {
        return deltaTime;
    }

    /**
     * Фреймрейт-независимая экспоненциальная интерполяция (Exp Decay Lerp).
     * Дает математически идентичный результат на 30 FPS, 60 FPS, 144 FPS и 360 FPS.
     *
     * @param current Текущее значение
     * @param target  Целевое значение
     * @param speed   Скорость схождения (типичные значения: 5.0 - 25.0)
     * @param dt      Время кадра в секундах
     */
    public static float expLerp(float current, float target, float speed, float dt) {
        return (float) (target + (current - target) * Math.exp(-speed * dt));
    }
}
