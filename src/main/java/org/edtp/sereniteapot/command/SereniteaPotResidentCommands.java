package org.edtp.sereniteapot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.failure;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.playerName;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.profile;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.route;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.saved;
import static org.edtp.sereniteapot.command.SereniteaPotCommandSupport.success;

/** Owner-managed persistent invitations, independent of one-use entry requests. */
final class SereniteaPotResidentCommands {
    private SereniteaPotResidentCommands() {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        var residents = literal("resident");
        route(residents, literal("add"), argument("player", GameProfileArgument.gameProfile())
            .executes(SereniteaPotResidentCommands::add));
        // Resolve removal against the saved invitation list, even if the player is offline
        // or Vanilla has evicted their name from its cache. A UUID always works here.
        route(residents, literal("remove"), argument("player", StringArgumentType.word())
            .suggests(SereniteaPotResidentCommands::suggestResidents)
            .executes(SereniteaPotResidentCommands::remove));
        route(residents, literal("list").executes(SereniteaPotResidentCommands::list));
        route(root, residents);
    }

    private static int add(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        UUID owner = context.getSource().getPlayerOrException().getUUID();
        var record = SereniteaPotManager.record(owner);
        if (record == null || !record.exists()) return failure(context, MessageKey.ERROR_SELF_NO_POT);
        UUID visitor = profile(context, "player");
        if (visitor.equals(owner)) return failure(context, MessageKey.RESIDENT_SELF);
        if (!record.getResidents().add(visitor)) return failure(context, MessageKey.RESIDENT_ALREADY_ADDED);
        return saved(context, SereniteaPotManager.saveCatalog(), MessageKey.RESIDENT_ADDED,
            playerName(context.getSource().getServer(), visitor));
    }

    private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        UUID owner = context.getSource().getPlayerOrException().getUUID();
        var record = SereniteaPotManager.record(owner);
        if (record == null) return failure(context, MessageKey.ERROR_SELF_NO_POT);
        String name = StringArgumentType.getString(context, "player");
        var server = context.getSource().getServer();
        UUID visitor = record.getResidents().stream()
            .filter(id -> id.toString().equalsIgnoreCase(name) || playerName(server, id).equalsIgnoreCase(name))
            .findFirst().orElse(null);
        if (visitor == null) return failure(context, MessageKey.RESIDENT_NOT_FOUND);
        record.getResidents().remove(visitor);
        SereniteaPotLifecycleService.checkPresence(owner);
        return saved(context, SereniteaPotManager.saveCatalog(), MessageKey.RESIDENT_REMOVED, playerName(server, visitor));
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var record = SereniteaPotManager.record(context.getSource().getPlayerOrException().getUUID());
        if (record == null) return failure(context, MessageKey.ERROR_SELF_NO_POT);
        return record.getResidents().isEmpty()
            ? success(context, MessageKey.RESIDENT_EMPTY)
            : success(context, MessageKey.RESIDENT_LIST, record.getResidents().stream()
                .map(id -> playerName(context.getSource().getServer(), id)).collect(Collectors.joining(", ")));
    }

    private static CompletableFuture<Suggestions> suggestResidents(CommandContext<CommandSourceStack> context,
                                                                    SuggestionsBuilder builder) {
        var player = context.getSource().getPlayer();
        var record = player == null ? null : SereniteaPotManager.record(player.getUUID());
        return record == null ? builder.buildFuture() : SharedSuggestionProvider.suggest(
            record.getResidents().stream().map(id -> playerName(context.getSource().getServer(), id)), builder);
    }
}
