# 固定执行后端构建

基础 `:mod:build` 与生产 Numen 后端构建分开。不要将实验后端或测试 JAR 安装到普通游戏。

准备 Java 21、Gradle 9.2.0，以及包含锁定提交 `947f0064f3374adc0341e61687215ae32ea9765a` 的 Numen 上游仓库。本地工作树不参与构建。当前准备脚本使用仓库旁的 `.tool-cache/gradle-9.2.0/bin/gradle.bat`；这是开发环境约定，不是自动下载入口。

```powershell
.\scripts\prepare-backend-lab.ps1 -UpstreamRepository ..\minecraft-numen -JavaHome '<Java21-JDK>' -Build
py -3 .\scripts\package-backend-dependency.py --output .artifacts\dependency-bundle
$env:JAVA_HOME = '<Java21-JDK>'
.\gradlew.bat -PnumenRelease -PbackendDependencyBundle=.artifacts/dependency-bundle :mod:jar --no-daemon --console=plain
```

打包器核对源归档及二进制哈希，移除受限媒体，并生成许可证、NOTICE、对应源码和复现说明。哈希不一致时停止；不要删除核验或替换成任意上游版本。

运行时需要生产 HearthCrew JAR 和 bundle 中的 **core** JAR。独立 API JAR 只用于编译，core 已包含 API。保留 bundle 的对应源码、许可证和 NOTICE；再分发须遵守各自条款。部署前检查 JAR 不含 GameTest、认证或运行日志。

控制器单独执行 `npm.cmd ci`、`npm.cmd run build`，通过项目专用配置完成登录及配对。使用固定 CLI `0.158.0-alpha.2.1` 的完整平台包，不修改桌面端全局配置。

本仓库默认只公开源码与文档，不提供已经通过长期生存验收的二进制发行版。