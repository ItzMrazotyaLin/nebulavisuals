package org.itzmr.nebulavisuals.mixin.client;

import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/**
 * Доступ к карте активных BossBar'ов в BossBarHud для умного сдвига HUD-элементов.
 */
@Mixin(BossBarHud.class)
public interface BossBarHudAccessor {
    @Accessor("bossBars")
    Map<UUID, ClientBossBar> nebulavisuals$getBossBars();
}
