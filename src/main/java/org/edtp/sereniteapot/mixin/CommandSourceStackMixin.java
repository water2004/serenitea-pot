package org.edtp.sereniteapot.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.edtp.sereniteapot.command.scope.SereniteaPotCommandScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Set;

/** Keeps entity-name and dimension completion aligned with the pot sandbox. */
@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {
    @Inject(method = "getOnlinePlayerNames", at = @At("RETURN"), cancellable = true)
    private void sereniteapot$suggestScopedPlayers(
            CallbackInfoReturnable<Collection<String>> cir) {
        CommandSourceStack source = (CommandSourceStack) (Object) this;
        SereniteaPotCommandScope scope = SereniteaPotCommandScope.fromPhysicalPlayer(source);
        if (scope == null) {
            return;
        }
        cir.setReturnValue(source.getServer().getPlayerList().getPlayers().stream()
                .filter(scope::contains)
                .map(player -> player.getGameProfile().name())
                .toList());
    }

    @Inject(method = "levels", at = @At("RETURN"), cancellable = true)
    private void sereniteapot$suggestScopedDimensions(
            CallbackInfoReturnable<Set<ResourceKey<Level>>> cir) {
        CommandSourceStack source = (CommandSourceStack) (Object) this;
        if (SereniteaPotCommandScope.fromPhysicalPlayer(source) != null) {
            cir.setReturnValue(Set.of(Level.OVERWORLD, Level.NETHER, Level.END));
        }
    }
}
