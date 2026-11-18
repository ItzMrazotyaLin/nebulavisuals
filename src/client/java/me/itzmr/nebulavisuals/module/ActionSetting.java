package me.itzmr.nebulavisuals.module;

import java.util.function.Supplier;

/**
 * Настройка-действие: отображается в ClickGUI как интерактивная кнопка с подписью и откликом по клику.
 */
public class ActionSetting extends Setting<Boolean> {
    private static final long FLASH_DURATION_NANOS = 250_000_000L;

    private final String buttonLabel;
    private final Runnable action;
    private long lastTriggerNanos = 0L;

    public ActionSetting(
            String name,
            String description,
            String buttonLabel,
            Runnable action,
            Supplier<Boolean> visibility) {
        super(name, description, Boolean.FALSE, visibility);
        this.buttonLabel = buttonLabel != null ? buttonLabel : "Run";
        this.action = action;
    }

    public ActionSetting(String name, String buttonLabel, Runnable action, Supplier<Boolean> visibility) {
        this(name, "", buttonLabel, action, visibility);
    }

    public ActionSetting(String name, String description, String buttonLabel, Runnable action) {
        this(name, description, buttonLabel, action, () -> true);
    }

    public ActionSetting(String name, String buttonLabel, Runnable action) {
        this(name, "", buttonLabel, action, () -> true);
    }

    public String getButtonLabel() {
        return buttonLabel;
    }

    public void trigger() {
        this.lastTriggerNanos = System.nanoTime();
        if (this.action != null) {
            this.action.run();
        }
    }

    public float getClickFlashProgress() {
        if (lastTriggerNanos == 0L) {
            return 0.0f;
        }
        long elapsed = System.nanoTime() - lastTriggerNanos;
        if (elapsed < 0L || elapsed >= FLASH_DURATION_NANOS) {
            return 0.0f;
        }
        return 1.0f - (float) elapsed / (float) FLASH_DURATION_NANOS;
    }
}
