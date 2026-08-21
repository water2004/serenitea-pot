package org.edtp.sereniteapot.mixin.compat.worldthreader;

import net.fabricmc.loader.api.FabricLoader;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Prevents the optional mixin target from being resolved when Worldthreader is absent. */
public final class WorldThreaderMixinPlugin implements IMixinConfigPlugin {
    private boolean enabled;

    @Override
    public void onLoad(String mixinPackage) {
        enabled = FabricLoader.getInstance().isModLoaded("worldthreader");
        if (enabled) {
            SereniteaPotMod.LOGGER.info("Worldthreader 3.1.0 compatibility enabled");
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return enabled;
    }

    // IMixinConfigPlugin requires the remaining lifecycle hooks even though this
    // compatibility config needs no generated mixins or bytecode post-processing.
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(
        String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo
    ) {}
    @Override public void postApply(
        String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo
    ) {}
}
