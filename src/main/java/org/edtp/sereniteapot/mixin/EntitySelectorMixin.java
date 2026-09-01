package org.edtp.sereniteapot.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.edtp.sereniteapot.command.scope.SereniteaPotCommandScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/** Applies the pot boundary at Vanilla's single entity-selector resolution point. */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
    @Inject(method = "getPredicate", at = @At("RETURN"), cancellable = true)
    private void sereniteapot$filterBeforeSortAndLimit(
            Vec3 position,
            AABB bounds,
            FeatureFlagSet enabledFeatures,
            CallbackInfoReturnable<Predicate<Entity>> cir) {
        SereniteaPotCommandScope scope = SereniteaPotCommandScope.current();
        if (scope != null) {
            cir.setReturnValue(cir.getReturnValue().and(scope::contains));
        }
    }

    @Inject(method = "findEntities", at = @At("RETURN"), cancellable = true)
    private void sereniteapot$filterExplicitEntity(
            CommandSourceStack sender,
            CallbackInfoReturnable<List<? extends Entity>> cir) {
        SereniteaPotCommandScope scope = SereniteaPotCommandScope.current();
        if (scope != null) {
            cir.setReturnValue(cir.getReturnValue().stream().filter(scope::contains).toList());
        }
    }

    @Inject(method = "findPlayers", at = @At("RETURN"), cancellable = true)
    private void sereniteapot$filterExplicitPlayer(
            CommandSourceStack sender,
            CallbackInfoReturnable<List<ServerPlayer>> cir) {
        SereniteaPotCommandScope scope = SereniteaPotCommandScope.current();
        if (scope != null) {
            cir.setReturnValue(cir.getReturnValue().stream().filter(scope::contains).toList());
        }
    }
}
