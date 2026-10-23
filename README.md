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
  runtime/bootstrap-v1/           # 管理会话环境的启动脚本
  runtime/tools/current/           # 当前一套开发工具、动态依赖、模板与证书
  runtime/python-packages/current/ # 当前科学库候选wheel与共享动态库
  sessions/<session-id>/
    workspace/                    # 用户/Agent任务文件
    home/                         # 当前会话HOME，含.venv及pip
    tmp/                          # 当前会话临时目录
    runs/<run-uuid>/              # main.py与output.txt；旧bootstrap.py保留
    terminals/<terminal-uuid>/     # PTY监督器、控制文件、终端日志
      commands/<command-uuid>/    # main.sh、output.bin与result.json
shared_prefs/config.xml           # 配置；API Key为Keystore AES-GCM密文
shared_prefs/git-accounts.xml     # 应用级 Git 主机/用户名及 Keystore 加密 Token
```

PRoot把当前会话的workspace、home、tmp映射到 `/workspace`、`/root`、`/tmp`。每次脚本以独立进程启动，工作目录为 `/workspace`，60秒超时、2MB输出上限。脚本与输出保留在runs中。文件工具拒绝绝对路径、`..`越界与符号链接越界。导入使用 Android SAF 用户选择器，单文件20MB；导出通过临时URI授权。

**同一APK内所有会话共享Android UID和底层内核。PRoot是兼容层，不是恶意脚本隔离边界。** 文件工具的路径校验不能限制任意Python原生代码；共享rootfs也可被脚本修改。此版本面向可信用户自己的任务，不应宣传为不可信代码沙箱。模型密钥不主动传入脚本环境，但同UID并非机密隔离。每会话使用home/.venv隔离Python site-packages（使用内置 ensurepip 离线引导 pip）；尚未为每会话复制/覆写rootfs。

档案内置于签名APK资源中，没有运行期修改工具。用户现阶段修改源文件后重建；尚无应用内档案编辑器。Android签名和安装权限保护包内容，PRoot不承担这个保护职责。

持续终端由应用级管理器和 specialUse 前台服务承载；命令运行期间持有 CPU 唤醒锁。系统/厂商仍可暂停或终止应用，首次使用请检查 Capri 的后台电池设置。应用进程终止后，下次启动将旧终端标为 interrupted，保留日志，不重新绑定旧 PID，也不自动恢复原 Shell。聊天和短 Python 任务仍跟随 ViewModel/应用进程。

## 持续终端

Agent 可使用 `terminal` 的 open/list/attach/run/read/input/cancel/close/clear_history/delete；「运行 → 持续终端」提供手动管理入口。每个终端保留 cwd、变量、export 和函数，聊天切换或模型调用结束不销毁它。PTY 合并 stdout/stderr；read 使用 UTF-8 安全的字节游标，后台子进程输出不承诺严格归属单条命令。

命令默认 60 秒，最长 24 小时；run 默认等 1 秒、最多 10 秒，之后通过 ID 查询；read 可等最多 20 秒，有新输出/结束状态即提前返回，取消读取不会停止命令。每条命令及终端日志各上限 2MB，全局最多 8 个活跃终端，每聊天 32 条终端记录、每终端 200 条命令记录。中断通过 PTY Ctrl+C；2 秒内不响应则关闭整个终端。关闭会结束关联 PRoot 执行器及其跟踪进程。终端使用提示符钩子报告退出码，根 Shell 的 PS1 为内部保留只读变量；当前没有 ANSI 全屏终端模拟器。

## Python 依赖管理（V2-02 首批）

python_run 与持续终端统一使用当前会话 `/root/.venv`；首次使用通过已有 ensurepip 离线补齐 pip，旧会话保留包和文件，不替换共享 rootfs。`python_env` 提供 status/list/install/uninstall/freeze/check，操作只针对当前会话；安装支持 PyPI 包名、extras 和版本条件，仅安装兼容 wheel，不构建源码包。高级 URL/本地包等可在终端手动处理，编译器等尚未集成。

包管理复用一个会话专属的持续终端，返回 terminal_id/command_id；用 terminal.read 等待、读取，terminal.cancel 中断；安装最长10分钟。重复提交时已有操作运行则返回 busy 和已有命令信息。managed 命令使用绝对 venv 解释器和 Python/pip 隔离参数，不依赖手动改动的 Shell PATH。运行页「诊断当前会话环境」提供解释器、venv、pip、包数量、TLS 及 Git/curl 版本与可用状态；完整依赖清单用工具 list/freeze。不会清空用户虚拟环境；若引导中断，只修复该次引导留下的 pip 自有文件，保留其他依赖。

## Git 与 HTTP/下载（V2-02 第二批）

持续终端和 Python 子进程可直接使用 `git`、`curl`。支持本地仓库/差异/提交及 HTTP(S) 克隆、拉取等 Git 命令，以及 HTTP 请求、重定向与文件下载；沿用 terminal 的等待/取消/超时/日志机制，不新增聊天工具或专门面板。建议下载用 `curl --fail --location --connect-timeout 15 --max-time 120 --output file.part URL`，成功后改名；原始 curl 的超时/覆盖/重试需由命令显式指定，并非受管下载队列。

项目与下载优先放当前 `/workspace`，Git 配置在当前 `/root/.gitconfig`，不同会话默认分开；认证账号由下述应用级设置共用，仍共享 Android UID。Git 不预设提交署名，禁止交互式口令提示以免后台挂起；SSH/SFTP 与搜索/网页提取工具尚未加入。共享工具层 bind 到 `/opt/capri/tools`，PATH、动态库、Git helper/模板和 CA 路径由启动器配置；保持 TLS 校验，不注入模型密钥。首次启动校验/解压 APK 内独立资源包，按完整 SHA-256 分目录、临时解包后原子提交；旧版本不自动回收，已有终端继续使用其版本，新的终端/短 Python 使用新版本。不会覆盖原有 rootfs、venv 或用户工作区。

工具版本/来源与摘要见 `docs/devtools-packages.json`，构建见 `tools/build_devtools.py`。Node、SSH、编译工具及基础环境升级修复仍待后续，V2-02 整体未完成。

## 全局 Git Token 账号

入口：运行 → Git 账号 · 全局。GitHub（github.com）只填 Token，保存新 Token 时经固定 https://api.github.com/user 自动识别用户名，无需手填；其他 HTTPS 主机（含可选端口）仍填写用户名/Token，供所有会话的 Git 认证共用；支持多主机、更新、删除及指定仓库读取测试。界面不回显已保存 Token，编辑留空保留原值且不联网识别。GitHub识别保持TLS校验、不跟随重定向，20秒总超时/可取消/16KiB响应上限；仅取login，不展示完整账号资料，失败不替换原凭据。旧账号直接兼容，无需重新填写。认证与提交署名分开，署名仍按仓库/会话设置；支持采用 HTTPS 用户名/Token 认证的 GitHub/GitLab/自建服务，不含浏览器 OAuth、SSH 或同主机多账号切换。

Token 用独立 Android Keystore AES-GCM 密钥加密，AAD绑定主机和用户名，存入应用私有偏好设置；默认不写入仓库URL、会话文件、命令/日志、进程参数或环境变量，也不作为模型工具结果返回。Git运行时重置文件凭据助手链，调用Python helper，经有长度/超时限制的抽象Unix socket向Android broker取值；broker验证对端真实UID、HTTPS、精确主机/端口及显式用户名。凭据仅通过内存/管道传给Git，不为curl注入认证。Git store/erase不改写用户维护的全局账号；更新/删除影响既有终端的后续认证，已发出的请求不撤销，本地删除不等于撤销平台Token。

自动跟随Git HTTP重定向关闭；仓库测试也不跟随重定向，使用最终HTTPS仓库地址，保留TLS校验。测试只证明读取接口可用，不证明推送权限。正常流程的隐藏和加密不构成同UID恶意代码隔离：任意本应用代码仍可能主动调用凭据助手或读取进程数据。

真机使用测试专用本地TLS私有Git服务验证跨会话克隆/推送、既有终端凭据更换/删除、失效提示、密文存储及会话记录无Token；测试证书/私钥仅在androidTest APK，不在主APK。真实托管平台需用户在界面配置其有效Token后使用，未用用户真实账号做远端推送验收。

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

## SSH / SFTP / SCP（V2-02 客户端批次）

内置 OpenSSH 10.0_p1-r10（Alpine维护包），通过已有 terminal 使用 ssh、sftp、scp、ssh-keygen、ssh-keyscan；不启动手机SSH服务器。Git也能使用SSH远程地址。运行 → SSH · 全局，可生成一个Ed25519设备密钥、查看指纹和复制公钥；既有密钥不覆盖，核验不通过时返回错误。私钥为应用私有目录内的标准OpenSSH无口令文件（0600、目录0700），没有单独Keystore加密或私钥导入/轮换界面，不向模型输出私钥。公钥需由用户添加至目标服务器/代码托管平台，HTTPS Git Token不用于SSH认证。

应用级 `files/runtime/ssh` 映射到 `/opt/capri/ssh`，所有聊天共用 config、known_hosts 和默认 id_ed25519；workspace中的任务和传输文件仍按聊天分开。连接别名/HostName/User/Port/可选其他IdentityFile通过终端编辑全局config，首版无主机配置表单。默认批处理、严格主机指纹检查、禁止代理转发、15秒连接超时和30秒×3次保活；文件/连接操作仍受terminal的任务期限、取消与日志机制约束，不自动重试远端副作用。

SSH包装脚本使用APK维护的配置，先Include用户全局config，再应用默认值；SFTP/SCP通过同一SSH包装器启动，解决其编译期 `/usr/bin/ssh` 与独立工具层路径不同的问题。未知/变化指纹默认拒绝；ssh-keyscan仅用于获取候选公钥，须与用户/服务器可信来源核对后才写known_hosts，不能直接据扫描结果自动信任。用户明确提供的命令行选项可覆盖默认值，原始终端不构成强制策略沙箱。没有受管的密码登录、加密私钥解锁/ssh-agent或完整ANSI远程终端模拟器。

OpenSSH需要Linux账号查询，因此PRoot增加 `-0` 兼容身份映射：客体getuid显示0，Android真实UID、内核权限和网络/文件访问约束保持不变。新增组件使用单一当前工具层，不覆盖rootfs、venv或用户文件；有持续终端/脚本时拒绝更新，空闲部署成功后清理旧SHA目录。

验收入口：`python3 tools/verify_ssh_device.py --adb <adb路径> --dependencies work/ssh-fixture-python`，开发机需Paramiko 4.0.0。测试生成专用密钥，仅连接开发机loopback SSH/SFTP服务；ADB reverse只作开发测试传输，结束后移除，实际连接用户服务器无需ADB或Root。已验证远端退出码、未知/变化指纹、错误密钥拒绝、SFTP/SCP往返、SSH Git及命令超时；测试不使用用户真实SSH私钥。

## 当前环境检查与修复

运行页显示基础Python、开发工具、科学库的状态/版本/逻辑占用，以及当前聊天的文件、依赖和缓存大小。检查环境对照APK可信清单核对文件SHA-256、链接和执行权限，再运行诊断；不检查额外手装文件或Python自动生成字节码。详细输出默认折叠。

只保留当前应用内置的一套工具，摘要是内容标识；不在线自动追踪上游最新，也不提供多版本选择。工具/科学库成功部署后清理旧SHA目录；.staging/.previous仅用于事务提交/恢复，成功后删除。已有rootfs不隐式覆盖；运行页确认后可修复所选组件，保留聊天、工作区、会话依赖、SSH/Git配置；基础修复会覆盖rootfs手工修改并可能影响依赖兼容性。活跃终端/脚本期间拒绝替换，不自动结束任务。

修改运行资源包后执行python3 tools/build_environment_inventory.py，重新生成.files.json/rootfs.sha256并更新docs/runtime-sha256.json。V2-02仍有缓存清理、会话依赖重置及兼容性评估待完成；完整版本档案见独立Capri-docs仓库。
