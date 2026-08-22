package org.edtp.sereniteapot.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.DifficultyCommand;
import net.minecraft.world.Difficulty;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.component;
import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/** Makes Vanilla's difficulty command world-local while its source is inside a pot. */
@Mixin(DifficultyCommand.class)
public abstract class DifficultyCommandMixin {
    @Inject(method = "setDifficulty", at = @At("HEAD"), cancellable = true)
    private static void sereniteapot$setPotDifficulty(
            CommandSourceStack source,
            Difficulty difficulty,
            CallbackInfoReturnable<Integer> cir) {
        var identity = SereniteaPotLevelKeys.identify(source.getLevel().dimension());
        if (identity == null) {
            return;
        }

        Runnable change = () -> {
            var record = SereniteaPotManager.record(identity.owner());
            if (record == null) {
                source.sendFailure(component(source, message(MessageKey.ACCESS_TARGET_MISSING)));
                return;
            }
            if (record.getActiveGeneration() != identity.generation()) {
                source.sendFailure(component(source, message(MessageKey.ACCESS_INACTIVE_GENERATION)));
                return;
            }
            if (record.getDifficulty() == difficulty) {
                source.sendFailure(Component.translatable(
                        "commands.difficulty.failure", difficulty.getDisplayName()));
                return;
            }
            SereniteaPotManager.setDifficulty(identity.owner(), difficulty);
            source.sendSuccess(() -> Component.translatable(
                    "commands.difficulty.success", difficulty.getDisplayName()), false);
        };
        if (source.getServer().isSameThread()) {
            change.run();
        } else {
            // WorldThreader may execute player commands on a dimension thread; catalog and
            // three-level property changes remain serialized on Minecraft's server thread.
            source.getServer().execute(change);
        }
        cir.setReturnValue(0);
    }
}
