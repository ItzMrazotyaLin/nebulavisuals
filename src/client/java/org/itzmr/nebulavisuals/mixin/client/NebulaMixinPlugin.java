package org.itzmr.nebulavisuals.mixin.client;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Управляет условным применением миксинов для совместимости с Sodium / модами без Indigo.
 */
public class NebulaMixinPlugin implements IMixinConfigPlugin {
    private boolean hasIndigo;
    private boolean hasSodium;

    @Override
    public void onLoad(String mixinPackage) {
        FabricLoader loader = FabricLoader.getInstance();
        this.hasIndigo = loader.isModLoaded("fabric-renderer-indigo");
        this.hasSodium = loader.isModLoaded("sodium");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".TerrainRenderContextMixin")) {
            return this.hasIndigo && !this.hasSodium;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
