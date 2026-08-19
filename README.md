# Universe 647

Minecraft 26.2 的 Fabric 服务端-only 小宇宙模组。每名玩家拥有一组三维度小宇宙（主世界、下界、末地），使用服务器现有的模组、注册表与游戏逻辑，同时隔离世界存档和玩家创造状态。

## 核心规则

- `/universe create <radius>` 以玩家为中心复制一个半长为 `radius` 的闭区间立方体，实际边长为 `2 × radius + 1`。
- 从公共主世界、下界或末地创建时，只替换小宇宙中对应维度；另两个维度通过新代际复制保留。
- 新代际完整保存后才切换活动指针。提交后立即删除旧代际；启动时也会清理未提交或未删完的非活动代际，不保留隐式备份。
- 复制在服务端主线程按预算分片执行，不并发调用模组世界代码。复制任务和小宇宙 tick 消耗同一套玩家/全局性能预算。
- 小宇宙外部使用 void 生成器，并为每个已有维度设置世界边界。
- 进入任意小宇宙后使用该小宇宙独立的创造状态；离开后恢复公共世界的库存、末影箱、经验、生命、效果、重生点、上次死亡位置、能力和游戏模式。
- 只有真实主人本人在自己的小宇宙中时，整组三维度才加载。主人离开或下线后立即保存、卸载，并送出所有访客和管理员。
- Carpet 假玩家、地狱门加载器、区块票和机器不能维持小宇宙加载。
- 普通玩家提交 60 秒一次性申请；主人批准时申请者立即进入。OP4 无需申请，但同样要求主人在场。
- 删除是永久操作，不创建备份。

## 玩家命令

```text
/universe                         查看自己的状态
/universe create <radius>         提取当前维度区域，覆盖对应小宇宙维度
/universe enter                   进入自己的小宇宙
/universe leave                   离开当前小宇宙
/universe request <owner>         申请进入，60 秒过期
/universe requests                查看收到的待处理申请
/universe approve <player>        批准并立即送入申请者
/universe deny <player>           拒绝申请
/universe delete confirm          永久删除自己的小宇宙
```

OP4 也可用 `/universe enter <owner>` 进入主人当前已加载的小宇宙。

## OP4 管理命令

```text
/universe admin enable|disable <player>
/universe admin freeze|unfreeze <player>
/universe admin stop|start <player>
/universe admin max-radius <player> <radius>
/universe admin budget <player> <ms-per-second>
/universe admin global-budget <ms-per-second>
/universe admin status <player>
/universe admin perf [player]
/universe admin clear-quarantine <player>
/universe admin delete <player> confirm
```

`start` 只解除管理员停止状态，不会常驻加载；仍需真实主人进入才会加载。

`disable`、`freeze`、`stop` 和自动性能隔离都会先禁止新进入，然后在 tick 末尾通过同一个关闭事务送出所有成员、确认维度无人占用，最后保存并卸载。这里的 `freeze` 是“冻结存档并卸载”，不是让玩家停留在一个不 tick 的维度中。

## 性能模型

每名玩家的三个维度和区域复制任务共享一个毫秒/秒 token bucket，全部小宇宙还共享一个全局池。预算不足、冻结、创建中或被隔离时会跳过整个 `ServerLevel.tick`。区域复制按玩家轮转，方块、生物群系、计划刻和实体扫描都按区块推进，每次最多准备一个新区块，并受每 tick 4 ms 的额外总上限约束。`perf` 显示 `RUNNING`、`COPYING`、`THROTTLED`、`QUARANTINED` 等运行状态，并记录最近完整一秒的总耗时、其中复制耗时、平均/最大维度 tick、执行/跳过次数和有效 TPS。

单次维度 tick、同步区块加载或模组回调无法从中途安全终止；如果一次调用超过预算，实际耗时会形成 token 债务，后续 tick 被节流。单片达到 200 ms 会自动隔离并关闭该小宇宙。这能限制长期占用和连续卡顿，但不能保证任意第三方模组的一次失控调用绝不造成瞬时卡顿。

为避免病态实体堆积绕过复制预算，提取区域中任一区块若超过 256 个非玩家根实体，创建会安全失败并清理暂存代际；乘客树随根实体整体复制。

## 模组兼容边界

产物内嵌并强依赖 Arcade Dimensions 0.13.0-beta.6。小宇宙三维度由 `VanillaLikeLevelsBuilder` 组成；Arcade 的 Nether/End portal mixin 和 `VanillaDimensionMapper` 负责让玩家及实体只在同一名主人的主世界、下界、末地之间传送。不要从 jar 中移除或替换这组 Arcade 依赖。

区域提取会复制方块状态、方块实体完整 NBT/Data Components、生物群系、方块/流体计划刻以及非玩家实体（含乘客树）。因此把机器状态保存在方块实体中的常规模组通常可以直接工作；小宇宙仍使用服务端相同的模组、注册表和游戏逻辑。

区域提取不是任意模组数据的字节级裁剪器。POI、结构引用、维度级 SavedData、跨区块网络，以及 Fabric/第三方自定义 chunk、level 或 player attachments 没有通用且安全的区域语义，当前不会承诺复制或隔离。玩家隔离覆盖上文列出的原版状态；额外模组若把关键玩家数据放在自定义 attachment 中，需要该模组提供专用适配。

## 构建与验证

需要 Java 25：

```powershell
.\gradlew.bat build
```

产物位于 `build/libs/`。项目包含 JUnit 测试，并已用 26.2 专用服务端验证模组加载、mixin 应用、命令注册、元数据保存和正常停服。
