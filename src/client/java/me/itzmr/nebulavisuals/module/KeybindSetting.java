package me.itzmr.nebulavisuals.module;

import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Настройка привязки клавиши (клавиатура GLFW или кнопка мыши GLFW_MOUSE_BUTTON_*).
 * Значение {@code -1} ({@link #UNBOUND}) соответствует состоянию NONE.
 */
public class KeybindSetting extends Setting<Integer> {
    public static final int UNBOUND = -1;
    private static final int MOUSE_CODE_BASE = -100;

    private boolean listening = false;

    public KeybindSetting(String name, String description, int defaultKey, Supplier<Boolean> visibility) {
        super(name, description, defaultKey, visibility);
    }

    public KeybindSetting(String name, String description, int defaultKey) {
        this(name, description, defaultKey, () -> true);
    }

    public KeybindSetting(String name, int defaultKey) {
        this(name, "", defaultKey, () -> true);
    }

    public static int encodeMouseButton(int glfwMouseButton) {
        return MOUSE_CODE_BASE - glfwMouseButton;
    }

    public boolean isBound() {
        return value != null && value != UNBOUND;
    }

    public boolean isMouseButton() {
        return value != null && value <= MOUSE_CODE_BASE;
    }

    public int getMouseButton() {
        return isMouseButton() ? (MOUSE_CODE_BASE - value) : -1;
    }

    public int getKeyCode() {
        return value != null ? value : UNBOUND;
    }

    public void bindKey(int glfwKey) {
        setValue(glfwKey);
    }

    public void bindMouse(int glfwMouseButton) {
        setValue(encodeMouseButton(glfwMouseButton));
    }

    public void unbind() {
        setValue(UNBOUND);
    }

    public boolean isListening() {
        return listening;
    }

    public void setListening(boolean listening) {
        this.listening = listening;
    }

    public boolean isPressed(long windowHandle) {
        if (windowHandle == 0L || !isBound()) {
            return false;
        }
        if (isMouseButton()) {
            int btn = getMouseButton();
            return btn >= 0 && btn <= GLFW.GLFW_MOUSE_BUTTON_LAST
                    && GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(windowHandle, value) == GLFW.GLFW_PRESS;
    }

    public String getDisplayName() {
        if (listening) {
            return "...";
        }
        if (!isBound()) {
            return "NONE";
        }
        if (isMouseButton()) {
            int btn = getMouseButton();
            return switch (btn) {
                case GLFW.GLFW_MOUSE_BUTTON_LEFT -> "LMB";
                case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "RMB";
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "MMB";
                default -> "MOUSE" + (btn + 1);
            };
        }
        return formatKeyName(value);
    }

    private static String formatKeyName(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS";
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> "ENTER";
            case GLFW.GLFW_KEY_BACKSPACE -> "BACK";
            case GLFW.GLFW_KEY_INSERT -> "INS";
            case GLFW.GLFW_KEY_HOME -> "HOME";
            case GLFW.GLFW_KEY_END -> "END";
            case GLFW.GLFW_KEY_PAGE_UP -> "PGUP";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PGDN";
            case GLFW.GLFW_KEY_UP -> "UP";
            case GLFW.GLFW_KEY_DOWN -> "DOWN";
            case GLFW.GLFW_KEY_LEFT -> "LEFT";
            case GLFW.GLFW_KEY_RIGHT -> "RIGHT";
            case GLFW.GLFW_KEY_F1 -> "F1";
            case GLFW.GLFW_KEY_F2 -> "F2";
            case GLFW.GLFW_KEY_F3 -> "F3";
            case GLFW.GLFW_KEY_F4 -> "F4";
            case GLFW.GLFW_KEY_F5 -> "F5";
            case GLFW.GLFW_KEY_F6 -> "F6";
            case GLFW.GLFW_KEY_F7 -> "F7";
            case GLFW.GLFW_KEY_F8 -> "F8";
            case GLFW.GLFW_KEY_F9 -> "F9";
            case GLFW.GLFW_KEY_F10 -> "F10";
            case GLFW.GLFW_KEY_F11 -> "F11";
            case GLFW.GLFW_KEY_F12 -> "F12";
            default -> {
                String raw = GLFW.glfwGetKeyName(key, 0);
                if (raw != null && !raw.isBlank()) {
                    yield raw.toUpperCase(Locale.ROOT);
                }
                yield "KEY" + key;
            }
        };
    }
}
