package org.edtp.sereniteapot.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import org.edtp.sereniteapot.command.scope.SereniteaPotCommandScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Grants only selector syntax; command authorization remains controlled by the command policy. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerCommandSourceMixin {
    private static final PermissionSet SERENITEA_POT_SELECTOR_PERMISSION =
            permission -> permission == Permissions.COMMANDS_ENTITY_SELECTORS;

    @Inject(method = "createCommandSourceStack", at = @At("RETURN"), cancellable = true)
    private void sereniteapot$grantOwnerSelectorSyntax(
            CallbackInfoReturnable<CommandSourceStack> cir) {
        CommandSourceStack source = cir.getReturnValue();
        if (SereniteaPotCommandScope.isPhysicalOwner(source)) {
            cir.setReturnValue(source.withMaximumPermission(SERENITEA_POT_SELECTOR_PERMISSION));
        }
    }
}
