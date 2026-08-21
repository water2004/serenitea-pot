package org.edtp.sereniteapot.mixin.accessor;

import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Vanilla's playerdata storage for the public-to-pot save boundary. */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("playerIo")
    PlayerDataStorage sereniteapot$getPlayerDataStorage();
}
