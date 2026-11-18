package me.itzmr.nebulavisuals.render.hud;

import net.minecraft.item.ItemStack;

/**
 * Доступ к оставшемуся времени кулдауна предметов в {@code ItemCooldownManager}.
 */
public interface CooldownManagerAccess {
    float nebulavisuals$getRemainingSeconds(ItemStack stack, float tickProgress);
}
