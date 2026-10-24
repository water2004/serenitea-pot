package org.edtp.sereniteapot.adapters;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Keeps optional-mod mixins inactive when their target mod is absent. */
public final class OptionalModMixinPlugin implements IMixinConfigPlugin {
    private boolean worldThreaderLoaded;
    private boolean tpcLoaded;
    private boolean carpetLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        worldThreaderLoaded = FabricLoader.getInstance().isModLoaded("worldthreader");
        tpcLoaded = FabricLoader.getInstance().isModLoaded("teleport_commands_fabric");
        carpetLoaded = FabricLoader.getInstance().isModLoaded("carpet");
        if (!worldThreaderLoaded) return;
        String version = FabricLoader.getInstance().getModContainer("worldthreader")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString();
        if (!"3.2.0".equals(version)) {
            throw new IllegalStateException(
                "Serenitea Pot supports WorldThreader 3.2.0 exactly, found " + version
            );
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("org.edtp.sereniteapot.adapters.worldthreader.mixin.")) return worldThreaderLoaded;
        if (mixinClassName.startsWith("org.edtp.sereniteapot.adapters.tpc.mixin.")) return tpcLoaded;
        if (targetClassName.startsWith("carpet.")) return carpetLoaded;
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
