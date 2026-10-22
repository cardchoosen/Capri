# Capri · 摩羯

Android 原生的个人 Agent。单 APK，Kotlin/Compose 界面与调度器，手机本地 PRoot + Alpine/Python 执行环境。当前为 **0.1 开发预览**，ARM64、Android 9+。

> 你是 Capri，摩羯——由用户掌控的赛博行动体，是用户意志在数字世界中的执行者，也是其终极 Hack 助手。

## 开始使用

1. 打开「运行」，初始化内置 Python 环境。无需 root、外部终端或运行时下载。
2. 右上角设置模型 HTTPS Base URL、模型 ID、API Key。接口须兼容 Chat Completions 和函数工具调用；Base URL 包含 `/v1` 等前缀，不包含 `/chat/completions`。
3. 新建会话，下达任务或通过加号导入资料。「文件」查看当前工作区，点击文件通过 Android 分享导出。
4. 基础身份问答由本地固定模板直接回答，不需要模型或密钥。其他任务中，Agent 可以检索自己的档案、读写工作区、运行 Python。停止按钮取消网络请求及当前 Python 任务；持续终端须单独中断或关闭。每个会话一次模型任务，不同会话可以并行。

推理使用用户配置的模型 API；Agent 循环、会话、工具和脚本在手机执行。目前没有内置离线大模型。

## 工程结构

- `Store.kt`：会话持久化、工作区路径校验、Keystore 加密配置、按需档案检索。
- `CapriModel.kt`：生命周期 ViewModel、会话任务状态、模型接口、工具循环与取消。
- `Runtime.kt`：环境初始化、PRoot 启动、Python 超时与日志。
- `MainActivity.kt`：Compose 页面与基础视觉组件。
- `terminal/`：持续 PTY Shell、终端/命令记录、前台服务、后台策略提示及按需终端面板。
- `assets/persona/`：短人格核心与独立档案。只有短人格常驻提示词，完整档案通过 `profile_search` 按需检索。
- `assets/runtime/` / `jniLibs/`：内置 ARM64 Linux 与 PRoot 组件。

## 文件与权限边界

应用私有目录结构：

```text
files/
  sessions.json
  terminals.json                  # 终端与命令元数据（AtomicFile）
  agent-recovery/<chat-id>.json    # 未完成任务检查点，完成后删除
  runtime/rootfs/                 # 共享基础环境
  sessions/<session-id>/
    workspace/                    # 用户/Agent任务文件
    home/                         # 当前会话HOME，含.venv
    tmp/                          # 当前会话临时目录
    runs/<run-uuid>/              # bootstrap.py、main.py与output.txt
    terminals/<terminal-uuid>/     # PTY监督器、控制文件、终端日志
      commands/<command-uuid>/    # main.sh、output.bin与result.json
shared_prefs/config.xml           # 配置；API Key为Keystore AES-GCM密文
```

PRoot把当前会话的workspace、home、tmp映射到 `/workspace`、`/root`、`/tmp`。每次脚本以独立进程启动，工作目录为 `/workspace`，60秒超时、2MB输出上限。脚本与输出保留在runs中。文件工具拒绝绝对路径、`..`越界与符号链接越界。导入使用 Android SAF 用户选择器，单文件20MB；导出通过临时URI授权。

**同一APK内所有会话共享Android UID和底层内核。PRoot是兼容层，不是恶意脚本隔离边界。** 文件工具的路径校验不能限制任意Python原生代码；共享rootfs也可被脚本修改。此版本面向可信用户自己的任务，不应宣传为不可信代码沙箱。模型密钥不主动传入脚本环境，但同UID并非机密隔离。每会话使用home/.venv隔离Python site-packages（初版不预装pip）；尚未为每会话复制/覆写rootfs。

档案内置于签名APK资源中，没有运行期修改工具。用户现阶段修改源文件后重建；尚无应用内档案编辑器。Android签名和安装权限保护包内容，PRoot不承担这个保护职责。

