package org.edtp.sereniteapot.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Loads the exact WorldThreader compatibility mixins only when that optional mod is present. */
public final class SereniteaPotMixinPlugin implements IMixinConfigPlugin {
    private boolean worldThreaderLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        worldThreaderLoaded = FabricLoader.getInstance().isModLoaded("worldthreader");
        if (!worldThreaderLoaded) return;
        String version = FabricLoader.getInstance().getModContainer("worldthreader")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString();
        if (!"3.1.0".equals(version)) {
            throw new IllegalStateException(
                "Serenitea Pot supports WorldThreader 3.1.0 exactly, found " + version
            );
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".compat.worldthreader.")) return worldThreaderLoaded;
        return true;
    }

    @Override public String getRefMapperConfig() { return null; }
    // IMixinConfigPlugin defines these extension points even when no bytecode generation is needed.
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(
        String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo
    ) {}
    @Override public void postApply(
        String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo
    ) {}
}
