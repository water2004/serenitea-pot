# Universe 647

Minecraft 26.2 的 Fabric 服务端-only 小宇宙模组。每名玩家拥有一组三维度小宇宙（主世界、下界、末地），使用服务器现有的模组、注册表与游戏逻辑，同时隔离世界存档和玩家创造状态。

## 核心规则

- `/universe create <radius>` 以玩家为中心复制一个半长为 `radius` 的闭区间立方体，实际边长为 `2 × radius + 1`。
- 从公共主世界、下界或末地创建时，只替换小宇宙中对应维度；另两个维度通过新代际复制保留。
- 新代际完整保存后才切换活动指针。复制在服务端主线程按每 tick 4 ms 分批执行，不并发调用模组世界代码。
- 小宇宙外部使用 void 生成器，并为每个已有维度设置世界边界。
- 进入任意小宇宙后使用该小宇宙独立的创造状态；离开后恢复公共世界的库存、末影箱、经验、生命、效果、能力和游戏模式。
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

## 性能模型

每名玩家的三个维度共享一个毫秒/秒 token bucket，全部小宇宙还共享一个全局池。预算不足、冻结、创建中或被隔离时会跳过整个 `ServerLevel.tick`。监控记录最近完整一秒的总耗时、平均/最大维度 tick、执行/跳过次数和有效 TPS。单次维度 tick 达到 200 ms 会自动隔离该小宇宙；任意模组的一次调用无法被安全地从中途强制终止。

## 构建与验证

需要 Java 25：

```powershell
.\gradlew.bat build
```

产物位于 `build/libs/`。项目包含 JUnit 测试，并已用 26.2 专用服务端验证模组加载、mixin 应用、命令注册、元数据保存和正常停服。