持续终端由应用级管理器和 specialUse 前台服务承载；命令运行期间持有 CPU 唤醒锁。系统/厂商仍可暂停或终止应用，首次使用请检查 Capri 的后台电池设置。应用进程终止后，下次启动将旧终端标为 interrupted，保留日志，不重新绑定旧 PID，也不自动恢复原 Shell。聊天和短 Python 任务仍跟随 ViewModel/应用进程。

## 持续终端

Agent 可使用 `terminal` 的 open/list/attach/run/read/input/cancel/close/clear_history/delete；「运行 → 持续终端」提供手动管理入口。每个终端保留 cwd、变量、export 和函数，聊天切换或模型调用结束不销毁它。PTY 合并 stdout/stderr；read 使用 UTF-8 安全的字节游标，后台子进程输出不承诺严格归属单条命令。

命令默认 60 秒，最长 24 小时；run 默认等 1 秒、最多 10 秒，之后通过 ID 查询；read 可等最多 20 秒，有新输出/结束状态即提前返回，取消读取不会停止命令。每条命令及终端日志各上限 2MB，全局最多 8 个活跃终端，每聊天 32 条终端记录、每终端 200 条命令记录。中断通过 PTY Ctrl+C；2 秒内不响应则关闭整个终端。关闭会结束关联 PRoot 执行器及其跟踪进程。终端使用提示符钩子报告退出码，根 Shell 的 PS1 为内部保留只读变量；当前没有 ANSI 全屏终端模拟器。

## 模型通信与任务续接

每条用户任务最多 50 次模型请求尝试（包括重试）。临时连接错误和 HTTP 408/429/500/502/503/504 最多额外重试 2 次，退避 0.5/1 秒；数字 Retry-After 大于 5 秒时交给用户稍后继续，避免过早重试。鉴权、证书、协议和响应格式错误不自动重试；请求可取消。关闭 OkHttp 隐式连接重试，连接/读/写/整次超时分别为 15/120/30/120 秒。

重试只重发模型请求，不重新执行工具。应用私有检查点保存已知 tool-call/result；网络失败、停止或预算耗尽后发送“继续”可接续。副作用未落盘的调用补为结果不确定，先核验资源、不直接重跑；不承诺通用 exactly-once。成功完成清理检查点，核心档案检索结果不落盘。已完成普通聊天仍使用最近 30 条文本历史，没有长期摘要。诊断只记录请求阶段、次数、耗时、错误类型/HTTP 状态与标准网络状态，不写密钥和请求正文。

## 构建

需要 JDK17+、Android SDK36，Gradle wrapper 8.13。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

本地SDK路径通过不入库的 `local.properties` 配置。禁止提交模型密钥。API错误仅显示HTTP状态，避免把服务端回显凭据写进聊天。

## 接下来

- 流式输出、上下文预算与结构化工具历史（单行折叠展示已实现）。
- 任务队列及用户主动重启中断任务；前台终端服务与进程死亡中断识别已实现。
- 会话文件预览/删除/重命名、配额与回收、用户档案编辑入口。
- 每会话依赖环境、rootfs恢复/版本迁移、运行时可复现构建。
- 重新评估更强隔离方案；保持单APK与真实权限边界说明一致。

第三方组件与分发注意事项见 [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)。本仓库尚未选择自身代码的开源许可证。

## 对话呈现

`ConversationPolicy.kt` 管理基础身份模板与工具可见性。明确、独立的身份问句直接返回固定文案；引用、代码任务与复合请求继续交给Agent。档案检索的工具名、参数、结果和前置播报不展示，内部结果仅供当前调用链使用，不新增到用户会话历史；旧版已保存的档案工具条目仍保留在数据文件中，但对话与运行页均隐藏。文件与Python工具记录照常展示。

新产生的最终回复渐进显示，过程说明与工具调用即时显示。完整内容先保存；Markdown只解析一次，再逐步揭示正文/代码/表格，最长约8秒。切换页面不重播，应用重启的历史直接完整显示，复制始终获得完整原文。此为展示层效果，模型接口仍为非流式。
