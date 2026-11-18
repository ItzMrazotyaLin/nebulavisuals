package me.itzmr.nebulavisuals.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import me.itzmr.nebulavisuals.module.ActionSetting;
import me.itzmr.nebulavisuals.module.BooleanSetting;
import me.itzmr.nebulavisuals.module.ColorSetting;
import me.itzmr.nebulavisuals.module.KeybindSetting;
import me.itzmr.nebulavisuals.module.ModeSetting;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.Setting;
import me.itzmr.nebulavisuals.module.SliderSetting;
import me.itzmr.nebulavisuals.module.VisualModule;
import me.itzmr.nebulavisuals.module.hud.HudModule;
import me.itzmr.nebulavisuals.module.hud.NotificationsHudModule;
import me.itzmr.nebulavisuals.render.hud.NotificationsRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Полноценный менеджер JSON-конфигураций Nebula Visuals.
 *
 * <p>Хранит профили в {@code C:\Nebula\configs\<name>.json} (на не-Windows ОС — {@code <runDir>/Nebula/configs}),
 * сериализует все модули и их настройки, координаты всех HUD-элементов (включая выключенные)
 * и глобальные параметры {@link ClientSettings}, а также выполняет фоновое автосохранение {@code Default.json}
 * с дебаунсом 300 мс.</p>
 */
public final class ConfigManager {
    public static final String DEFAULT_CONFIG = "Default";
    private static final int CURRENT_FORMAT_VERSION = 2;
    private static final long AUTO_SAVE_DEBOUNCE_MS = 300L;
    private static final Set<String> EXPECTED_SETTINGS_KEYS = Set.of(
            "accentColor", "accentHue", "accentSat", "accentBri",
            "clickGuiScale", "uiOpacity", "blurStrength", "animationSpeed"
    );
    private static final Set<String> EXPECTED_HUD_KEYS = Set.of("x", "y", "scale");
    private static final Set<String> EXPECTED_COLOR_KEYS = Set.of("argb", "hex", "rainbow");

