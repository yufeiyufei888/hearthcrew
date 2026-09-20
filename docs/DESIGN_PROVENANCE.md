# 设计来源

- 用户批准的 HearthCrew 计划：三名独立角色、身体单写、真实生存规则、附近辅助扫描、完整通关后发布。
- 用户提供的 Codexbot 复盘：仅提取动作争抢、终局不可靠、轮询风暴等行为失败要求。未从旧实现移植源码或资源。
- NeoForge 官方 1.21.1 文档、21.1.249 依赖源码及 Mojang 映射接口用于平台接入。Minecraft 与平台源码不进入发布包。
- Gradle Wrapper 由官方 Gradle 发行版生成。构建 DSL 参考 NeoForge 官方 MDK 文档，项目构建配置自行组织。
- OpenAI 官方 App Server/MCP 文档和本机 CLI 生成 schema 用于接入。

https://docs.neoforged.net/docs/1.21.1/gettingstarted/
https://docs.neoforged.net/docs/1.21.1/misc/gametest/
https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle
https://developers.openai.com/codex/app-server
https://developers.openai.com/codex/mcp

## 0.2.13 持续执行参考

只读对照 Numen f9b714d7e3c01e456763d4d00177170138ca7c78 的 MineCompanionTask 与 PathExecutor。借鉴任务持续维护候选、局部失败轮换、分段移动，独立实现有界真实配方准备、原版拾取归属和历史分流；未复制源码或迁移运行数据。具体链接和验证边界见 [0.2.13报告](RELEASE_0.2.13.md)。
