package me.itzmr.nebulavisuals.module.utilities;

import me.itzmr.nebulavisuals.module.Category;
import me.itzmr.nebulavisuals.module.ModuleManager;
import me.itzmr.nebulavisuals.module.VisualModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Модуль AutoSprint (категория Utilities): автоматически включает бег (спринт),
 * когда игрок движется вперёд и не заблокирован стеной/голодом/приседанием.
 */
public class AutoSprintModule extends VisualModule {

    public AutoSprintModule() {
        super("AutoSprint", "Automatically sprint while moving forward", Category.UTILITIES, false);
    }

    public static void onClientTick(MinecraftClient client) {
        AutoSprintModule module = ModuleManager.getAutoSprintModule();
        if (module == null || !module.isEnabled() || client == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null || player.input == null) {
            return;
        }

        boolean movingForward = player.input.getMovementInput().y > 0.0f;
        boolean hasFood = player.getHungerManager().getFoodLevel() > 6 || player.getAbilities().allowFlying;

        if (movingForward && !player.isSneaking() && hasFood && !player.horizontalCollision) {
            player.setSprinting(true);
        }
    }
}
