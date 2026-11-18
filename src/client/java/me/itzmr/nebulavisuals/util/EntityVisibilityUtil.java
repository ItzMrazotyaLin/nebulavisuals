package me.itzmr.nebulavisuals.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Централизованный хелпер легитной видимости невидимых сущностей
 * и детектор скрытого/статичного здоровья (FunTime / серверные анти-индикаторы).
 */
public final class EntityVisibilityUtil {
    private static final long HIT_REVEAL_TIMEOUT_NS = 4_000_000_000L;
    private static final long HIDDEN_HP_TTL_NS = 10_000_000_000L;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final EquipmentSlot[] VISIBLE_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private static int lastHitEntityId = -1;
    private static long lastHitTimeNs = 0L;
    private static final Map<Integer, Long> HIDDEN_HP_ENTITIES = new HashMap<>();

    private EntityVisibilityUtil() {}

    /**
     * Фиксирует удар локального игрока по сущности (разрешает показ TargetHUD / TargetESP на 4.0 сек).
     */
    public static void recordPlayerAttack(Entity target) {
        if (target == null) {
            return;
        }
        lastHitEntityId = target.getId();
        lastHitTimeNs = System.nanoTime();
    }

    public static void clearHitIfMatches(Entity target) {
        if (target != null && target.getId() == lastHitEntityId) {
            lastHitEntityId = -1;
            lastHitTimeNs = 0L;
        }
    }

    public static void clearAll() {
        lastHitEntityId = -1;
        lastHitTimeNs = 0L;
        HIDDEN_HP_ENTITIES.clear();
    }

    public static boolean isRecentlyHit(Entity entity) {
        if (entity == null || entity.getId() != lastHitEntityId || lastHitTimeNs == 0L) {
            return false;
        }
        if ((System.nanoTime() - lastHitTimeNs) > HIT_REVEAL_TIMEOUT_NS) {
            lastHitEntityId = -1;
            lastHitTimeNs = 0L;
            return false;
        }
        return true;
    }

    /**
     * Проверяет, надета ли хотя бы одна видимая часть брони на сущности.
     */
    public static boolean hasVisibleArmor(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getEquippedStack(slot);
            if (stack != null && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверяет, есть ли на сущности видимая броня или предмет в руках.
     */
    public static boolean hasVisibleArmorOrHandItem(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        for (EquipmentSlot slot : VISIBLE_SLOTS) {
            ItemStack stack = entity.getEquippedStack(slot);
            if (stack != null && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Единое правило легитного отображения визуалов:
     * видимые сущности разрешены всегда; невидимые — ТОЛЬКО если на них есть видимая броня/предмет
     * или локальный игрок ударил эту сущность в течение последних 4.0 секунд.
     */
    public static boolean canRenderVisualsOn(Entity entity) {
        if (entity == null || entity.isRemoved() || entity.isSpectator()) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && entity == client.player) {
            return true;
        }
        if (entity instanceof LivingEntity living) {
            return canRenderVisualsOn(living);
        }
        return !entity.isInvisible();
    }

    public static boolean canRenderVisualsOn(LivingEntity entity) {
        if (entity == null || entity.isRemoved() || entity.isSpectator()) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && entity == client.player) {
            return true;
        }
        boolean invisible = entity.isInvisible()
                || (client != null && client.player != null && entity.isInvisibleTo(client.player));
        if (!invisible) {
            return true;
        }
        return hasVisibleArmorOrHandItem(entity) || isRecentlyHit(entity);
    }

    /**
     * Определяет, скрыто ли сервером реальное здоровье цели:
     * невидимость ИЛИ застывшие 20.0 HP / {@code <= 0} при получении урона ({@code hurtTime > 0}).
     */
    public static boolean isHealthHidden(LivingEntity target) {
        if (target == null) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (target.isInvisible() || (client != null && client.player != null && target.isInvisibleTo(client.player))) {
            return true;
        }

        long now = System.nanoTime();
        cleanupExpiredHiddenEntries(now);

        float health = target.getHealth();
        if (target.isAlive() && health > 0.05f && health < 19.95f) {
            HIDDEN_HP_ENTITIES.remove(target.getId());
            return false;
        }

        boolean static20OnHit = target.hurtTime > 0
                && target.getAbsorptionAmount() <= 0.001f
                && health >= 20.0f;
        boolean zeroWhileAlive = target.isAlive() && health <= 0.0f;

        if (static20OnHit || zeroWhileAlive) {
            HIDDEN_HP_ENTITIES.put(target.getId(), now);
            return true;
        }

        Long markedAt = HIDDEN_HP_ENTITIES.get(target.getId());
        return markedAt != null && (now - markedAt) <= HIDDEN_HP_TTL_NS;
    }

    private static void cleanupExpiredHiddenEntries(long now) {
        if (HIDDEN_HP_ENTITIES.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Long>> it = HIDDEN_HP_ENTITIES.entrySet().iterator();
        while (it.hasNext()) {
            if ((now - it.next().getValue()) > HIDDEN_HP_TTL_NS) {
                it.remove();
            }
        }
    }
}
