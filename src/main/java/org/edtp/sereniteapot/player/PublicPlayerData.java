package org.edtp.sereniteapot.player;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;

/** Adds a checked result to Vanilla's otherwise void, exception-swallowing save API. */
public final class PublicPlayerData {
    private static final ScopedValue<SaveResult> CURRENT_SAVE = ScopedValue.newInstance();

    private PublicPlayerData() { }

    public static void saveChecked(PlayerDataStorage storage, ServerPlayer player) {
        SaveResult result = new SaveResult(player);
        // Scoped to this call and thread: ordinary saves and concurrent world threads
        // cannot consume another transfer's result. Vanilla still owns the entire save.
        ScopedValue.where(CURRENT_SAVE, result).run(() -> storage.save(player));
        if (!result.success) {
            throw new PlayerStateStore.InvalidPlayerStateException(
                    "Failed to save public player data before entry for " + player.getUUID());
        }
    }

    public static void recordSaveResult(boolean success) {
        if (CURRENT_SAVE.isBound()) CURRENT_SAVE.get().success = success;
    }

    public static boolean isCheckingSave(Player player) {
        return CURRENT_SAVE.isBound() && CURRENT_SAVE.get().player == player;
    }

    private static final class SaveResult {
        private final Player player;
        private boolean success;

        private SaveResult(Player player) { this.player = player; }
    }
}
