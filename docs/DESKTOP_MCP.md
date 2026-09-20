# 桌面 MCP 开发接入

当前是 P1 开发接口。专用登录、实际工具隔离和单角色采木—制作—交付已有通过记录；三人完整陪玩、通关及发布验收尚未完成。

控制器提供 `status / command / control / events` 四个 MCP 工具，使用 MCP stdio。Mod 只连接本机带随机令牌的 TCP 端口，令牌留在本地 pairing 文件，不能加入仓库。MCP 不监听公网 HTTP。

## 开发构建

1. 安装本仓库固定版本 Java 21、Node 24 和 Codex CLI 0.153.3。
2. 在 `bridge/` 执行 `npm ci`、`npm run build`；Mod 使用 `scripts/build.ps1`。
3. 通过 `node bridge/dist/bridge/src/cli.js login --codex-command <Codex可执行文件绝对路径>` 完成官方设备登录。专用目录默认为 `%LOCALAPPDATA%/HearthCrew/codex-runtime`；不复制桌面凭据，不修改全局模型配置。
4. 桌面 MCP 入口为 `node <本仓库绝对路径>/bridge/dist/bridge/src/play-runtime.js --codex-command <Codex可执行文件绝对路径>`。标准输出只包含 MCP 消息；诊断在标准错误。
5. 使用独立游戏实例启动模组，在世界中 `/hearthcrew join 名称` 创建伙伴，然后通过 `status` 查询。默认 pairing 在 `%LOCALAPPDATA%/HearthCrew/runtime/pairing.json`。开发测试可以用 JVM 属性 `-Dhearthcrew.pairing=绝对路径` 覆盖。

## 前台启动脚本

推荐从仓库根目录运行 `scripts/start-controller.ps1`。脚本直接在当前进程前台运行，继承标准输入、输出和错误流，不打开额外窗口；`play-runtime` 的 stdout 专用于 MCP JSON-RPC。

```powershell
# 已完成 bridge 构建时
powershell.exe -NoProfile -File C:\path\to\hearthcrew\scripts\start-controller.ps1

# 首次启动或源码有变化时，把构建日志转到 stderr 后再启动
powershell.exe -NoProfile -File C:\path\to\hearthcrew\scripts\start-controller.ps1 -Build

# 使用显式的专用目录和 Codex CLI
powershell.exe -NoProfile -File C:\path\to\hearthcrew\scripts\start-controller.ps1 `
  -CodexCommand C:\path\to\codex.exe `
  -RuntimeRoot C:\HearthCrew\runtime `
  -WorkspaceRoot C:\HearthCrew\workspace
```

默认目录与 `play-runtime`、官方登录流程一致：state 为 `%LOCALAPPDATA%\HearthCrew\runtime`，Codex 专用 home 为同级的 `%LOCALAPPDATA%\HearthCrew\codex-runtime`，workspace 为同级的 `%LOCALAPPDATA%\HearthCrew\workspace`。传入 `-RuntimeRoot` 时它表示自定义 state 目录，脚本在其父目录使用 `codex-runtime`；`-WorkspaceRoot` 可单独覆盖 workspace。这些目录不能指向仓库根目录、全局 `CODEX_HOME` 或个人存档。

只读诊断使用 `scripts/diagnose.ps1`，输出 Node、Java、固定 Codex CLI 版本、构建文件、目录、controller lock、pairing 文件存在性和 hearthcrew JAR 的 SHA-256；不会读取或打印 pairing 内容、token、认证数据、存档或私人 journal。

```powershell
powershell.exe -NoProfile -File C:\path\to\hearthcrew\scripts\diagnose.ps1
```

需要重新登录时运行前台 device-auth 入口；它不会启动游戏或陪玩控制器：

```powershell
powershell.exe -NoProfile -File C:\path\to\hearthcrew\scripts\login.ps1

# 显式指定 Codex CLI 和专用目录
powershell.exe -NoProfile -File C:\path\to\hearthcrew\scripts\login.ps1 `
  -CodexCommand C:\path\to\codex.exe `
  -CodexHome C:\HearthCrew\codex-runtime `
  -WorkspaceRoot C:\HearthCrew\workspace
```

登录脚本中的 `CodexHome` 直接表示 Codex 专用 home；默认值是 `%LOCALAPPDATA%\HearthCrew\codex-runtime`，与 `play-runtime` 使用的目录相同。`RuntimeRoot` 仍作为兼容别名接受。设备码只显示在官方登录流程中，不写入本仓库。

诊断中的 `controllerLock.alive` 只表示对应 PID 当前是否存在，不代表它一定属于 HearthCrew；脚本不会终止任何 PID。安全停止方式是关闭桌面 MCP 会话让 stdin 发送 EOF，或在前台控制台按 Ctrl+C；`play-runtime` 会释放锁、关闭本地监听和 App Server 子进程。不要把删除 lock 文件或猜测 PID 当作停止操作。

示例配置（路径占位符须替换；此文件不会自动写入桌面全局配置）：

```toml
[mcp_servers.hearthcrew]
command = "powershell.exe"
args = ["-NoProfile", "-File", "C:/path/to/hearthcrew/scripts/start-controller.ps1", "-CodexCommand", "C:/path/to/codex.exe"]
startup_timeout_sec = 30
```

`command` 的输入为 `{ "message": "采一些木头，做成木板交给我", "botId": "status 返回的伙伴 UUID" }`；接受回执不是完成证据。`control` 支持 `pause`、`resume`、`stop`，省略 botId 操作当前世界的所有伙伴。急停取消旧动作，恢复不会重放取消的动作。

`events` 使用 `{ "after": 0, "limit": 50 }` 读取本地确认事件。角色记忆、命令与游戏事件是用户自己的运行数据，只在专用运行目录保存，不进入发布包。更换世界时先核对世界身份，不能把旧任务直接写进新世界。

依据：[MCP 生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[stdio 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[工具接口](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[Codex MCP 配置](https://developers.openai.com/codex/mcp)。目前实现的是所需协议子集，不声明支持远程传输、资源、提示模板或 MCP Tasks 扩展。
