package me.itzmr.nebulavisuals.alt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import net.minecraft.util.Uuids;
import org.itzmr.nebulavisuals.mixin.client.MinecraftClientAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Менеджер оффлайн-аккаунтов Nebula Visuals.
 *
 * <p>Подменяет {@link Session} клиента в рантайме без перезапуска Minecraft, генерируя
 * стандартный оффлайн UUID через {@link Uuids#getOfflinePlayerUuid(String)}, и сохраняет
 * список аккаунтов + последний активный ник в {@code config/nebulavisuals/alts.json}.</p>
 */
public final class AltManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NebulaAltManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final Pattern DEV_PLAYER_PATTERN = Pattern.compile("^Player\\d+$");

    private static final String[] PVP_PREFIXES = {
            "Kuro", "Vex", "Raze", "Nyx", "Zeph", "Kaze", "Sora", "Kryo",
            "Mizu", "Lynx", "Nova", "Onyx", "Frost", "Blaze", "Jinx", "Apex",
            "Aether", "Cinder", "Vortex", "Shad", "Rift", "Volt", "Akira", "Ren"
    };
    private static final String[] PVP_SUFFIXES = {
            "Blade", "Strike", "Core", "Edge", "Pulse", "Rush", "Zero", "Dash",
            "Wave", "Claw", "Fang", "Spark", "PvP", "MC", "FX", "zy", "ko", "xd"
    };

    private static final List<String> ACCOUNTS = new ArrayList<>();
    private static String lastActiveUsername = "";
    private static boolean initialized = false;
    private static boolean bootSessionRestored = false;

    private static final class AltsConfig {
        String lastActive = "";
        List<String> accounts = new ArrayList<>();
    }

    private AltManager() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        load();
    }

    public static synchronized boolean isValidUsername(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * Переключает активную сессию на указанный оффлайн-ник и сохраняет его в список.
     *
     * @return {@code true}, если ник валиден и сессия успешно применена.
     */
    public static synchronized boolean loginOffline(String username) {
        if (!initialized) init();
        if (!isValidUsername(username)) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }

        UUID offlineUuid = Uuids.getOfflinePlayerUuid(username);
        Session newSession = new Session(
                username,
                offlineUuid,
                "",
                Optional.empty(),
                Optional.empty()
        );
        ((MinecraftClientAccessor) client).setSession(newSession);

        if (!ACCOUNTS.contains(username)) {
            ACCOUNTS.add(0, username);
        }
        lastActiveUsername = username;
        save();

        LOGGER.info("[NebulaVisuals] Switched offline session to '{}' (UUID: {})", username, offlineUuid);
        return true;
    }

    /**
     * Удаляет сохранённый аккаунт из списка и перезаписывает {@code alts.json}.
     */
    public static synchronized boolean removeAccount(String username) {
        if (!initialized) init();
        if (username == null) return false;
        boolean removed = ACCOUNTS.remove(username);
        if (removed) {
            if (username.equals(lastActiveUsername)) {
                lastActiveUsername = getCurrentUsername();
            }
            save();
        }
        return removed;
    }

    /**
     * При первом запуске в dev-среде (ник {@code PlayerNNN} с пустым токеном)
     * восстанавливает последний сохранённый оффлайн-аккаунт, не затрагивая реальные аккаунты.
     */
    public static synchronized void tryRestoreLastSessionOnBoot() {
        if (!initialized) init();
        if (bootSessionRestored) return;
        bootSessionRestored = true;

        if (!isValidUsername(lastActiveUsername)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSession() == null) return;

        Session current = client.getSession();
        String currentName = current.getUsername();
        String token = current.getAccessToken();
        if ((token == null || token.isEmpty())
                && currentName != null
                && DEV_PLAYER_PATTERN.matcher(currentName).matches()) {
            loginOffline(lastActiveUsername);
        }
    }

    public static synchronized List<String> getAccounts() {
        if (!initialized) init();
        return Collections.unmodifiableList(new ArrayList<>(ACCOUNTS));
    }

    public static String getCurrentUsername() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getSession() != null && client.getSession().getUsername() != null) {
            return client.getSession().getUsername();
        }
        return isValidUsername(lastActiveUsername) ? lastActiveUsername : "Player";
    }

    public static synchronized String getLastActiveUsername() {
        if (!initialized) init();
        return lastActiveUsername;
    }

    /**
     * Генерирует читаемый PvP-ник (3–16 символов {@code [a-zA-Z0-9_]}) без повторения с уже сохранёнными.
     */
    public static synchronized String generateRandomPvpName() {
        if (!initialized) init();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < 24; attempt++) {
            String prefix = PVP_PREFIXES[rng.nextInt(PVP_PREFIXES.length)];
            String suffix = PVP_SUFFIXES[rng.nextInt(PVP_SUFFIXES.length)];
            int num = rng.nextInt(10, 999);

            int pattern = rng.nextInt(3);
            String candidate = switch (pattern) {
                case 0 -> prefix + suffix + num;
                case 1 -> prefix + "_" + suffix + (num % 100);
                default -> (rng.nextBoolean() ? "x" : "Itz") + prefix + num;
            };

            if (candidate.length() > 16) {
                candidate = candidate.substring(0, 16);
                while (candidate.endsWith("_") && candidate.length() > 3) {
                    candidate = candidate.substring(0, candidate.length() - 1);
                }
            }

            if (isValidUsername(candidate)
                    && !ACCOUNTS.contains(candidate)
                    && !candidate.equalsIgnoreCase(getCurrentUsername())) {
                return candidate;
            }
        }
        return "NebulaPvP_" + rng.nextInt(100, 999);
    }

    private static Path getConfigFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("nebulavisuals").resolve("alts.json");
    }

    private static void load() {
        ACCOUNTS.clear();
        lastActiveUsername = "";

        Path file = getConfigFile();
        if (!Files.exists(file)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            AltsConfig loaded = GSON.fromJson(reader, AltsConfig.class);
            if (loaded != null) {
                if (loaded.accounts != null) {
                    for (String name : loaded.accounts) {
                        if (isValidUsername(name) && !ACCOUNTS.contains(name)) {
                            ACCOUNTS.add(name);
                        }
                    }
                }
                if (isValidUsername(loaded.lastActive)) {
                    lastActiveUsername = loaded.lastActive;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[NebulaVisuals] Failed to load alts.json: {}", e.getMessage());
        }
    }

    private static void save() {
        Path file = getConfigFile();
        try {
            Files.createDirectories(file.getParent());
            AltsConfig data = new AltsConfig();
            data.lastActive = lastActiveUsername != null ? lastActiveUsername : "";
            data.accounts = new ArrayList<>(ACCOUNTS);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("[NebulaVisuals] Failed to save alts.json: {}", e.getMessage());
        }
    }
}
