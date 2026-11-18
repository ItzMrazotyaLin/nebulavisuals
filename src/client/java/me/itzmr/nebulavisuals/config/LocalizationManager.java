package me.itzmr.nebulavisuals.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Локализация интерфейса Nebula Visuals (Русский / English).
 * <p>
 * Принцип работы:
 * - Выбранный язык ({@code ru}, {@code en}) хранится глобально в
 *   {@code <gameDir>/config/nebulavisuals_client.json} и не зависит от профилей в {@code C:\Nebula\configs}.
 * - При первом запуске (если файл ещё не создан) язык определяется по {@link Locale#getDefault()} и сразу сохраняется.
 * - Названия модулей, бренд {@code NebulaVisuals}, строки версий и внутренние ключи JSON-конфигов НИКОГДА не переводятся.
 * - Переводятся только отображаемые в UI подписи, категории, настройки, описания, кнопки, модальные окна и уведомления.
 */
public final class LocalizationManager {
    public enum LanguageMode {
        RU("ru"),
        EN("en");

        public final String id;

        LanguageMode(String id) {
            this.id = id;
        }

        public static LanguageMode fromId(String raw, LanguageMode fallback) {
            if (raw == null) {
                return fallback;
            }
            for (LanguageMode mode : values()) {
                if (mode.id.equalsIgnoreCase(raw.trim())) {
                    return mode;
                }
            }
            return fallback;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("NebulaVisuals-Lang");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CLIENT_CONFIG_FILE = "nebulavisuals_client.json";
    private static final boolean OS_RUSSIAN = "ru".equalsIgnoreCase(Locale.getDefault().getLanguage());

    private static volatile LanguageMode languageMode = OS_RUSSIAN ? LanguageMode.RU : LanguageMode.EN;
    private static volatile boolean initialized = false;

    private static final Map<String, String> UI_EN = new HashMap<>();
    private static final Map<String, String> UI_RU = new HashMap<>();
    private static final Map<String, String> TABS_RU = new HashMap<>();
    private static final Map<String, String> MODULE_DESCS_RU = new HashMap<>();
    private static final Map<String, String> SETTING_NAMES_RU = new HashMap<>();
    private static final Map<String, String> ACTION_LABELS_RU = new HashMap<>();

    static {
        // --- Вкладки сайдбара (категории Visuals / HUD / Utilities всегда на английском) ---
        TABS_RU.put("Settings", "Настройки");
        TABS_RU.put("Optimization", "Оптимизация");
        TABS_RU.put("Configs", "Конфиги");
        TABS_RU.put("About", "О моде");

        // --- UI / Вкладка About ---
        regUi("about.welcome", "Welcome to the", "Добро пожаловать в");
        regUi("about.dedicated", "Dedicated to the NebulaPVP server", "Посвящено серверу NebulaPVP");
        regUi("about.creator", "Creator:", "Создатель:");
        regUi("about.website", "Project Website", "Сайт проекта");
        regUi("about.opened", "(opened)", "(открыто)");

        // --- UI / Вкладка Settings ---
        regUi("settings.interface_motion", "Interface & Motion", "Интерфейс и анимации");
        regUi("settings.language", "Interface Language", "Язык интерфейса");

        // --- UI / Вкладка Configs ---
        regUi("configs.title", "Configs", "Конфиги");
        regUi("configs.auto_save", "Auto-save", "Автосохр.");
        regUi("configs.new_placeholder", "New config name...", "Имя нового конфига...");
        regUi("configs.create", "Create", "Создать");
        regUi("configs.badge.auto_save", "Auto-save", "Автосохр.");
        regUi("configs.badge.active_dirty", "Active *", "Активен *");
        regUi("configs.badge.active", "Active", "Активен");
        regUi("configs.badge.default", "Default", "По умолч.");

        // --- Модальные окна ---
        regUi("modal.yes", "Yes", "Да");
        regUi("modal.no", "No", "Нет");
        regUi("modal.overwrite", "Overwrite config \"%s\" with current settings?", "Перезаписать конфиг «%s» текущими настройками?");
        regUi("modal.switch", "You have unsaved changes in \"%s\". Switch to \"%s\" without saving?", "В «%s» есть несохранённые изменения. Переключиться на «%s» без сохранения?");
        regUi("modal.reset", "Reset config \"%s\" to default settings?", "Сбросить конфиг «%s» до настроек по умолчанию?");

        // --- Статусы вкладки Configs ---
        regUi("configs.status.loaded_default", "Loaded 'Default' profile (Auto-Save)", "Загружен профиль 'Default' (Автосохр.)");
        regUi("configs.status.loaded", "Loaded config '%s'", "Загружен конфиг '%s'");
        regUi("configs.status.load_failed", "Failed to load '%s'", "Не удалось загрузить '%s'");
        regUi("configs.status.overwritten", "Overwrote config '%s'", "Перезаписан конфиг '%s'");
        regUi("configs.status.save_failed", "Failed to save '%s'", "Ошибка сохранения '%s'");
        regUi("configs.status.reset_done", "Reset config '%s' to defaults", "Сброшен конфиг '%s' по умолчанию");
        regUi("configs.status.reset_failed", "Failed to reset '%s'", "Не удалось сбросить '%s'");
        regUi("configs.status.enter_name", "Enter a config name", "Введите имя конфига");
        regUi("configs.status.reserved_default", "Name 'Default' is reserved", "Имя 'Default' зарезервировано");
        regUi("configs.status.created", "Created and activated '%s'", "Создан и активирован конфиг '%s'");
        regUi("configs.status.exists", "Config '%s' already exists", "Конфиг '%s' уже существует");
        regUi("configs.status.autosave_state", "Auto-save for '%s': %s", "Автосохр. для '%s': %s");
        regUi("configs.status.on", "on", "вкл");
        regUi("configs.status.off", "off", "выкл");
        regUi("configs.status.opened_folder", "Opened folder: %s", "Открыта папка: %s");
        regUi("configs.status.refreshed", "Config list refreshed", "Список конфигов обновлён");
        regUi("configs.status.renamed_short", "Renamed to '%s'", "Переименовано в '%s'");
        regUi("configs.status.renamed_pair", "Renamed: '%s' -> '%s'", "Переименовано: '%s' -> '%s'");
        regUi("configs.status.rename_empty", "Config name cannot be empty", "Имя конфига не может быть пустым");
        regUi("configs.status.rename_failed", "Failed to rename to '%s'", "Не удалось переименовать в '%s'");
        regUi("configs.status.deleted", "Deleted config '%s'", "Удалён конфиг '%s'");

        // --- Уведомления ConfigManager ---
        regUi("notif.config.notice.title", "Config Notice", "Уведомление конфига");
        regUi("notif.config.created.title", "Config Created", "Конфиг создан");
        regUi("notif.config.created.desc", "Created & switched to \"%s\"", "Создан и выбран «%s»");
        regUi("notif.config.saved.title", "Config Saved", "Конфиг сохранён");
        regUi("notif.config.saved.desc", "Saved \"%s\"", "Сохранён «%s»");
        regUi("notif.config.loaded.title", "Config Loaded", "Конфиг загружен");
        regUi("notif.config.loaded.desc", "Loaded \"%s\"", "Загружен «%s»");
        regUi("notif.config.renamed.title", "Config Renamed", "Конфиг переименован");
        regUi("notif.config.deleted.title", "Config Deleted", "Конфиг удалён");
        regUi("notif.config.deleted.desc", "Deleted \"%s\"", "Удалён «%s»");
        regUi("notif.config.reset.title", "Config Reset", "Конфиг сброшен");
        regUi("notif.config.reset.desc", "Reset \"%s\" to defaults", "Сброшен «%s» до настроек по умолчанию");
        regUi("notif.config.migrated", "Config \"%s\" migrated to the current version.", "Конфиг «%s» обновлён до текущей версии.");
        regUi("notif.config.corrupted", "Corrupted config \"%s\" was recreated with default settings.", "Повреждённый конфиг «%s» пересоздан с настройками по умолчанию.");

        // --- Notifications HUD ---
        regUi("notif.preview.desc", "Config & durability alerts appear here", "Здесь появляются уведомления о конфигах и прочности");
        regUi("notif.default_title", "Notice", "Уведомление");
        regUi("notif.module.enabled", "Enabled", "Включён");
        regUi("notif.module.disabled", "Disabled", "Выключен");
        regUi("notif.durability.title", "Low Durability", "Низкая прочность");
        regUi("notif.effect.title", "Effect Expiring", "Эффект заканчивается");
        regUi("notif.effect.desc", "%s ends in 5s", "%s закончится через 5с");

        // --- HUD Editor ---
        regUi("hud_editor.resize", "Resize", "Размер");
        regUi("hud_editor.apply", "Apply", "Применить");
        regUi("hud_editor.defaults", "Defaults", "Сброс");
        regUi("hud_editor.cancel", "Cancel", "Отмена");

        // --- Potions HUD секции ---
        regUi("potions.beneficial", "Beneficial", "Полезные");
        regUi("potions.harmful", "Harmful", "Вредные");

        // --- Память (чат) ---
        regUi("memory.freed", "§d[Nebula] §fFreed %d MB RAM", "§d[Nebula] §fОсвобождено %d МБ ОЗУ");

        // --- Привязки клавиш ---
        regUi("key.none", "None", "Нет");
        regUi("key.bind", "Bind...", "Нажмите...");

        // --- Описания модулей ---
        MODULE_DESCS_RU.put("Displays equipped armor pieces and remaining durability", "Показывает надетую броню и её оставшуюся прочность");
        MODULE_DESCS_RU.put("Move vanilla boss bars to Left, Center, or Right", "Выравнивает ванильную полосу босса по левому краю, центру или правому краю");
        MODULE_DESCS_RU.put("Displays active item cooldowns with timers and progress bars", "Показывает активные задержки предметов с таймерами и шкалой прогресса");
        MODULE_DESCS_RU.put("Crisp vector crosshair with dynamic gap and target highlight", "Чёткий векторный прицел с динамическим зазором и подсветкой цели");
        MODULE_DESCS_RU.put("Minimalist frosted glass WASD, mouse & CPS indicator", "Минималистичная стеклянная панель клавиш WASD, кнопок мыши и CPS");
        MODULE_DESCS_RU.put("Sleek frosted glass toast notifications with slide animations", "Стеклянные всплывающие уведомления с плавными анимациями");
        MODULE_DESCS_RU.put("Displays active status effects with timers and category grouping", "Показывает активные эффекты с таймерами и разделением по категориям");
        MODULE_DESCS_RU.put("Frosted glass combat card with target health, skin & distance", "Стеклянная карточка цели со здоровьем, скином и дистанцией");
        MODULE_DESCS_RU.put("Frosted glass HUD capsule with live client telemetry", "Стеклянная капсула с телеметрией клиента в реальном времени");
        MODULE_DESCS_RU.put("Automatically sprint while moving forward", "Автоматический спринт при движении вперёд");
        MODULE_DESCS_RU.put("Rotate camera 360 degrees while holding a key without rotating your player", "Свободный обзор камеры на 360° при удержании клавиши без поворота игрока");
        MODULE_DESCS_RU.put("See clearly in dark caves and areas without night vision potion", "Ясная видимость в тёмных пещерах и областях без зелья ночного зрения");
        MODULE_DESCS_RU.put("Smooth animations for inventories, lists, chat, hotbar, creative & F5 camera", "Плавные анимации инвентаря, списков, чата, хотбара, креатива и камеры F5");
        MODULE_DESCS_RU.put("Smooth neon 3D block highlight with interpolated box sliding", "Плавная неоновая 3D-подсветка блока со скольжением рамки");
        MODULE_DESCS_RU.put("Translucent 3D gradient cone hat above head in third person", "Полупрозрачная 3D-шляпа конусом над головой в виде от третьего лица");
        MODULE_DESCS_RU.put("World-locked procedural shader skybox with multiple cosmic patterns", "Процедурный шейдерный скайбокс с выбором космических узоров");
        MODULE_DESCS_RU.put("Custom translucent glowing 3D hitboxes with optional eye line & hurt flash", "Светящиеся 3D-хитбоксы с линией взгляда и вспышкой при уроне");
        MODULE_DESCS_RU.put("Glowing 3D impact spheres with gravity and block bounce", "Светящиеся 3D-частицы при ударе с гравитацией и отскоком от блоков");
        MODULE_DESCS_RU.put("Glowing 3D impact particles with shapes, gravity and block bounce", "Светящиеся 3D-частицы разных форм с гравитацией и отскоком от блоков");
        MODULE_DESCS_RU.put("Makes dropped items tumble in mid-air and lie flat on the ground", "Вращение выброшенных предметов в воздухе и укладка плашмя на земле");
        MODULE_DESCS_RU.put("Smooth expanding 3D energy rings under jumping players", "Плавные расширяющиеся 3D-кольца под прыгающими игроками");
        MODULE_DESCS_RU.put("Spawns stylized 3D visual effects when nearby entities die", "Стилизованные 3D-эффекты при гибели сущностей рядом");
        MODULE_DESCS_RU.put("Camera matrix & depth-reprojected motion blur for 3D world", "Размытие движения мира по матрицам камеры и буферу глубины");
        MODULE_DESCS_RU.put("Simulates accurate 3D trajectories & impact markers for held projectiles", "Точная 3D-траектория и точка падения снарядов в руках");
        MODULE_DESCS_RU.put("Display your own vanilla nametag in third-person perspective", "Отображает собственный никнейм игрока в виде от третьего лица");
        MODULE_DESCS_RU.put("Make rain and snow thinner and more transparent for clear PvP visibility", "Делает дождь и снег тоньше и прозрачнее для лучшего обзора в PvP");
        MODULE_DESCS_RU.put("3D orbiting orbs and pulsing scan ring around combat target", "3D-сферы и эффекты вокруг текущей боевой цели");
        MODULE_DESCS_RU.put("Smooth glowing 3D motion ribbons behind player and projectiles", "Плавный светящийся 3D-шлейф за игроком и снарядами");
        MODULE_DESCS_RU.put("Customize first-person hand position and held item scale", "Настройка позиции рук и размера предметов в первом лице");
        // --- Описания модулей Optimization и Utilities ---
        MODULE_DESCS_RU.put("Toggle expensive animated textures, 3D books, beacon beams & item glint", "Отключает тяжёлые анимированные текстуры, книги, лучи маяка и блеск чар");
        MODULE_DESCS_RU.put("Distance-cull block entities, foliage, pots, seagrass, glass, doors, beds & more", "Отсечение далёких блок-сущностей, листвы, травы, стекла, дверей и кроватей");
        MODULE_DESCS_RU.put("Distance-cull frames, armor stands, items, mobs & fire while keeping combat safe", "Отсечение далёких рамок, стоек, предметов, мобов и огня с защитой боя");
        MODULE_DESCS_RU.put("Monitor JVM heap usage and safely free unused memory & temporary caches", "Следит за памятью JVM и безопасно очищает неиспользуемый кэш");
        MODULE_DESCS_RU.put("Disable obstructing screen overlays, hurt shake, and totem popup", "Убирает мешающие экранные оверлеи, тряску от урона и анимацию тотема");
        MODULE_DESCS_RU.put("Frustum/wall-cull hidden particles and deterministically cap excess particles", "Отсекает скрытые частицы и ограничивает плотные скопления без мерцания");
        MODULE_DESCS_RU.put("Throttle FPS when unfocused and optionally skip world render while minimized", "Ограничивает FPS в фоне и пропускает рендер мира при свёрнутом окне");
        MODULE_DESCS_RU.put("Hold key to smoothly zoom FOV and adjust magnification with scroll wheel", "Плавное приближение камеры по клавише и настройка кратности колёсиком мыши");
        MODULE_DESCS_RU.put("Overrides client world time, lighting and atmospheric fog distance", "Меняет клиентское время суток и дальность тумана");

        // --- Названия настроек ---
        SETTING_NAMES_RU.put("Accent Color", "Акцентный цвет");
        SETTING_NAMES_RU.put("Saturation", "Насыщенность");
        SETTING_NAMES_RU.put("Brightness", "Яркость");
        SETTING_NAMES_RU.put("ClickGUI Scale", "Масштаб ClickGUI");
        SETTING_NAMES_RU.put("UI Opacity", "Прозрачность UI");
        SETTING_NAMES_RU.put("Blur Strength", "Сила размытия");
        SETTING_NAMES_RU.put("Animation Speed", "Скорость анимаций");

        SETTING_NAMES_RU.put("Amount", "Количество");
        SETTING_NAMES_RU.put("Animation", "Анимация");
        SETTING_NAMES_RU.put("Armor Stands", "Стойки для брони");
        SETTING_NAMES_RU.put("Auto Clean", "Авто-очистка");
        SETTING_NAMES_RU.put("Avoid BossBars", "Обход BossBar");
        SETTING_NAMES_RU.put("Banners", "Знамена");
        SETTING_NAMES_RU.put("Banners Dist", "Дист. знамён");
        SETTING_NAMES_RU.put("Beacon & Anchor", "Маяк и якорь");
        SETTING_NAMES_RU.put("Beds", "Кровати");
        SETTING_NAMES_RU.put("Beds Dist", "Дист. кроватей");
        SETTING_NAMES_RU.put("Carpets", "Ковры");
        SETTING_NAMES_RU.put("Carpets Dist", "Дист. ковров");
        SETTING_NAMES_RU.put("Chests", "Сундуки");
        SETTING_NAMES_RU.put("Chests Dist", "Дист. сундуков");
        SETTING_NAMES_RU.put("Clean Now", "Очистить сейчас");
        SETTING_NAMES_RU.put("Color Mode", "Режим цвета");
        SETTING_NAMES_RU.put("Config Actions", "Действия с конфигами");
        SETTING_NAMES_RU.put("Creative Scroll", "Плавный креатив");
        SETTING_NAMES_RU.put("Crops & Saplings", "Посевы и саженцы");
        SETTING_NAMES_RU.put("Crops Dist", "Дист. посевов");
        SETTING_NAMES_RU.put("Custom Time", "Своё время");
        SETTING_NAMES_RU.put("Damage Highlight", "Подсветка урона");
        SETTING_NAMES_RU.put("Doors & Trapdoors", "Двери и люки");
        SETTING_NAMES_RU.put("Doors Dist", "Дист. дверей");
        SETTING_NAMES_RU.put("Dropped Items", "Выброшенные предметы");
        SETTING_NAMES_RU.put("Durability Mode", "Вид прочности");
        SETTING_NAMES_RU.put("Duration", "Длительность");
        SETTING_NAMES_RU.put("Dynamic", "Динамический");
        SETTING_NAMES_RU.put("Enchant Glint", "Блеск чар");
        SETTING_NAMES_RU.put("Enchanting Table", "Стол зачарований");
        SETTING_NAMES_RU.put("Expiring Effects", "Истекающие эффекты");
        SETTING_NAMES_RU.put("Eye & Look Vector", "Уровень глаз и взгляд");
        SETTING_NAMES_RU.put("F5 Transition", "Плавный F5");
        SETTING_NAMES_RU.put("Fill Alpha", "Прозр. заливки");
        SETTING_NAMES_RU.put("Fill Opacity", "Заливка граней");
        SETTING_NAMES_RU.put("Fire Blocks", "Блоки огня");
        SETTING_NAMES_RU.put("Fire Dist", "Дист. огня");
        SETTING_NAMES_RU.put("First Person", "От первого лица");
        SETTING_NAMES_RU.put("Flower Pots", "Цветочные горшки");
        SETTING_NAMES_RU.put("Fog Distance", "Дальность тумана");
        SETTING_NAMES_RU.put("Fog Sync", "Цвет тумана под небо");
        SETTING_NAMES_RU.put("Foliage & Flowers", "Трава и цветы");
        SETTING_NAMES_RU.put("Foliage Dist", "Дист. травы");
        SETTING_NAMES_RU.put("Frames Dist", "Дист. рамок");
        SETTING_NAMES_RU.put("Gap", "Зазор");
        SETTING_NAMES_RU.put("Glass & Panes", "Стекло и панели");
        SETTING_NAMES_RU.put("Glass Dist", "Дист. стекла");
        SETTING_NAMES_RU.put("GUI Animations", "Анимации GUI");
        SETTING_NAMES_RU.put("Height", "Высота");
        SETTING_NAMES_RU.put("Hold Key", "Клавиша удержания");
        SETTING_NAMES_RU.put("Item Frames", "Рамки предметов");
        SETTING_NAMES_RU.put("Item Scale", "Масштаб предмета");
        SETTING_NAMES_RU.put("Items", "Предметы");
        SETTING_NAMES_RU.put("Items Dist", "Дист. предметов");
        SETTING_NAMES_RU.put("Length", "Длина");
        SETTING_NAMES_RU.put("Life Time", "Время жизни");
        SETTING_NAMES_RU.put("Line Width", "Толщина линий");
        SETTING_NAMES_RU.put("Low Durability", "Низкая прочность");
        SETTING_NAMES_RU.put("Main Hand X", "Правая рука X");
        SETTING_NAMES_RU.put("Main Hand Y", "Правая рука Y");
        SETTING_NAMES_RU.put("Main Hand Z", "Правая рука Z");
        SETTING_NAMES_RU.put("Max Distance", "Макс. дистанция");
        SETTING_NAMES_RU.put("Max Particles", "Макс. частиц");
        SETTING_NAMES_RU.put("Max Radius", "Макс. радиус");
        SETTING_NAMES_RU.put("Mobs", "Мобы");
        SETTING_NAMES_RU.put("Mobs & Monsters", "Мобы и монстры");
        SETTING_NAMES_RU.put("Mobs Dist", "Дист. мобов");
        SETTING_NAMES_RU.put("Mode", "Режим");
        SETTING_NAMES_RU.put("Module Toggles", "Переключение модулей");
        SETTING_NAMES_RU.put("Nebula Intensity", "Яркость туманности");
        SETTING_NAMES_RU.put("No Fire Overlay", "Убрать огонь на экране");
        SETTING_NAMES_RU.put("No Hurt Cam", "Убрать тряску камеры");
        SETTING_NAMES_RU.put("No Pumpkin & Vignette", "Убрать тыкву и виньетку");
        SETTING_NAMES_RU.put("No Totem Animation", "Убрать анимацию тотема");
        SETTING_NAMES_RU.put("Off Hand X", "Левая рука X");
        SETTING_NAMES_RU.put("Off Hand Y", "Левая рука Y");
        SETTING_NAMES_RU.put("Off Hand Z", "Левая рука Z");
        SETTING_NAMES_RU.put("Only In Combat", "Только в бою");
        SETTING_NAMES_RU.put("Only Players", "Только игроки");
        SETTING_NAMES_RU.put("Only Sword", "Только с мечом");
        SETTING_NAMES_RU.put("Opacity", "Непрозрачность");
        SETTING_NAMES_RU.put("Orientation", "Ориентация");
        SETTING_NAMES_RU.put("Other Players", "Другие игроки");
        SETTING_NAMES_RU.put("Outline", "Обводка");
        SETTING_NAMES_RU.put("Physics Bounce", "Отскок от блоков");
        SETTING_NAMES_RU.put("Players", "Игроки");
        SETTING_NAMES_RU.put("Portals & Campfires", "Порталы и костры");
        SETTING_NAMES_RU.put("Position", "Позиция");
        SETTING_NAMES_RU.put("Pots Dist", "Дист. горшков");
        SETTING_NAMES_RU.put("Projectiles", "Снаряды");
        SETTING_NAMES_RU.put("Radius", "Радиус");
        SETTING_NAMES_RU.put("Render Mode", "Режим отрисовки");
        SETTING_NAMES_RU.put("Rotation Speed", "Скорость вращения");
        SETTING_NAMES_RU.put("Samples", "Сэмплы");
        SETTING_NAMES_RU.put("Scale", "Масштаб");
        SETTING_NAMES_RU.put("Seagrass & Kelp", "Водоросли");
        SETTING_NAMES_RU.put("Seagrass Dist", "Дист. водорослей");
        SETTING_NAMES_RU.put("Self", "Свой хитбокс");
        SETTING_NAMES_RU.put("Shape", "Форма");
        SETTING_NAMES_RU.put("Show Absorption", "Показывать поглощение");
        SETTING_NAMES_RU.put("Show Bar", "Полоса прогресса");
        SETTING_NAMES_RU.put("Show CPS", "Показывать CPS");
        SETTING_NAMES_RU.put("Show Distance", "Дистанция");
        SETTING_NAMES_RU.put("Show FPS", "Показывать FPS");
        SETTING_NAMES_RU.put("Show In Third Person", "Видно в 3-м лице");
        SETTING_NAMES_RU.put("Show Landing Ring", "Кольцо падения");
        SETTING_NAMES_RU.put("Show Mouse", "Кнопки мыши");
        SETTING_NAMES_RU.put("Show On Target", "Над целью");
        SETTING_NAMES_RU.put("Show Ping", "Показывать пинг");
        SETTING_NAMES_RU.put("Show Server", "Показывать сервер");
        SETTING_NAMES_RU.put("Show Space", "Клавиша пробела");
        SETTING_NAMES_RU.put("Show Status", "Статус WIN/LOSE");
        SETTING_NAMES_RU.put("Show System Time", "Системное время");
        SETTING_NAMES_RU.put("Show Username", "Имя игрока");
        SETTING_NAMES_RU.put("Signs", "Таблички");
        SETTING_NAMES_RU.put("Signs Dist", "Дист. табличек");
        SETTING_NAMES_RU.put("Size", "Размер");
        SETTING_NAMES_RU.put("Skip World Render", "Пауза при свёрнутом");
        SETTING_NAMES_RU.put("Sky Mode", "Режим неба");
        SETTING_NAMES_RU.put("Smooth Hotbar", "Плавный хотбар");
        SETTING_NAMES_RU.put("Smooth Slide", "Плавное скольжение");
        SETTING_NAMES_RU.put("Smooth Speed", "Плавность полосы HP");
        SETTING_NAMES_RU.put("Smooth Zoom", "Плавный зум");
        SETTING_NAMES_RU.put("Speed", "Скорость");
        SETTING_NAMES_RU.put("Split Good & Bad", "Разделять эффекты");
        SETTING_NAMES_RU.put("Stands Dist", "Дист. стоек");
        SETTING_NAMES_RU.put("Stars Intensity", "Яркость звёзд");
        SETTING_NAMES_RU.put("Start Zoom", "Начальный зум");
        SETTING_NAMES_RU.put("Strength", "Интенсивность");
        SETTING_NAMES_RU.put("Style", "Стиль");
        SETTING_NAMES_RU.put("Thickness", "Толщина");
        SETTING_NAMES_RU.put("Threshold %", "Порог памяти %");
        SETTING_NAMES_RU.put("Time", "Время суток");
        SETTING_NAMES_RU.put("Trigger", "Условие");
        SETTING_NAMES_RU.put("Unfocused FPS", "FPS в фоне");
        SETTING_NAMES_RU.put("Water & Lava", "Вода и лава");
        SETTING_NAMES_RU.put("Workstations", "Верстаки и печи");
        SETTING_NAMES_RU.put("Workstations Dist", "Дист. печей");
        SETTING_NAMES_RU.put("Zoom Key", "Клавиша зума");

        // --- Кнопки ActionSetting ---
        ACTION_LABELS_RU.put("Clean", "Очистить");

        init();
    }

    private LocalizationManager() {}

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        loadClientLanguage();
    }

    private static Path resolveClientConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(CLIENT_CONFIG_FILE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void loadClientLanguage() {
        LanguageMode osDefault = OS_RUSSIAN ? LanguageMode.RU : LanguageMode.EN;
        Path path = resolveClientConfigPath();
        if (path == null) {
            languageMode = osDefault;
            return;
        }
        if (!Files.exists(path)) {
            languageMode = osDefault;
            saveClientLanguage();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has("language") && obj.get("language").isJsonPrimitive()
                        && obj.getAsJsonPrimitive("language").isString()) {
                    String rawId = obj.get("language").getAsString();
                    LanguageMode parsedMode = LanguageMode.fromId(rawId, osDefault);
                    languageMode = parsedMode;
                    if (!parsedMode.id.equalsIgnoreCase(rawId.trim())) {
                        saveClientLanguage();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read client language config {}: {}", path, e.getMessage());
        }
        languageMode = osDefault;
        saveClientLanguage();
    }

    private static synchronized void saveClientLanguage() {
        Path path = resolveClientConfigPath();
        if (path == null) {
            return;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            JsonObject obj = new JsonObject();
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement existing = JsonParser.parseReader(reader);
                    if (existing != null && existing.isJsonObject()) {
                        obj = existing.getAsJsonObject();
                    }
                } catch (Exception ignored) {
                }
            }
            obj.addProperty("language", languageMode.id);
            Files.writeString(path, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to save client language config {}: {}", path, e.getMessage());
        }
    }

    public static LanguageMode getLanguageMode() {
        return languageMode;
    }

    public static void setLanguageMode(LanguageMode mode) {
        if (mode == null || mode == languageMode) {
            return;
        }
        languageMode = mode;
        saveClientLanguage();
    }

    private static void regUi(String key, String en, String ru) {
        UI_EN.put(key, en);
        UI_RU.put(key, ru);
    }

    public static boolean isRussian() {
        return languageMode == LanguageMode.RU;
    }

    public static String tr(String key, Object... args) {
        String template = isRussian()
                ? UI_RU.getOrDefault(key, UI_EN.getOrDefault(key, key))
                : UI_EN.getOrDefault(key, key);
        if (args == null || args.length == 0) {
            return template;
        }
        return String.format(Locale.ROOT, template, args);
    }

    public static String tabLabel(String englishLabel) {
        if (!isRussian() || englishLabel == null) {
            return englishLabel == null ? "" : englishLabel;
        }
        return TABS_RU.getOrDefault(englishLabel, englishLabel);
    }

    public static String moduleDescription(String englishDesc) {
        if (!isRussian() || englishDesc == null) {
            return englishDesc == null ? "" : englishDesc;
        }
        return MODULE_DESCS_RU.getOrDefault(englishDesc, englishDesc);
    }

    public static String settingName(String englishName) {
        if (!isRussian() || englishName == null) {
            return englishName == null ? "" : englishName;
        }
        return SETTING_NAMES_RU.getOrDefault(englishName, englishName);
    }

    public static String actionLabel(String englishLabel) {
        if (!isRussian() || englishLabel == null) {
            return englishLabel == null ? "" : englishLabel;
        }
        return ACTION_LABELS_RU.getOrDefault(englishLabel, englishLabel);
    }

    public static String keyDisplay(String rawKeyDisplay) {
        if (rawKeyDisplay == null) {
            return "";
        }
        if ("NONE".equals(rawKeyDisplay)) {
            return tr("key.none");
        }
        if ("...".equals(rawKeyDisplay)) {
            return tr("key.bind");
        }
        return rawKeyDisplay;
    }
}
