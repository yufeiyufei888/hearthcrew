# 真实互操作测试

`scripts/test-interop.ps1` 是 HearthCrew Mod 与本地 bridge 的受控互操作入口。它只使用本项目的 `.runtime/interop-<runId>/` 和 GameTest 目录，不读取或修改用户的 Minecraft 存档、全局 Codex 配置或其他 JVM。

运行前需要 Java 21、Node.js 24，以及 bridge 依赖已经安装。脚本会先执行 bridge TypeScript 构建和 `:mod:classes`，然后生成唯一 run ID，启动隐藏的 Node probe。probe 创建本地 pairing 文件后，脚本启动只启用 `hearthcrewinterop` 命名空间的 GameTestServer，并传入同一个 run ID。伙伴名称包含该 ID，因此持久测试世界中的旧实体不会被误认成当前 fixture。

```powershell
.\scripts\test-interop.ps1 -JavaHome "$env:APPDATA\.minecraft\runtime\java-runtime-delta"
```

需要只查看路径和参数时使用 `-PlanOnly`。计划输出只显示占位符，不显示 pairing token：

```powershell
.\scripts\test-interop.ps1 -PlanOnly
```

每次运行的证据保存在唯一目录中：

- `prepare.log`：bridge 构建和 Mod classes 构建输出；
- `node.stdout.log`、`node.stderr.log`：认证 TCP probe 输出；
- `gametest.stdout.log`、`gametest.stderr.log`：GameTestServer 输出；
- `report.json`：probe 的结构化结果；
- `runner-summary.json`：脚本记录的双方退出码、report 状态和失败原因，不含 pairing token；
- `pairing.json`：本地运行凭据，留在被忽略的运行目录中，不应提交或打印。

脚本只有在 Node exit 0、GameTestServer exit 0、且 `report.json.status` 为 `PASS` 时返回成功。超时或任一侧失败时保留全部证据，并且只终止脚本自己启动的进程树。

`RunId` 仅接受 1–48 个字母、数字或连字符；已存在的证据目录会被拒绝覆盖。测试 JVM 使用单次 Gradle 进程，便于按本次进程树清理。

2026-09-05 已实际运行该脚本：`runner-20260905-1358`，Node 与 GameTestServer 均 exit 0，probe 与 runner-summary 均 PASS。原始证据位于 `.runtime/interop-runner-20260905-1358/`。首次实跑前修正了 npm 构建工作目录、run ID 校验不一致和旧证据覆盖风险。

互测覆盖认证握手、4100 次只读 status 查询后的变更额度、MOVE 接受、同 ID 重试返回相同 receipt、实际移动位置和 `COMPLETED` journal、错误 body generation 拒绝、暂停/取消/恢复、事件确认、断线重连、reconcile，以及最终 WAIT。GameTest fixture 使用原版实体移动、碰撞和真实位置后置条件；测试用的强制区块 ticket、跑道和打开的 GameTest 边界墙只保证该 fixture 可运行，不能证明生产远程探险的 chunk-ticket 策略。

NeoForge GameTestServer 会以远快于现实 20 TPS 的速度推进 tick，没有内置的墙钟 pacing。这个脚本不在游戏线程 sleep，也不把 tick 数当作现实时间；互测中的短动作取消使用显式 pause/WAIT/cancel/resume 协议。该证据证明本地 Mod—bridge 传输与服务器后置条件闭环，不证明真实模型回合、Codex 桌面 MCP、普通生存通关、第二种子复验或长时间 soak。
