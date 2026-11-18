package org.itzmr.nebulavisuals.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Позволяет подменять активную игровую сессию {@link Session} без перезапуска клиента.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Mutable
    @Accessor("session")
    void setSession(Session session);
}
