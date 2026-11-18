package org.itzmr.nebulavisuals.mixin.client;

import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Доступ к хранилищу построенных чанк-секций {@link BuiltChunkStorage} для их пересборки при изменении дистанции отсечения.
 */
@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {
    @Accessor("chunks")
    BuiltChunkStorage nebulavisuals$getChunks();
}
