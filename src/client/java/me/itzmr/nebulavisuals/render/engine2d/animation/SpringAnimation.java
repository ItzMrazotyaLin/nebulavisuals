package me.itzmr.nebulavisuals.render.engine2d.animation;

/**
 * Физическая пружинная анимация (Damped Harmonic Oscillator)
 * с сабстеппингом для идеальной гладкости и стабильности.
 */
public class SpringAnimation {
    private float value;
    private float target;
    private float velocity;

    private float stiffness; // Жесткость пружины k (например, 150 - 300)
    private float damping;   // Коэффициент демпфирования c (например, 15 - 25)
    private float mass = 1.0f;

    public SpringAnimation(float initialValue, float stiffness, float damping) {
        this.value = initialValue;
        this.target = initialValue;
        this.velocity = 0.0f;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public void update(float dt) {
        if (dt <= 0.0f || Float.isNaN(dt) || Float.isInfinite(dt)) return;
        float safeDt = (dt > 0.1f) ? 0.016f : dt;

        // Сабстеппинг гарантирует устойчивость дифференциального уравнения
        float maxStep = 0.004f;
        float remaining = Math.min(safeDt, 0.05f);

        while (remaining > 0.0f) {
            float subDt = Math.min(remaining, maxStep);

            float displacement = value - target;
            float springForce = -stiffness * displacement;
            float dampingForce = -damping * velocity;
            float acceleration = (springForce + dampingForce) / mass;

            velocity += acceleration * subDt;
            value += velocity * subDt;

            remaining -= subDt;
        }

        if (Float.isNaN(value) || Float.isInfinite(value)) {
            value = target;
            velocity = 0.0f;
        }

        // Порог покоя
        if (Math.abs(value - target) < 0.0001f && Math.abs(velocity) < 0.0001f) {
            value = target;
            velocity = 0.0f;
        }
    }

    public float getValue() {
        return value;
    }

    public void setValue(float value) {
        this.value = value;
        this.velocity = 0.0f;
    }

    public float getTarget() {
        return target;
    }

    public void setTarget(float target) {
        this.target = target;
    }

    public boolean isResting() {
        return Math.abs(value - target) < 0.0001f && Math.abs(velocity) < 0.0001f;
    }

    public void setStiffness(float stiffness) {
        this.stiffness = stiffness;
    }

    public void setDamping(float damping) {
        this.damping = damping;
    }
}
