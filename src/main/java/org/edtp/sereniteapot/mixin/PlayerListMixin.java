package org.edtp.sereniteapot.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.fallback;

/** 在 Vanilla 保存断线玩家之前完成 realm 切换。 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void sereniteapot$leavePotBeforeDisconnectSave(ServerPlayer player, CallbackInfo ci) {
        // PlayerList.remove 会在移除实体前立即保存 playerdata。必须先走正常离开路径，
        // 才能同时保存壶内位置，并让 Vanilla 写入公共维度、公共坐标和公共状态。
        if (SereniteaPotLevelKeys.identify(player.level().dimension()) != null) {
            SereniteaPotTravelService.Result result = SereniteaPotTravelService.leave(player);
            if (result instanceof SereniteaPotTravelService.Rejected rejected) {
                SereniteaPotMod.LOGGER.error(
                    "Failed to leave Serenitea Pot before saving disconnected player {}: {}",
                    player.getUUID(),
                    fallback(rejected.reason())
                );
            }
        }
        PlayerStateManager.onDisconnect(player);
    }
}
