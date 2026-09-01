package org.edtp.sereniteapot.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.edtp.sereniteapot.command.scope.SereniteaPotCommandScope;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps one immutable pot scope around the complete command execution queue. */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @WrapMethod(method = "performCommand")
    private void sereniteapot$runInsideCommandScope(
            ParseResults<CommandSourceStack> command,
            String commandString,
            Operation<Void> original) {
        SereniteaPotCommandScope.enter(command.getContext().getSource());
        try {
            original.call(command, commandString);
        } finally {
            SereniteaPotCommandScope.exit();
        }
    }
}
