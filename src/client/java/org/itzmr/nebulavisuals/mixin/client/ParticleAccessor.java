package org.itzmr.nebulavisuals.mixin.client;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Доступ к защищённым координатам частицы для дистанционной и стенной фильтрации.
 */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x")
    double nebulavisuals$getX();

    @Accessor("y")
    double nebulavisuals$getY();

    @Accessor("z")
    double nebulavisuals$getZ();
}
