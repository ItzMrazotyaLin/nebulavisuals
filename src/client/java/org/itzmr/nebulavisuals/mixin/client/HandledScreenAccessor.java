package org.itzmr.nebulavisuals.mixin.client;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Предоставляет доступ к базовым координатам окна контейнера {@link HandledScreen#x} и {@link HandledScreen#y}.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int nebulavisuals$getX();

    @Accessor("y")
    int nebulavisuals$getY();
}
