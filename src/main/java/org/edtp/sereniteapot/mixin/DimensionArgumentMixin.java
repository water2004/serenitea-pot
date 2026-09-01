package org.edtp.sereniteapot.mixin;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.edtp.sereniteapot.command.scope.SereniteaPotCommandScope;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/** Maps Vanilla dimension arguments onto the current pot's corresponding three dimensions. */
@Mixin(DimensionArgument.class)
public abstract class DimensionArgumentMixin {
    @Inject(method = "getDimension", at = @At("HEAD"), cancellable = true)
    private static void sereniteapot$resolveScopedDimension(
            CommandContext<CommandSourceStack> context,
            String name,
            CallbackInfoReturnable<ServerLevel> cir) throws CommandSyntaxException {
        SereniteaPotCommandScope scope = SereniteaPotCommandScope.current();
        if (scope == null) {
            return;
        }

        Identifier requestedId = context.getArgument(name, Identifier.class);
        ResourceKey<Level> requested = ResourceKey.create(Registries.DIMENSION, requestedId);
        SereniteaPotDimension vanilla = SereniteaPotDimension.fromVanillaLevel(requested);
        ResourceKey<Level> resolved = vanilla == null ? requested : scope.dimension(vanilla);
        if (!scope.contains(resolved)) {
            throw denied(context.getSource());
        }

        ServerLevel level = context.getSource().getServer().getLevel(resolved);
        if (level == null) {
            throw denied(context.getSource());
        }
        cir.setReturnValue(level);
    }

    private static CommandSyntaxException denied(CommandSourceStack source) {
        return new SimpleCommandExceptionType(component(
                source,
                message(MessageKey.COMMAND_SCOPE_DIMENSION_DENIED)
        )).create();
    }
}
