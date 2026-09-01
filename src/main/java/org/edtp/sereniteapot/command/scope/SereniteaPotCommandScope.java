package org.edtp.sereniteapot.command.scope;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.model.SereniteaPotDimension;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable security boundary captured when a player starts a command inside a pot.
 *
 * <p>The scope is stored for the lifetime of the whole command execution queue. This is
 * important because {@code /execute} creates new command sources: deriving the scope again
 * after {@code as}, {@code at}, or {@code in} would let the command lose its original
 * boundary.</p>
 */
public record SereniteaPotCommandScope(UUID owner, long generation) {
    private static final ThreadLocal<ExecutionState> EXECUTION = new ThreadLocal<>();

    public SereniteaPotCommandScope {
        Objects.requireNonNull(owner, "owner");
    }

    /** Opens one Commands#performCommand frame while preserving an enclosing frame's origin. */
    public static void enter(CommandSourceStack source) {
        ExecutionState state = EXECUTION.get();
        if (state == null) {
            EXECUTION.set(new ExecutionState(fromPhysicalPlayer(source), 1));
        } else {
            state.depth++;
        }
    }

    public static void exit() {
        ExecutionState state = EXECUTION.get();
        if (state == null) {
            throw new IllegalStateException("No Serenitea Pot command scope is active");
        }
        if (--state.depth == 0) {
            EXECUTION.remove();
        }
    }

    public static SereniteaPotCommandScope current() {
        ExecutionState state = EXECUTION.get();
        return state == null ? null : state.scope;
    }

    /** Returns the pot physically occupied by the source player, independent of /execute state. */
    public static SereniteaPotCommandScope fromPhysicalPlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return null;
        }
        SereniteaPotLevelKeys.Identity identity =
                SereniteaPotLevelKeys.identify(player.level().dimension());
        return identity == null
                ? null
                : new SereniteaPotCommandScope(identity.owner(), identity.generation());
    }

    public static boolean isPhysicalPlayerInsidePot(CommandSourceStack source) {
        return fromPhysicalPlayer(source) != null;
    }

    public static boolean isPhysicalOwner(CommandSourceStack source) {
        SereniteaPotCommandScope scope = fromPhysicalPlayer(source);
        ServerPlayer player = source.getPlayer();
        return scope != null && player != null && scope.owner.equals(player.getUUID());
    }

    public boolean contains(Entity entity) {
        return entity.level() instanceof ServerLevel level && contains(level.dimension());
    }

    public boolean contains(ResourceKey<Level> dimension) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(dimension);
        return identity != null
                && owner.equals(identity.owner())
                && generation == identity.generation();
    }

    /**
     * A final executable source must still point at this pot. This closes selector-free
     * /execute relations such as "on owner" and also rejects a transformed public level.
     */
    public boolean contains(CommandSourceStack source) {
        if (!contains(source.getLevel().dimension())) {
            return false;
        }
        Entity entity = source.getEntity();
        return entity == null || contains(entity);
    }

    public ResourceKey<Level> dimension(SereniteaPotDimension dimension) {
        return SereniteaPotLevelKeys.key(owner, generation, dimension);
    }

    private static final class ExecutionState {
        private final SereniteaPotCommandScope scope;
        private int depth;

        private ExecutionState(SereniteaPotCommandScope scope, int depth) {
            this.scope = scope;
            this.depth = depth;
        }
    }
}
