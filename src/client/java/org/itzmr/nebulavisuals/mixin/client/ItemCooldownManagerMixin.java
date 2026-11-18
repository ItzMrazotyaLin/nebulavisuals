package org.itzmr.nebulavisuals.mixin.client;

import me.itzmr.nebulavisuals.render.hud.CooldownManagerAccess;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Сохраняет конечные тики кулдаунов по группам предметов в {@link ItemCooldownManager}
 * для точного отображения оставшихся секунд в {@code Cooldowns HUD}.
 */
@Mixin(ItemCooldownManager.class)
public abstract class ItemCooldownManagerMixin implements CooldownManagerAccess {
    @Shadow private int tick;

    @Shadow public abstract Identifier getGroup(ItemStack stack);
    @Shadow public abstract float getCooldownProgress(ItemStack stack, float tickProgress);

    @Unique private final Map<Identifier, Integer> nebulavisuals$endTicks = new HashMap<>();

    @Inject(method = "set(Lnet/minecraft/util/Identifier;I)V", at = @At("HEAD"))
    private void nebulavisuals$trackCooldownEnd(Identifier groupId, int duration, CallbackInfo ci) {
        if (groupId == null) {
            return;
        }
        if (duration <= 0) {
            this.nebulavisuals$endTicks.remove(groupId);
        } else {
            this.nebulavisuals$endTicks.put(groupId, this.tick + duration);
        }
    }

    @Inject(method = "remove(Lnet/minecraft/util/Identifier;)V", at = @At("HEAD"))
    private void nebulavisuals$clearTrackedCooldown(Identifier groupId, CallbackInfo ci) {
        if (groupId != null) {
            this.nebulavisuals$endTicks.remove(groupId);
        }
    }

    @Override
    public float nebulavisuals$getRemainingSeconds(ItemStack stack, float tickProgress) {
        if (stack == null || stack.isEmpty()) {
            return 0.0f;
        }
        Identifier group = this.getGroup(stack);
        Integer endTick = this.nebulavisuals$endTicks.get(group);
        if (endTick != null) {
            float remainingTicks = Math.max(0.0f, endTick - (this.tick + tickProgress));
            return remainingTicks / 20.0f;
        }
        return this.getCooldownProgress(stack, tickProgress);
    }
}