    private static final Logger LOGGER = LoggerFactory.getLogger("NebulaConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Nebula-Config-IO");
        t.setDaemon(true);
        return t;
    });

    private static final List<String> CONFIG_NAMES = new ArrayList<>();
    private static final ConcurrentLinkedQueue<String> PENDING_CHAT_NOTICES = new ConcurrentLinkedQueue<>();
    private static final Map<String, Boolean> AUTO_SAVE_PREFS = new ConcurrentHashMap<>();
    private static Path configsDir;
    private static Path activeStateFile;
    private static Path autoSaveFile;
    private static String activeConfigName = DEFAULT_CONFIG;

    private static volatile boolean initialized = false;
    private static volatile boolean isLoading = false;
    private static volatile boolean pendingAutoSave = false;
    private static volatile boolean hasUnsavedChanges = false;
    private static volatile long lastDirtyMs = 0L;
    private static volatile String lastStatusNotice = "";

    private ConfigManager() {}

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        resolvePaths();
        ensureDirectory();
        loadAutoSavePrefs();

        Path defaultFile = resolveConfigFile(DEFAULT_CONFIG);
        boolean defaultExisted = Files.exists(defaultFile);
        if (!defaultExisted) {
            writeSnapshotSync(defaultFile, buildSnapshot());
        }

        String bootTarget = readSavedActiveName();
        refreshConfigs();

        initialized = true;
        if (!loadConfig(bootTarget)) {
            loadConfig(DEFAULT_CONFIG);
        }
    }

    public static Path getConfigsDir() {
        if (configsDir == null) {
            resolvePaths();
        }
        return configsDir;
    }

    public static String getConfigsDirDisplay() {
        return getConfigsDir().toString();
    }

    public static List<String> getConfigNames() {
        synchronized (CONFIG_NAMES) {
            return Collections.unmodifiableList(new ArrayList<>(CONFIG_NAMES));
        }
    }

    public static String getActiveConfigName() {
        return activeConfigName;
    }

    public static boolean isDefaultActive() {
        return DEFAULT_CONFIG.equals(activeConfigName);
    }

    public static boolean isAutoSaveEnabled() {
        return isAutoSaveEnabled(activeConfigName);
    }

    public static boolean isAutoSaveEnabled(String configName) {
        if (configName == null || configName.isEmpty()) {
            return true;
        }
        String key = DEFAULT_CONFIG.equalsIgnoreCase(configName) ? DEFAULT_CONFIG : configName;
        Boolean pref = AUTO_SAVE_PREFS.get(key);
        return pref != null ? pref : true;
    }

    public static void setAutoSaveEnabled(boolean enabled) {
        String key = DEFAULT_CONFIG.equalsIgnoreCase(activeConfigName) ? DEFAULT_CONFIG : activeConfigName;
        AUTO_SAVE_PREFS.put(key, enabled);
        saveAutoSavePrefs();
        if (!initialized) {
            return;
        }
        Path targetFile = resolveConfigFile(key);
        if (enabled) {
            hasUnsavedChanges = false;
            pendingAutoSave = false;
            JsonObject snapshot = buildSnapshot(true);
            IO_EXECUTOR.submit(() -> writeSnapshotSync(targetFile, snapshot));
        } else {
            pendingAutoSave = false;
            IO_EXECUTOR.submit(() -> writeAutoSaveFlagToFile(targetFile, false));
        }
    }

    public static boolean hasUnsavedChanges() {
        return hasUnsavedChanges && !isAutoSaveEnabled();
    }

    public static String consumeLastStatusNotice() {
        String notice = lastStatusNotice;
        lastStatusNotice = "";
        return notice;
    }

    public static boolean isLoadingConfig() {
        return isLoading;
    }

    private static boolean shouldRouteToNotificationsHud() {
        if (!initialized) {
            return false;
        }
        NotificationsHudModule module = ModuleManager.getNotificationsHudModule();
        return module != null && module.isEnabled() && module.configActions.getValue();
    }

    private static void emitConfigActionNotification(String title, String description) {
        lastStatusNotice = description;
        if (shouldRouteToNotificationsHud()) {
            NotificationsRenderer.pushConfig(title, description);
        }
    }

    private static void notifyUser(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        lastStatusNotice = message;
        if (shouldRouteToNotificationsHud()) {
            NotificationsRenderer.pushConfig(LocalizationManager.tr("notif.config.notice.title"), message);
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§d[Nebula] §f" + message), false);
                }
            });
        } else {
            PENDING_CHAT_NOTICES.add(message);
        }
    }

    public static void markDirty() {
        if (!initialized || isLoading) {
            return;
        }
        if (isAutoSaveEnabled()) {
            hasUnsavedChanges = false;
            pendingAutoSave = true;
            lastDirtyMs = System.currentTimeMillis();
        } else {
            hasUnsavedChanges = true;
        }
    }

    public static void onClientTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && !PENDING_CHAT_NOTICES.isEmpty()) {
            String queued;
            while ((queued = PENDING_CHAT_NOTICES.poll()) != null) {
                client.player.sendMessage(Text.literal("§d[Nebula] §f" + queued), false);
            }
        }
        if (!initialized || !pendingAutoSave || !isAutoSaveEnabled()) {
            return;
        }
        if ((System.currentTimeMillis() - lastDirtyMs) < AUTO_SAVE_DEBOUNCE_MS) {
            return;
        }
        pendingAutoSave = false;
        JsonObject snapshot = buildSnapshot();
        Path target = resolveConfigFile(activeConfigName);
        IO_EXECUTOR.submit(() -> writeSnapshotSync(target, snapshot));
    }

    public static void shutdown() {
        if (!initialized) {
            return;
        }
        if (pendingAutoSave && isAutoSaveEnabled()) {
            pendingAutoSave = false;
            writeSnapshotSync(resolveConfigFile(activeConfigName), buildSnapshot());
        }
        IO_EXECUTOR.shutdown();
        try {
            IO_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static void refreshConfigs() {
        ensureDirectory();
        Path defaultFile = resolveConfigFile(DEFAULT_CONFIG);
        if (!Files.exists(defaultFile)) {
            writeSnapshotSync(defaultFile, buildSnapshot());
        }

        List<String> discovered = new ArrayList<>();
        AUTO_SAVE_PREFS.putIfAbsent(DEFAULT_CONFIG, readAutoSaveFromFile(defaultFile, true));
        try (Stream<Path> stream = Files.list(getConfigsDir())) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String fileName = p.getFileName().toString();
                if (fileName.toLowerCase(Locale.ROOT).endsWith(".json")) {
                    String name = fileName.substring(0, fileName.length() - 5).trim();
                    if (!name.isEmpty()) {
                        String key = DEFAULT_CONFIG.equalsIgnoreCase(name) ? DEFAULT_CONFIG : name;
                        AUTO_SAVE_PREFS.put(key, readAutoSaveFromFile(p, true));
                        if (!DEFAULT_CONFIG.equalsIgnoreCase(name)) {
                            discovered.add(name);
                        }
                    }
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to scan configs directory {}", getConfigsDir(), e);
        }

        discovered.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
        synchronized (CONFIG_NAMES) {
            CONFIG_NAMES.clear();
            CONFIG_NAMES.add(DEFAULT_CONFIG);
            CONFIG_NAMES.addAll(discovered);
        }
    }

    public static boolean createConfig(String rawName) {
        String cleanName = sanitizeName(rawName);
        if (cleanName.isEmpty() || DEFAULT_CONFIG.equalsIgnoreCase(cleanName)) {
            return false;
        }
        ensureDirectory();
        Path file = resolveConfigFile(cleanName);
        if (Files.exists(file)) {
            return false;
        }
        AUTO_SAVE_PREFS.put(cleanName, true);
        if (!writeSnapshotSync(file, buildSnapshot(true))) {
            return false;
        }
        pendingAutoSave = false;
        hasUnsavedChanges = false;
        activeConfigName = cleanName;
        saveActiveName(cleanName);
        saveAutoSavePrefs();
        refreshConfigs();
        emitConfigActionNotification(
                LocalizationManager.tr("notif.config.created.title"),
                LocalizationManager.tr("notif.config.created.desc", cleanName)
        );
        return true;
    }

    public static boolean saveConfig(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String targetName = DEFAULT_CONFIG.equalsIgnoreCase(name) ? DEFAULT_CONFIG : name;
        ensureDirectory();
        pendingAutoSave = false;
        boolean ok = writeSnapshotSync(resolveConfigFile(targetName), buildSnapshot(isAutoSaveEnabled(targetName)));
        if (ok) {
            hasUnsavedChanges = false;
            emitConfigActionNotification(
                    LocalizationManager.tr("notif.config.saved.title"),
                    LocalizationManager.tr("notif.config.saved.desc", targetName)
            );
        }
        refreshConfigs();
        return ok;
    }

    public static boolean resetConfig(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String targetName = DEFAULT_CONFIG.equalsIgnoreCase(name) ? DEFAULT_CONFIG : name;
        ensureDirectory();
        Path file = resolveConfigFile(targetName);
        boolean autoSave = isAutoSaveEnabled(targetName);
        boolean isActive = targetName.equals(activeConfigName);
        boolean hadNotifications = shouldRouteToNotificationsHud();

        JsonObject snapshot;
        if (isActive) {
            isLoading = true;
            try {
                resetAllToDefaults();
            } finally {
                isLoading = false;
            }
            snapshot = buildSnapshot(autoSave);
            pendingAutoSave = false;
            hasUnsavedChanges = false;
        } else {
            snapshot = buildDefaultSnapshot(autoSave);
        }

        boolean ok = writeSnapshotSync(file, snapshot);
        if (ok) {
            refreshConfigs();
            String title = LocalizationManager.tr("notif.config.reset.title");
            String desc = LocalizationManager.tr("notif.config.reset.desc", targetName);
            lastStatusNotice = desc;
            if (shouldRouteToNotificationsHud() || (isActive && hadNotifications)) {
                NotificationsRenderer.pushConfig(title, desc);
            }
        }
        return ok;
    }

    public static boolean loadConfig(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String targetName = DEFAULT_CONFIG.equalsIgnoreCase(name) ? DEFAULT_CONFIG : name;
        ensureDirectory();
        Path file = resolveConfigFile(targetName);
        if (!Files.exists(file)) {
            return false;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return recreateCorruptedConfig(file, targetName, "root element is not a JSON object");
            }
            JsonObject root = parsed.getAsJsonObject();
            boolean needsMigration = inspectSnapshotNeedsMigration(root);
            boolean loadedAutoSave = (!root.has("autoSave") || !root.get("autoSave").isJsonPrimitive()
                    || !root.getAsJsonPrimitive("autoSave").isBoolean())
                    ? true
                    : root.get("autoSave").getAsBoolean();
            AUTO_SAVE_PREFS.put(targetName, loadedAutoSave);

            isLoading = true;
            try {
                resetAllToDefaults();
                applySnapshot(root);
            } finally {
                isLoading = false;
            }

            pendingAutoSave = false;
            hasUnsavedChanges = false;
            activeConfigName = targetName;
            saveActiveName(targetName);

            if (needsMigration) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
                writeSnapshotSync(file, buildSnapshot());
                String msg = LocalizationManager.tr("notif.config.migrated", targetName);
                LOGGER.info("Config '{}' migrated to current format.", targetName);
                notifyUser(msg);
            } else {
                emitConfigActionNotification(
                        LocalizationManager.tr("notif.config.loaded.title"),
                        LocalizationManager.tr("notif.config.loaded.desc", targetName)
                );
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("Corrupted config '{}': {}", targetName, e.getMessage());
            return recreateCorruptedConfig(file, targetName, e.getMessage());
        }
    }

    private static boolean recreateCorruptedConfig(Path file, String targetName, String reason) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            LOGGER.error("Failed to delete corrupted config {}", file, ex);
        }

        isLoading = true;
        try {
            resetAllToDefaults();
        } finally {
            isLoading = false;
        }

        boolean ok = writeSnapshotSync(file, buildSnapshot());
        pendingAutoSave = false;
        hasUnsavedChanges = false;
        activeConfigName = targetName;
        saveActiveName(targetName);
        refreshConfigs();

        String msg = LocalizationManager.tr("notif.config.corrupted", targetName);
        LOGGER.warn("Corrupted config '{}' recreated with defaults (Reason: {})", targetName, reason);
        notifyUser(msg);
        return ok;
    }

    public static boolean renameConfig(String oldName, String rawNewName) {
        if (oldName == null || DEFAULT_CONFIG.equalsIgnoreCase(oldName)) {
            return false;
        }
        String cleanNew = sanitizeName(rawNewName);
        if (cleanNew.isEmpty() || DEFAULT_CONFIG.equalsIgnoreCase(cleanNew)) {
            return false;
        }
        if (oldName.equals(cleanNew)) {
            return true;
        }

        Path oldFile = resolveConfigFile(oldName);
        Path newFile = resolveConfigFile(cleanNew);
        if (!Files.exists(oldFile) || Files.exists(newFile)) {
            return false;
        }

        try {
            Files.move(oldFile, newFile);
            Boolean oldPref = AUTO_SAVE_PREFS.remove(oldName);
            if (oldPref != null) {
                AUTO_SAVE_PREFS.put(cleanNew, oldPref);
                saveAutoSavePrefs();
            }
            if (oldName.equals(activeConfigName)) {
                activeConfigName = cleanNew;
                saveActiveName(cleanNew);
            }
            refreshConfigs();
            emitConfigActionNotification(
                    LocalizationManager.tr("notif.config.renamed.title"),
                    "\"" + oldName + "\" -> \"" + cleanNew + "\""
            );
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to rename config '{}' -> '{}'", oldName, cleanNew, e);
            return false;
        }
    }

    public static boolean deleteConfig(String name) {
        if (name == null || DEFAULT_CONFIG.equalsIgnoreCase(name)) {
            return false;
        }
        Path file = resolveConfigFile(name);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.error("Failed to delete config '{}'", name, e);
            return false;
        }

        if (AUTO_SAVE_PREFS.remove(name) != null) {
            saveAutoSavePrefs();
        }
        boolean wasActive = name.equals(activeConfigName);
        refreshConfigs();
        if (wasActive) {
            loadConfig(DEFAULT_CONFIG);
        }
        emitConfigActionNotification(
                LocalizationManager.tr("notif.config.deleted.title"),
                LocalizationManager.tr("notif.config.deleted.desc", name)
        );
        return true;
    }

    public static void openConfigsFolder() {
        ensureDirectory();
        try {
            Util.getOperatingSystem().open(getConfigsDir().toFile());
        } catch (Exception e) {
            LOGGER.warn("Failed to open configs folder {}", getConfigsDir(), e);
        }
    }

    public static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().replaceAll("[\\\\/:*?\"<>|]", "");
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".json")) {
            trimmed = trimmed.substring(0, trimmed.length() - 5).trim();
        }
        if (trimmed.length() > 32) {
            trimmed = trimmed.substring(0, 32).trim();
        }
        return trimmed;
    }

    private static void resolvePaths() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            configsDir = Paths.get("C:\\Nebula\\configs");
        } else {
            MinecraftClient mc = MinecraftClient.getInstance();
            Path base = (mc != null && mc.runDirectory != null)
                    ? mc.runDirectory.toPath()
                    : FabricLoader.getInstance().getGameDir();
            configsDir = base.resolve("Nebula").resolve("configs");
        }
        activeStateFile = configsDir.getParent().resolve("active_config.txt");
        autoSaveFile = configsDir.getParent().resolve("auto_save.json");
    }

    private static void loadAutoSavePrefs() {
        AUTO_SAVE_PREFS.clear();
        AUTO_SAVE_PREFS.put(DEFAULT_CONFIG, true);
        if (autoSaveFile == null || !Files.exists(autoSaveFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(autoSaveFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed != null && parsed.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isBoolean()) {
                        String key = DEFAULT_CONFIG.equalsIgnoreCase(entry.getKey()) ? DEFAULT_CONFIG : entry.getKey();
                        AUTO_SAVE_PREFS.put(key, entry.getValue().getAsBoolean());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load auto-save preferences", e);
        }
    }

    private static synchronized void saveAutoSavePrefs() {
        if (autoSaveFile == null) {
            return;
        }
        try {
            Files.createDirectories(autoSaveFile.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Boolean> entry : AUTO_SAVE_PREFS.entrySet()) {
                obj.addProperty(entry.getKey(), Boolean.TRUE.equals(entry.getValue()));
            }
            Files.writeString(autoSaveFile, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to save auto-save preferences", e);
        }
    }

    private static void ensureDirectory() {
        try {
            Files.createDirectories(getConfigsDir());
        } catch (IOException e) {
            LOGGER.error("Failed to create configs directory {}", getConfigsDir(), e);
        }
    }

    private static Path resolveConfigFile(String name) {
        return getConfigsDir().resolve(name + ".json");
    }

    private static String readSavedActiveName() {
        if (activeStateFile == null || !Files.exists(activeStateFile)) {
            return DEFAULT_CONFIG;
        }
        try {
            String saved = Files.readString(activeStateFile, StandardCharsets.UTF_8).trim();
            if (!saved.isEmpty() && Files.exists(resolveConfigFile(saved))) {
                return saved;
            }
        } catch (IOException ignored) {
        }
        return DEFAULT_CONFIG;
    }

    private static void saveActiveName(String name) {
        if (activeStateFile == null) {
            return;
        }
        try {
            Files.createDirectories(activeStateFile.getParent());
            Files.writeString(activeStateFile, name, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to persist active config name", e);
        }
    }

    private static void resetAllToDefaults() {
        ClientSettings.resetToDefaults();
        for (HudModule hm : ModuleManager.getHudModules()) {
            hm.resetHudToDefault();
        }
        for (VisualModule m : ModuleManager.getModules()) {
            m.resetToDefault();
        }
    }

    private static boolean inspectSnapshotNeedsMigration(JsonObject root) {
        if (!root.has("formatVersion") || !root.get("formatVersion").isJsonPrimitive()
                || !root.getAsJsonPrimitive("formatVersion").isNumber()
                || root.get("formatVersion").getAsInt() != CURRENT_FORMAT_VERSION) {
            return true;
        }
        if (!root.has("autoSave") || !root.get("autoSave").isJsonPrimitive()
                || !root.getAsJsonPrimitive("autoSave").isBoolean()) {
            return true;
        }
        if (!root.has("settings") || !root.get("settings").isJsonObject()
                || !root.has("hud") || !root.get("hud").isJsonObject()
                || !root.has("modules") || !root.get("modules").isJsonObject()) {
            return true;
        }

        // 1. Проверка секции settings (точное совпадение ключей: clickGuiScale вместо uiScale и т.д.)
        JsonObject settingsObj = root.getAsJsonObject("settings");
        if (!settingsObj.keySet().equals(EXPECTED_SETTINGS_KEYS)) {
            return true;
        }
        for (String key : EXPECTED_SETTINGS_KEYS) {
            if (!settingsObj.get(key).isJsonPrimitive()) {
                return true;
            }
            if (!"accentColor".equals(key) && !settingsObj.getAsJsonPrimitive(key).isNumber()) {
                return true;
            }
        }

        // 2. Проверка секции hud (наличие всех актуальных HUD + exact keys {"x", "y", "scale"})
        JsonObject hudObj = root.getAsJsonObject("hud");
        Set<String> expectedHudNames = new HashSet<>();
        for (HudModule hm : ModuleManager.getHudModules()) {
            expectedHudNames.add(hm.getName());
            JsonElement el = hudObj.get(hm.getName());
            if (el == null || !el.isJsonObject()) {
                return true;
            }
            JsonObject posObj = el.getAsJsonObject();
            if (!posObj.keySet().equals(EXPECTED_HUD_KEYS)) {
                return true;
            }
            for (String hk : EXPECTED_HUD_KEYS) {
                if (!posObj.get(hk).isJsonPrimitive() || !posObj.getAsJsonPrimitive(hk).isNumber()) {
                    return true;
                }
            }
        }
        if (!hudObj.keySet().equals(expectedHudNames)) {
            return true;
        }

        // 3. Проверка секции modules (отсутствие удалённых модулей/настроек и наличие всех новых)
        JsonObject modulesObj = root.getAsJsonObject("modules");
        Set<String> expectedModuleNames = new HashSet<>();
        for (VisualModule m : ModuleManager.getModules()) {
            expectedModuleNames.add(m.getName());
            JsonElement modEl = modulesObj.get(m.getName());
            if (modEl == null || !modEl.isJsonObject()) {
                return true;
            }
            JsonObject modObj = modEl.getAsJsonObject();
            if (!modObj.has("enabled") || !modObj.get("enabled").isJsonPrimitive()
                    || !modObj.getAsJsonPrimitive("enabled").isBoolean()) {
                return true;
            }
            if (!modObj.has("settings") || !modObj.get("settings").isJsonObject()) {
                return true;
            }
            JsonObject modSettings = modObj.getAsJsonObject("settings");
            Set<String> expectedSettingNames = new HashSet<>();
            for (Setting<?> s : m.getSettings()) {
                if (s instanceof ActionSetting) {
                    continue;
                }
                expectedSettingNames.add(s.getName());
                JsonElement sEl = modSettings.get(s.getName());
                if (sEl == null || !isSettingJsonValid(s, sEl)) {
                    return true;
                }
            }
            if (!modSettings.keySet().equals(expectedSettingNames)) {
                return true;
            }
        }
        return !modulesObj.keySet().equals(expectedModuleNames);
    }

    private static boolean isSettingJsonValid(Setting<?> setting, JsonElement el) {
        if (setting instanceof BooleanSetting) {
            return el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean();
        }
        if (setting instanceof SliderSetting || setting instanceof KeybindSetting) {
            return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber();
        }
        if (setting instanceof ModeSetting) {
            return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString();
        }
        if (setting instanceof ColorSetting) {
            if (!el.isJsonObject()) {
                return false;
            }
            JsonObject colorObj = el.getAsJsonObject();
            return colorObj.keySet().equals(EXPECTED_COLOR_KEYS)
                    && colorObj.get("argb").isJsonPrimitive() && colorObj.getAsJsonPrimitive("argb").isNumber()
                    && colorObj.get("hex").isJsonPrimitive() && colorObj.getAsJsonPrimitive("hex").isString()
                    && colorObj.get("rainbow").isJsonPrimitive() && colorObj.getAsJsonPrimitive("rainbow").isBoolean();
        }
        return true;
    }

    private static JsonObject buildSnapshot() {
        return buildSnapshot(isAutoSaveEnabled(activeConfigName), false);
    }

    private static JsonObject buildSnapshot(boolean autoSave) {
        return buildSnapshot(autoSave, false);
    }

    private static JsonObject buildDefaultSnapshot(boolean autoSave) {
        return buildSnapshot(autoSave, true);
    }

    private static JsonObject buildSnapshot(boolean autoSave, boolean useDefaults) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", CURRENT_FORMAT_VERSION);
        root.addProperty("autoSave", autoSave);

        // 1. Глобальные настройки интерфейса (Settings)
        JsonObject settingsObj = new JsonObject();
        if (useDefaults) {
            Integer defAccent = ClientSettings.getAccentColorSetting().getDefaultValue();
            int accentArgb = defAccent != null ? defAccent : 0xFF9333EA;
            float[] hsb = ClientSettings.argbToHsb(accentArgb);
            settingsObj.addProperty("accentColor", ClientSettings.getAccentColorSetting().getDefaultHex());
            settingsObj.addProperty("accentHue", hsb[0]);
            settingsObj.addProperty("accentSat", hsb[1]);
            settingsObj.addProperty("accentBri", hsb[2]);
            settingsObj.addProperty("clickGuiScale", ClientSettings.getClickGuiScaleSetting().getDefaultValue());
            settingsObj.addProperty("uiOpacity", ClientSettings.getUiOpacitySetting().getDefaultValue());
            settingsObj.addProperty("blurStrength", ClientSettings.getBlurStrengthSetting().getDefaultValue());
            settingsObj.addProperty("animationSpeed", ClientSettings.getAnimationSpeedSetting().getDefaultValue());
        } else {
            settingsObj.addProperty("accentColor", ClientSettings.getAccentColorSetting().getHex());
            settingsObj.addProperty("accentHue", ClientSettings.getAccentHue());
            settingsObj.addProperty("accentSat", ClientSettings.getAccentSat());
            settingsObj.addProperty("accentBri", ClientSettings.getAccentBri());
            settingsObj.addProperty("clickGuiScale", ClientSettings.getClickGuiScaleSetting().getValue());
            settingsObj.addProperty("uiOpacity", ClientSettings.getUiOpacitySetting().getValue());
            settingsObj.addProperty("blurStrength", ClientSettings.getBlurStrengthSetting().getValue());
            settingsObj.addProperty("animationSpeed", ClientSettings.getAnimationSpeedSetting().getValue());
        }
        root.add("settings", settingsObj);

        // 2. Координаты и индивидуальный масштаб всех HUD-элементов (включая выключенные)
        JsonObject hudObj = new JsonObject();
        for (HudModule hm : ModuleManager.getHudModules()) {
            JsonObject pos = new JsonObject();
            pos.addProperty("x", useDefaults ? hm.getDefaultX() : hm.getHudX());
            pos.addProperty("y", useDefaults ? hm.getDefaultY() : hm.getHudY());
            pos.addProperty("scale", useDefaults ? hm.getDefaultHudScale() : hm.getHudScale());
            hudObj.add(hm.getName(), pos);
        }
        root.add("hud", hudObj);

        // 3. Все модули всех категорий и все их настройки
        JsonObject modulesObj = new JsonObject();
        for (VisualModule m : ModuleManager.getModules()) {
            JsonObject modObj = new JsonObject();
            modObj.addProperty("enabled", useDefaults ? m.isDefaultEnabled() : m.isEnabled());

            JsonObject modSettings = new JsonObject();
            for (Setting<?> s : m.getSettings()) {
                if (s instanceof ActionSetting) {
                    continue;
                }
                if (s instanceof BooleanSetting bs) {
                    boolean val = Boolean.TRUE.equals(useDefaults ? bs.getDefaultValue() : bs.getValue());
                    modSettings.addProperty(bs.getName(), val);
                } else if (s instanceof SliderSetting ss) {
                    Double val = useDefaults ? ss.getDefaultValue() : ss.getValue();
                    modSettings.addProperty(ss.getName(), val);
                } else if (s instanceof ModeSetting ms) {
                    String val = useDefaults ? ms.getDefaultValue() : ms.getValue();
                    modSettings.addProperty(ms.getName(), val);
                } else if (s instanceof KeybindSetting ks) {
                    Integer val = useDefaults ? ks.getDefaultValue() : ks.getKeyCode();
                    modSettings.addProperty(ks.getName(), val != null ? val : KeybindSetting.UNBOUND);
                } else if (s instanceof ColorSetting cs) {
                    JsonObject colorObj = new JsonObject();
                    Integer argb = useDefaults ? cs.getDefaultValue() : cs.getValue();
                    colorObj.addProperty("argb", argb);
                    colorObj.addProperty("hex", useDefaults ? cs.getDefaultHex() : cs.getHex());
                    colorObj.addProperty("rainbow", useDefaults ? cs.isDefaultRainbow() : cs.isRainbow());
                    modSettings.add(cs.getName(), colorObj);
                }
            }
            modObj.add("settings", modSettings);
            modulesObj.add(m.getName(), modObj);
        }
        root.add("modules", modulesObj);

        return root;
    }

    private static void applySnapshot(JsonObject root) {
        isLoading = true;
        try {
            // 1. Глобальные настройки Settings
            if (root.has("settings") && root.get("settings").isJsonObject()) {
                JsonObject settingsObj = root.getAsJsonObject("settings");
                if (settingsObj.has("accentHue") && settingsObj.has("accentSat") && settingsObj.has("accentBri")) {
                    ClientSettings.setAccentHsb(
                            settingsObj.get("accentHue").getAsFloat(),
                            settingsObj.get("accentSat").getAsFloat(),
                            settingsObj.get("accentBri").getAsFloat()
                    );
                } else if (settingsObj.has("accentColor")) {
                    Integer parsedColor = parseColorValue(settingsObj.get("accentColor"));
                    if (parsedColor != null) {
                        ClientSettings.setAccentColor(parsedColor);
                    }
                }
                // Миграция старого "uiScale" -> новый "clickGuiScale"
                if (settingsObj.has("clickGuiScale") && settingsObj.get("clickGuiScale").isJsonPrimitive()
                        && settingsObj.getAsJsonPrimitive("clickGuiScale").isNumber()) {
                    ClientSettings.getClickGuiScaleSetting().setValue(settingsObj.get("clickGuiScale").getAsDouble());
                } else if (settingsObj.has("uiScale") && settingsObj.get("uiScale").isJsonPrimitive()
                        && settingsObj.getAsJsonPrimitive("uiScale").isNumber()) {
                    ClientSettings.getClickGuiScaleSetting().setValue(settingsObj.get("uiScale").getAsDouble());
                }
                if (settingsObj.has("uiOpacity") && settingsObj.get("uiOpacity").isJsonPrimitive()
                        && settingsObj.getAsJsonPrimitive("uiOpacity").isNumber()) {
                    ClientSettings.getUiOpacitySetting().setValue(settingsObj.get("uiOpacity").getAsDouble());
                }
                if (settingsObj.has("blurStrength") && settingsObj.get("blurStrength").isJsonPrimitive()
                        && settingsObj.getAsJsonPrimitive("blurStrength").isNumber()) {
                    ClientSettings.getBlurStrengthSetting().setValue(settingsObj.get("blurStrength").getAsDouble());
                }
                if (settingsObj.has("animationSpeed") && settingsObj.get("animationSpeed").isJsonPrimitive()
                        && settingsObj.getAsJsonPrimitive("animationSpeed").isNumber()) {
                    ClientSettings.getAnimationSpeedSetting().setValue(settingsObj.get("animationSpeed").getAsDouble());
                }
            }

            // 2. Позиции и индивидуальный масштаб всех HUD-модулей (включая выключенные)
            if (root.has("hud") && root.get("hud").isJsonObject()) {
                JsonObject hudObj = root.getAsJsonObject("hud");
                for (HudModule hm : ModuleManager.getHudModules()) {
                    JsonObject posObj = findIgnoreCaseObject(hudObj, hm.getName());
                    if (posObj != null) {
                        if (posObj.has("x") && posObj.has("y")
                                && posObj.get("x").isJsonPrimitive() && posObj.getAsJsonPrimitive("x").isNumber()
                                && posObj.get("y").isJsonPrimitive() && posObj.getAsJsonPrimitive("y").isNumber()) {
                            hm.setHudPosition(posObj.get("x").getAsFloat(), posObj.get("y").getAsFloat());
                        }
                        if (posObj.has("scale") && posObj.get("scale").isJsonPrimitive()
                                && posObj.getAsJsonPrimitive("scale").isNumber()) {
                            hm.setHudScale(posObj.get("scale").getAsFloat());
                        } else {
                            hm.setHudScale(1.0f);
                        }
                    }
                }
            }

            // 3. Состояние и настройки всех модулей
            if (root.has("modules") && root.get("modules").isJsonObject()) {
                JsonObject modulesObj = root.getAsJsonObject("modules");
                for (VisualModule m : ModuleManager.getModules()) {
                    JsonObject modObj = findIgnoreCaseObject(modulesObj, m.getName());
                    if (modObj == null && "Predictions".equals(m.getName())) {
                        modObj = findIgnoreCaseObject(modulesObj, "ProjectilePrediction");
                    }
                    if (modObj == null) {
                        continue;
                    }
                    if (modObj.has("enabled") && modObj.get("enabled").isJsonPrimitive()) {
                        m.setEnabled(modObj.get("enabled").getAsBoolean());
                    }
                    if (modObj.has("settings") && modObj.get("settings").isJsonObject()) {
                        JsonObject modSettings = modObj.getAsJsonObject("settings");
                        for (Setting<?> s : m.getSettings()) {
                            if (s instanceof ActionSetting) {
                                continue;
                            }
                            JsonElement el = findIgnoreCaseElement(modSettings, s.getName());
                            if (el == null || el.isJsonNull()) {
                                continue;
                            }
                            applySettingValue(s, el);
                        }
                    }
                }
            }
        } finally {
            isLoading = false;
        }
    }

    private static void applySettingValue(Setting<?> setting, JsonElement el) {
        try {
            if (setting instanceof BooleanSetting bs && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
                bs.setValue(el.getAsBoolean());
            } else if (setting instanceof SliderSetting ss && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                ss.setValue(el.getAsDouble());
            } else if (setting instanceof ModeSetting ms && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                ms.setValue(el.getAsString());
            } else if (setting instanceof KeybindSetting ks && el.isJsonPrimitive()) {
                JsonPrimitive prim = el.getAsJsonPrimitive();
                if (prim.isNumber()) {
                    ks.setValue(prim.getAsInt());
                } else if (prim.isString()) {
                    // Миграция устаревших строковых значений вроде "Left Alt" / "V" / "C" / "Middle Mouse"
                    int migratedKey = switch (prim.getAsString()) {
                        case "V" -> GLFW.GLFW_KEY_V;
                        case "C" -> GLFW.GLFW_KEY_C;
                        case "Middle Mouse" -> KeybindSetting.encodeMouseButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
                        default -> GLFW.GLFW_KEY_LEFT_ALT;
                    };
                    ks.setValue(migratedKey);
                }
            } else if (setting instanceof ColorSetting cs) {
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("argb")) {
                        cs.setValue(obj.get("argb").getAsInt());
                    } else if (obj.has("hex")) {
                        Integer c = parseColorValue(obj.get("hex"));
                        if (c != null) {
                            cs.setValue(c);
                        }
                    }
                    if (obj.has("rainbow")) {
                        cs.setRainbow(obj.get("rainbow").getAsBoolean());
                    }
                } else {
                    Integer c = parseColorValue(el);
                    if (c != null) {
                        cs.setValue(c);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to apply setting '{}': {}", setting.getName(), e.getMessage());
        }
    }

    private static JsonObject findIgnoreCaseObject(JsonObject parent, String key) {
        JsonElement el = findIgnoreCaseElement(parent, key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static JsonElement findIgnoreCaseElement(JsonObject parent, String key) {
        if (parent.has(key)) {
            return parent.get(key);
        }
        for (Map.Entry<String, JsonElement> entry : parent.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Integer parseColorValue(JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        if (el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
        }
        String s = el.getAsString().trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            long parsed = Long.parseLong(s, 16);
            if (s.length() <= 6) {
                parsed |= 0xFF000000L;
            }
            return (int) parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean readAutoSaveFromFile(Path file, boolean fallback) {
        if (file == null || !Files.exists(file)) {
            return fallback;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has("autoSave") && obj.get("autoSave").isJsonPrimitive()
                        && obj.getAsJsonPrimitive("autoSave").isBoolean()) {
                    return obj.get("autoSave").getAsBoolean();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static synchronized void writeAutoSaveFlagToFile(Path file, boolean autoSave) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                obj.addProperty("autoSave", autoSave);
                writeSnapshotSync(file, obj);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to update autoSave flag in {}", file, e);
        }
    }

    private static synchronized boolean writeSnapshotSync(Path file, JsonObject snapshot) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, GSON.toJson(snapshot), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", file, e);
            return false;
        }
    }
}
