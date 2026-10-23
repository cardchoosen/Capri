# Capri · 摩羯

Android 原生个人 Agent，单 APK、Kotlin/Compose、手机本地 PRoot + Ubuntu/Python；ARM64，Android 9+。当前 APK 为 0.1.0 开发预览，V2-01 已交付、V2-02 进行中，尚未正式发布 v2。

> 你是 Capri，摩羯——由用户掌控的赛博行动体，是用户意志在数字世界中的执行者，也是其终极 Hack 助手。

## 使用

1. 设置兼容 Chat Completions/函数工具调用的 HTTPS 模型地址、模型名和 API Key。
2. 创建会话即自动准备执行环境：首次下载官方 Ubuntu ARM64、安装基础 Python/venv、CA、Git、curl 和 SSH；后续共享复用。运行页显示状态、重试和默认折叠的诊断，无组件管理卡片。
3. 下达任务或通过加号导入文件。「文件」查看当前 workspace，产物可在聊天内显示/播放或分享导出。

Agent 循环和工具在手机运行；推理由用户配置的远端模型提供，无离线模型。不依赖 Root、Shizuku、外部终端或日常 ADB。首次准备及未缓存的依赖安装需要网络。

运行 → 诊断与维护提供会话 tmp/runs 清理、全局 pip/npm/apt 下载缓存清理、当前会话 .venv/托管 npm 重置。执行前展示范围与文件字节估算；有运行中的终端/脚本时拒绝，不自动中断。重置离线重建 Python，保留工作区（包括项目 node_modules）、其他 HOME 文件、聊天和全局凭据；失败可重试准备。

对话区域采用可关闭的预览子标签：仅有对话时不显示标签栏，对话固定且不可关闭；网页/文件预览可关闭，关闭代码预览不删除文件。文件页点击打开UTF-8文本/代码预览（最大256KB，支持复制与导出），右侧仍可直接导出。每聊天独立维护预览选择，切回保留阅读位置与输入草稿。

运行页的“浏览器 · 当前会话”打开浏览器入口子标签，使用系统WebView；每聊天独立Profile、最多5个逻辑标签，全局最多2个存活页面。browser提供status/tabs/open/observe/show/close，支持HTTPS、有界主frame DOM摘要；Agent只更新后台网页标签，open/observe/show不切换当前子标签。全尺寸WebView宿主位于不透明对话/文件层后方，非选中网页禁止触摸、键盘焦点及无障碍访问；应用仍须在前台；不支持多Profile的设备明确拒绝，不降级共享Cookie。标签元数据可恢复，但查询参数/fragment不持久化、表单与JS内存不恢复；不读取外部Chrome登录态。点击/填写/文件等属于后续03-B/C。

## 环境、文件和执行

```text
files/
  sessions.json                       # 聊天消息，AtomicFile
  terminals.json                      # 持续终端及命令记录
  agent-recovery/<chat>.json           # 未完成任务结构化检查点
  runtime/ubuntu/                     # 唯一共享系统、apt/dpkg数据库和系统包
  runtime/bootstrap-v1/               # 应用维护的启动/凭据/诊断脚本
  runtime/package-cache/              # pip/npm下载缓存
  runtime/ssh/                        # 全局配置、密钥、known_hosts、身份公共清单
  runtime/ubuntu-session-reset-v1.done # 一次性迁移完成标记
  sessions/<chat>/
    workspace/                        # /workspace，项目/文件/产物
    home/.venv/                       # /root/.venv，当前会话Python依赖
    home/.local/npm/                  # 当前会话npm-g前缀
    tmp/                              # /tmp
    runs/<run>/                       # 脚本/输出
    metrics/<turn>.jsonl               # 仅阶段/耗时/状态/token数，不含正文或代码
    terminals/<terminal>/             # PTY监督器及命令/输出/状态
shared_prefs/config.xml               # 模型配置，KeyStore AES-GCM加密Key
shared_prefs/git-accounts.xml         # 全局Git账号，加密Token
cache/ubuntu-setup.log                # 最近一次环境准备日志
```

共享 Ubuntu 24.04.5/glibc 替代旧 Alpine/工具层/科学包；APK不再包含语言、编译器和科学包大资源。官方镜像来源/大小/SHA-256见 `assets/runtime/ubuntu-source.json`。暂存解包后在固定路径安装，正常签名apt及真实venv/TLS/Git/SSH验证成功后标记就绪；失败可重试并恢复中断dpkg。PRoot --link2symlink适配Android硬链接限制，未启动Linux虚拟机。

