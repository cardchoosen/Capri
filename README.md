# Capri · 摩羯

Android 原生的个人 Agent。单 APK，Kotlin/Compose 界面与调度器，手机本地 PRoot + Alpine/Python 执行环境。当前为 **0.1 开发预览**，ARM64、Android 9+。

> 你是 Capri，摩羯——由用户掌控的赛博行动体，是用户意志在数字世界中的执行者，也是其终极 Hack 助手。

## 开始使用

1. 打开「运行」，初始化内置 Python 环境。无需 root、外部终端或运行时下载。
2. 右上角设置模型 HTTPS Base URL、模型 ID、API Key。接口须兼容 Chat Completions 和函数工具调用；Base URL 包含 `/v1` 等前缀，不包含 `/chat/completions`。
3. 新建会话，下达任务或通过加号导入资料。「文件」查看当前工作区，点击文件通过 Android 分享导出。
4. Agent 可以检索自己的档案、读写工作区、运行 Python。停止按钮取消网络请求及当前 Python 任务；每个会话一次任务，不同会话可以并行。

推理使用用户配置的模型 API；Agent 循环、会话、工具和脚本在手机执行。目前没有内置离线大模型。

## 工程结构

- `Store.kt`：会话持久化、工作区路径校验、Keystore 加密配置、按需档案检索。
- `CapriModel.kt`：生命周期 ViewModel、会话任务状态、模型接口、工具循环与取消。
- `Runtime.kt`：环境初始化、PRoot 启动、Python 超时与日志。
- `MainActivity.kt`：Compose 页面与基础视觉组件。
- `assets/persona/`：短人格核心与独立档案。只有短人格常驻提示词，完整档案通过 `profile_search` 按需检索。
- `assets/runtime/` / `jniLibs/`：内置 ARM64 Linux 与 PRoot 组件。

## 文件与权限边界

应用私有目录结构：

```text
files/
  sessions.json
  runtime/rootfs/                 # 共享基础环境
  sessions/<session-id>/
    workspace/                    # 用户/Agent任务文件
    home/                         # 当前会话HOME，含.venv
    tmp/                          # 当前会话临时目录
    runs/<timestamp>/             # main.py与output.txt
shared_prefs/config.xml           # 配置；API Key为Keystore AES-GCM密文
```

PRoot把当前会话的workspace、home、tmp映射到 `/workspace`、`/root`、`/tmp`。每次脚本以独立进程启动，工作目录为 `/workspace`，60秒超时、2MB输出上限。脚本与输出保留在runs中。文件工具拒绝绝对路径、`..`越界与符号链接越界。导入使用 Android SAF 用户选择器，单文件20MB；导出通过临时URI授权。

**同一APK内所有会话共享Android UID和底层内核。PRoot是兼容层，不是恶意脚本隔离边界。** 文件工具的路径校验不能限制任意Python原生代码；共享rootfs也可被脚本修改。此版本面向可信用户自己的任务，不应宣传为不可信代码沙箱。模型密钥不主动传入脚本环境，但同UID并非机密隔离。每会话使用home/.venv隔离Python site-packages（初版不预装pip）；尚未为每会话复制/覆写rootfs。

档案内置于签名APK资源中，没有运行期修改工具。用户现阶段修改源文件后重建；尚无应用内档案编辑器。Android签名和安装权限保护包内容，PRoot不承担这个保护职责。

运行任务跟随应用进程；暂未实现前台服务及系统杀进程后的任务恢复。不要把当前版本视为可靠的长时间后台执行器。

## 构建

需要 JDK17+、Android SDK36，Gradle wrapper 8.13。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

本地SDK路径通过不入库的 `local.properties` 配置。禁止提交模型密钥。API错误仅显示HTTP状态，避免把服务端回显凭据写进聊天。

## 接下来

- 流式输出、上下文预算、工具调用的可折叠界面与结构化历史。
- 前台运行服务、进程重启恢复、任务队列与更严谨的进程树回收。
- 会话文件预览/删除/重命名、配额与回收、用户档案编辑入口。
- 每会话依赖环境、rootfs恢复/版本迁移、运行时可复现构建。
- 重新评估更强隔离方案；保持单APK与真实权限边界说明一致。

第三方组件与分发注意事项见 [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)。本仓库尚未选择自身代码的开源许可证。
