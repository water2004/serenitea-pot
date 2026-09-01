package org.edtp.sereniteapot.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.ExecuteCommand;
import org.edtp.sereniteapot.command.scope.SereniteaPotCommandScope;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/** Rejects selector-free /execute transformations that leave the captured pot. */
@Mixin(ExecuteCommand.class)
public abstract class CommandExecutionTaskMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void sereniteapot$validateFinalSource(
            ExecutionCommandSource<?> sender,
            ExecutionContext<?> context,
            Frame frame,
            CallbackInfo ci) {
        SereniteaPotCommandScope scope = SereniteaPotCommandScope.current();
        if (scope == null || !(sender instanceof CommandSourceStack source)) {
            return;
        }
        if (!scope.contains(source)) {
            source.sendFailure(component(
                    source,
                    message(MessageKey.COMMAND_SCOPE_ESCAPE_DENIED)
            ));
            ci.cancel();
        }
    }
}