`environment`按任务声明命令、Python包/版本/导入、系统包、头文件/链接库和编译器：inspect核对事实及项目清单；prepare正常apt/pip安装，from_project读取Python/npm/pnpm项目；verify执行明确argv的小样本，最多40秒。无固定绘图/语言场景枚举，不保证自动推断所有依赖。系统包全局安装一次，venv/node_modules/npm前缀按会话；缓存复用不等于依赖已安装。Node/npm/g++等按需安装，允许具备依赖后的源码构建。

短任务默认python_run：旧code调用保留，可一次带requirements准备并实际检查依赖后执行；code与steps二选一，steps按argv逐步执行，非零退出立即停止并保留中间文件。执行部分累计60秒、输出软上限2MB，与终端共用当前venv。任意code内被忽略的子进程退出码无法自动识别，编排应check=True，或使用steps独立核验。持续终端为POSIX /bin/sh（dash），已进入会话venv，无需source激活。模型调用使用扁平参数（旧params仍兼容），run默认短等5秒且提前结束立即返回。终端保留cwd/变量/函数，支持stdin、增量输出、cancel/close和日志清理；前台服务承载，进程死亡标记中断。系统安装要求环境空闲，存活终端即使空闲也返回busy，不自动停止任务。

本次用户明确授权：新Ubuntu验证后一次性清空旧聊天、工作文件/附件、会话依赖/日志、终端记录/recovery及旧组件；保留全局SSH、Git Token、模型配置、设置与核心档案。以后普通升级不重复清理。不要pm clear或卸载主应用来部署。

## 能力与边界

- 十个模型入口：profile_search、list_files、read_file、write_file、python_run、android、terminal、send_file、environment、browser。旧python_env/environment_check仅兼容分发。
- android包含34个封闭原生action，按需权限；不提供系统最高权限。目录见 [docs/ANDROID_TOOLS.md](docs/ANDROID_TOOLS.md)。
- 多聊天独立任务及文件，单聊天一个模型任务；最多50次模型请求尝试（含有界网络重试），未完成检查点可显式继续，不自动重放副作用。
- Markdown、代码复制、最终正文渐进显示、阅读锚点保护、单行工具轮播/折叠和按轮次二级折叠；本地图片/GIF与文件附件，单个20MB。
- 核心人格常驻，档案按需检索，内部profile_search隐藏，基础身份问句本地固定回答；档案只由用户修改。
- 应用级Git Token按HTTPS主机共享，加密保存；GitHub只填Token自动识别login，其他主机保留用户名，支持仓库读取测试。Token不传模型或命令URL，无OAuth。
- SSH全局生成设备密钥或导入命名无口令私钥，提供公共身份清单；严格指纹校验，私钥不发送模型。远端账号权限由服务器决定，无加密私钥解锁代理、删钥/轮换表单或手机SSH服务。

同一Android UID和内核，共享根可写。PRoot及venv目录组织**不是恶意代码沙箱**，不隔离同UID秘密或系统包；没有独立内核、资源硬配额、网络隔离。文件工具限制workspace路径，任意代码仍有应用权限。后台受Android/厂商限制。浏览器已交付03-A的页面打开/读取与独立Profile，点击/填写/下载仍待后续；未交付长期记忆、MCP/Skills/Plugins/Rules/工作流或正式互联网查询工具，见独立文档仓库Plans。

## 开发与验证

```sh
python3 tools/verify.py --profile local
python3 tools/verify.py --profile short-task --device --approve-capri-install
```

聚合构建、JVM/宿主检查、APK摘要复用部署和选定真机用例；JDK17+（自动识别Android Studio JBR），SDK36。runtime精简覆盖Ubuntu/实际依赖执行/跨会话/终端及清理保留边界；不是全部旧场景回归。调试接口仅debug APK，见 `tools/capri_debug.py`；不会读取模型密钥。`tools/analyze_agent_run.py`统计调用，--trace读取阶段计时。debug版本可用 `tools/capri_debug.py metrics --chat CHAT_ID` 导出该会话最新任务计时：模型请求/响应头/读取/解析/退避、工具与准备/执行/步骤、系统安装和Python操作。计时基于单调时钟，父子阶段不可相加；响应头不代表首token，包操作暂不拆分下载/安装，持续终端返回不代表后台任务结束。记录不包含模型正文、工具参数、代码、输出、URL或密钥。

主要实现：LinuxEnvironment/LinuxSetupService（安装及前台准备）、Runtime/session_bootstrap（执行和venv）、CapabilityTool/task_environment（通用依赖）、TerminalManager/terminal_supervisor（持续PTY）、CapriModel（编排）、Store（会话与文件）、GitCredentialBroker/SshEnvironment（全局凭据）、ConversationTimeline（显示）。

工程/特性/待办/变更在独立 `../Capri-docs` Git仓库维护。第三方来源见 [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)；`tools/legacy`及历史Alpine清单仅审计参考，已退出当前构建链。尚未为自身代码选择公开许可证。
