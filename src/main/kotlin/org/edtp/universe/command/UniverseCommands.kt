package org.edtp.universe.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import org.edtp.universe.level.UniverseDeletionService
import org.edtp.universe.level.UniverseInvitationService
import org.edtp.universe.level.UniverseManager
import org.edtp.universe.level.UniverseTravelService
import org.edtp.universe.performance.UniverseScheduler
import org.edtp.universe.region.UniverseCreationService
import java.util.UUID

object UniverseCommands {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> register(dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val root = Commands.literal("universe")
            .executes(::statusSelf)
            .then(
                Commands.literal("create")
                    .then(
                        Commands.argument("radius", IntegerArgumentType.integer(1))
                            .executes(::create),
                    ),
            )
            .then(
                Commands.literal("enter")
                    .executes { enter(it, it.source.playerOrException.uuid) }
                    .then(Commands.argument("owner", GameProfileArgument.gameProfile()).executes(::enterTarget)),
            )
            .then(Commands.literal("leave").executes(::leave))
            .then(
                Commands.literal("request")
                    .then(Commands.argument("owner", GameProfileArgument.gameProfile()).executes(::request)),
            )
            .then(Commands.literal("requests").executes(::requests))
            .then(
                Commands.literal("approve")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(::approve)),
            )
            .then(
                Commands.literal("deny")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(::deny)),
            )
            .then(
                Commands.literal("delete")
                    .then(Commands.literal("confirm").executes(::deleteSelf)),
            )
            .then(adminCommands())
        dispatcher.register(root)
    }

    private fun adminCommands() = Commands.literal("admin")
        .requires { it.permissions().hasPermission(Permissions.COMMANDS_OWNER) }
        .then(adminToggle("enable", enabled = true))
        .then(adminToggle("disable", enabled = false))
        .then(adminFreeze("freeze", frozen = true))
        .then(adminFreeze("unfreeze", frozen = false))
        .then(adminStop("stop", stopped = true))
        .then(adminStop("start", stopped = false))
        .then(
            Commands.literal("max-radius")
                .then(
                    Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(
                            Commands.argument("radius", IntegerArgumentType.integer(1, 4096))
                                .executes(::adminMaxRadius),
                        ),
                ),
        )
        .then(
            Commands.literal("budget")
                .then(
                    Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(
                            Commands.argument("ms-per-second", DoubleArgumentType.doubleArg(0.0, 1000.0))
                                .executes(::adminBudget),
                        ),
                ),
        )
        .then(
            Commands.literal("global-budget")
                .then(
                    Commands.argument("ms-per-second", DoubleArgumentType.doubleArg(0.0, 5000.0))
                        .executes(::adminGlobalBudget),
                ),
        )
        .then(
            Commands.literal("status")
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(::adminStatus)),
        )
        .then(
            Commands.literal("perf")
                .executes(::adminPerfList)
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(::adminPerf)),
        )
        .then(
            Commands.literal("clear-quarantine")
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(::adminClearQuarantine)),
        )
        .then(
            Commands.literal("delete")
                .then(
                    Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.literal("confirm").executes(::adminDelete)),
                ),
        )

    private fun adminToggle(name: String, enabled: Boolean) = Commands.literal(name)
        .then(
            Commands.argument("player", GameProfileArgument.gameProfile()).executes { context ->
                val owner = profile(context, "player")
                val record = UniverseManager.getOrCreateRecord(owner)
                record.enabled = enabled
                if (!enabled) {
                    UniverseManager.unload(owner)
                }
                UniverseManager.saveCatalog()
                success(context, "已${if (enabled) "启用" else "禁用"} $owner 的小宇宙功能")
            },
        )

    private fun adminFreeze(name: String, frozen: Boolean) = Commands.literal(name)
        .then(
            Commands.argument("player", GameProfileArgument.gameProfile()).executes { context ->
                val owner = profile(context, "player")
                UniverseManager.getOrCreateRecord(owner).frozen = frozen
                UniverseManager.saveCatalog()
                success(context, "已${if (frozen) "冻结" else "解冻"} $owner 的小宇宙")
            },
        )

    private fun adminStop(name: String, stopped: Boolean) = Commands.literal(name)
        .then(
            Commands.argument("player", GameProfileArgument.gameProfile()).executes { context ->
                val owner = profile(context, "player")
                UniverseManager.getOrCreateRecord(owner).stopped = stopped
                if (stopped) {
                    UniverseManager.unload(owner)
                }
                UniverseManager.saveCatalog()
                val wording = if (stopped) "停止并卸载" else "解除停止（等待主人进入时加载）"
                success(context, "已$wording $owner 的小宇宙")
            },
        )

    private fun create(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        return when (val result = UniverseCreationService.request(player, IntegerArgumentType.getInteger(context, "radius"))) {
            is UniverseCreationService.RequestResult.Accepted -> success(
                context,
                "开始提取 ${result.volume} 个方块（代际 ${result.generation}），过程按 tick 分批执行",
            )
            is UniverseCreationService.RequestResult.Rejected -> failure(context, result.reason)
        }
    }

    private fun enterTarget(context: CommandContext<CommandSourceStack>): Int = enter(context, profile(context, "owner"))

    private fun enter(context: CommandContext<CommandSourceStack>, owner: UUID): Int =
        when (val result = UniverseTravelService.enter(context.source.playerOrException, owner)) {
            UniverseTravelService.Result.Success -> success(context, "已进入小宇宙")
            is UniverseTravelService.Result.Rejected -> failure(context, result.reason)
        }

    private fun leave(context: CommandContext<CommandSourceStack>): Int =
        when (val result = UniverseTravelService.leave(context.source.playerOrException)) {
            UniverseTravelService.Result.Success -> success(context, "已离开小宇宙")
            is UniverseTravelService.Result.Rejected -> failure(context, result.reason)
        }

    private fun request(context: CommandContext<CommandSourceStack>): Int =
        when (val result = UniverseInvitationService.request(context.source.playerOrException, profile(context, "owner"))) {
            UniverseInvitationService.Result.Accepted -> success(context, "申请已发送，60 秒后过期")
            is UniverseInvitationService.Result.Rejected -> failure(context, result.reason)
            is UniverseInvitationService.Result.Approved -> 0
        }

    private fun requests(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val requests = UniverseInvitationService.pending(player.uuid)
        if (requests.isEmpty()) {
            return success(context, "当前没有待处理申请")
        }
        return success(context, "待处理申请：${requests.joinToString()}")
    }

    private fun approve(context: CommandContext<CommandSourceStack>): Int {
        val owner = context.source.playerOrException
        return when (val result = UniverseInvitationService.approve(owner, profile(context, "player"))) {
            is UniverseInvitationService.Result.Approved -> {
                val visitor = context.source.server.playerList.getPlayer(result.visitor)
                    ?: return failure(context, "申请者已离线")
                when (val travel = UniverseTravelService.enter(visitor, owner.uuid)) {
                    UniverseTravelService.Result.Success -> success(context, "已批准申请并将玩家送入小宇宙")
                    is UniverseTravelService.Result.Rejected -> failure(context, travel.reason)
                }
            }
            UniverseInvitationService.Result.Accepted -> 0
            is UniverseInvitationService.Result.Rejected -> failure(context, result.reason)
        }
    }

    private fun deny(context: CommandContext<CommandSourceStack>): Int =
        when (val result = UniverseInvitationService.deny(context.source.playerOrException, profile(context, "player"))) {
            UniverseInvitationService.Result.Accepted -> success(context, "已拒绝申请")
            is UniverseInvitationService.Result.Rejected -> failure(context, result.reason)
            is UniverseInvitationService.Result.Approved -> 0
        }

    private fun deleteSelf(context: CommandContext<CommandSourceStack>): Int =
        delete(context, context.source.playerOrException.uuid)

    private fun adminDelete(context: CommandContext<CommandSourceStack>): Int =
        delete(context, profile(context, "player"))

    private fun delete(context: CommandContext<CommandSourceStack>, owner: UUID): Int =
        when (val result = UniverseDeletionService.archiveAndReset(context.source.server, owner)) {
            UniverseDeletionService.Result.Success -> success(context, "小宇宙已卸载并永久删除")
            is UniverseDeletionService.Result.Rejected -> failure(context, result.reason)
        }

    private fun adminMaxRadius(context: CommandContext<CommandSourceStack>): Int {
        val owner = profile(context, "player")
        val radius = IntegerArgumentType.getInteger(context, "radius")
        UniverseManager.getOrCreateRecord(owner).maxRadius = radius
        UniverseManager.saveCatalog()
        return success(context, "$owner 的最大半径已设为 $radius（边长 ${radius * 2 + 1}）")
    }

    private fun adminBudget(context: CommandContext<CommandSourceStack>): Int {
        val owner = profile(context, "player")
        val budget = DoubleArgumentType.getDouble(context, "ms-per-second")
        UniverseManager.getOrCreateRecord(owner).budgetMillisPerSecond = budget
        UniverseManager.saveCatalog()
        return success(context, "$owner 的三维度共享预算已设为 $budget ms/s")
    }

    private fun adminGlobalBudget(context: CommandContext<CommandSourceStack>): Int {
        val budget = DoubleArgumentType.getDouble(context, "ms-per-second")
        UniverseManager.catalog().globalBudgetMillisPerSecond = budget
        UniverseManager.saveCatalog()
        return success(context, "全部小宇宙的全局预算已设为 $budget ms/s")
    }

    private fun statusSelf(context: CommandContext<CommandSourceStack>): Int =
        status(context, context.source.playerOrException.uuid)

    private fun adminStatus(context: CommandContext<CommandSourceStack>): Int = status(context, profile(context, "player"))

    private fun status(context: CommandContext<CommandSourceStack>, owner: UUID): Int {
        val record = UniverseManager.record(owner) ?: return failure(context, "没有该玩家的小宇宙配置")
        val creation = UniverseCreationService.progress(owner)?.let { ", 创建进度=${"%.1f".format(it * 100)}%" } ?: ""
        return success(
            context,
            "owner=$owner, 存在=${record.exists}, 已加载=${UniverseManager.loaded(owner) != null}, " +
                "enabled=${record.enabled}, frozen=${record.frozen}, stopped=${record.stopped}, " +
                "quarantined=${record.quarantined}, maxRadius=${record.maxRadius}, " +
                "budget=${record.budgetMillisPerSecond}ms/s$creation",
        )
    }

    private fun adminPerf(context: CommandContext<CommandSourceStack>): Int {
        val owner = profile(context, "player")
        val snapshot = UniverseScheduler.snapshot(owner) ?: return failure(context, "没有该玩家的小宇宙配置")
        return success(context, formatPerformance(snapshot))
    }

    private fun adminPerfList(context: CommandContext<CommandSourceStack>): Int {
        val snapshots = UniverseScheduler.allSnapshots().take(10)
        if (snapshots.isEmpty()) {
            return success(context, "暂无小宇宙性能数据")
        }
        context.source.sendSuccess({ Component.literal("小宇宙性能排行（最近完整 1 秒窗口）：") }, false)
        for (snapshot in snapshots) {
            context.source.sendSuccess({ Component.literal(formatPerformance(snapshot)) }, false)
        }
        return snapshots.size
    }

    private fun adminClearQuarantine(context: CommandContext<CommandSourceStack>): Int {
        val owner = profile(context, "player")
        UniverseManager.getOrCreateRecord(owner).quarantined = false
        UniverseScheduler.reset(owner)
        UniverseManager.saveCatalog()
        return success(context, "已解除 $owner 的性能隔离；主人下次进入时加载")
    }

    private fun formatPerformance(snapshot: org.edtp.universe.performance.UniversePerformanceSnapshot): String =
        "${snapshot.owner}: cost=${"%.2f".format(snapshot.consumedMillisLastSecond)}/${"%.2f".format(snapshot.budgetMillisPerSecond)}ms/s, " +
            "avg=${"%.3f".format(snapshot.averageTickMillis)}ms, max=${"%.3f".format(snapshot.maximumTickMillis)}ms, " +
            "effectiveTPS=${"%.2f".format(snapshot.effectiveTps)}, run=${snapshot.executedTicks}, skip=${snapshot.skippedTicks}"

    private fun profile(context: CommandContext<CommandSourceStack>, argument: String): UUID =
        GameProfileArgument.getGameProfiles(context, argument).single().id()

    private fun success(context: CommandContext<CommandSourceStack>, message: String): Int {
        context.source.sendSuccess({ Component.literal(message) }, false)
        return 1
    }

    private fun failure(context: CommandContext<CommandSourceStack>, message: String): Int {
        context.source.sendFailure(Component.literal(message))
        return 0
    }
}
