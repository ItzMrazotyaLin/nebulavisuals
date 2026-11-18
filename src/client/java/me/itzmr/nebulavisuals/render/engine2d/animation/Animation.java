package me.itzmr.nebulavisuals.render.engine2d.animation;

/**
 * Плавная временная анимация на базе delta-time (независимая от тиков сервера и FPS).
 */
public class Animation {
    private float duration; // в секундах
    private float elapsed;  // в секундах
    private float fromValue;
    private float toValue;
    private Easing easing;
    private boolean finished;
    private boolean reversed;

    public Animation(float durationSeconds, float fromValue, float toValue, Easing easing) {
        this.duration = Math.max(0.0001f, durationSeconds);
        this.fromValue = fromValue;
        this.toValue = toValue;
        this.easing = easing != null ? easing : Easing.LINEAR;
        this.elapsed = 0.0f;
        this.finished = false;
        this.reversed = false;
    }

    public void update(float dt) {
        if (finished || dt <= 0.0f || Float.isNaN(dt) || Float.isInfinite(dt)) return;
        float safeDt = (dt > 0.1f) ? 0.016f : dt;

        elapsed += safeDt;
        if (elapsed >= duration) {
            elapsed = duration;
            finished = true;
        }
    }

    public float getValue() {
        float t = getProgress();
        float easedT = easing.ease(t);
        return fromValue + (toValue - fromValue) * easedT;
    }

    public float getProgress() {
        if (duration <= 0.0f) return 1.0f;
        float progress = Math.clamp(elapsed / duration, 0.0f, 1.0f);
        return reversed ? (1.0f - progress) : progress;
    }

    public void start() {
        this.elapsed = 0.0f;
        this.finished = false;
        this.reversed = false;
    }

    public void reverse() {
        this.reversed = !this.reversed;
        this.elapsed = duration - elapsed;
        this.finished = false;
    }

    public void reset(float from, float to) {
        this.fromValue = from;
        this.toValue = to;
        this.elapsed = 0.0f;
        this.finished = false;
        this.reversed = false;
    }

    public void setTarget(float newTarget) {
        float currentVal = getValue();
        reset(currentVal, newTarget);
    }

    public boolean isFinished() {
        return finished;
    }

    public void setDuration(float durationSeconds) {
        this.duration = Math.max(0.0001f, durationSeconds);
    }

    public void setEasing(Easing easing) {
        this.easing = easing != null ? easing : Easing.LINEAR;
    }
}
