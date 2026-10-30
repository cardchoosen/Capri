# Capri Coding 能力

2026-10-30，对应优化待办第 6 项。六个子项完成首版开发与电脑侧验证：写后检查、Python LSP 试点、诊断差分、异步交付、文件快照和精确引用。按用户要求，本轮未连接、安装或操作手机；Android／PRoot 集成、手机启动耗时和内存表现仍待设备方便时补验。

## 写入、快照与快速检查

现有 workspace 文件工具继续保留 UTF-8／大小／路径限制、符号链接拒绝、项目 AGENTS.md、版本冲突检查和原子替换。CapriModel 为这些工具注入 CodingStore；旧的裸工具构造接口保留兼容。写入、精确编辑、patch 的实际新增／修改／删除，以及恢复操作，都在改变目标前保存 before/after 字节和哈希、权限、父调用 ID 与 pending 元数据。没有变更及 dry_run 不建立新版本。

保存快照后、实际提交前再次检查取消和源版本，避免备份期间发生的外部修改被覆盖。提交后标记 committed；如果文件已改变而快照收尾或分析排队失败，返回 applied 与警告，不伪装未发生写入，也不自动回滚。多文件 patch 继续逐项原子、整体非事务：失败时保留 applied_paths 和可核验快照。

JSON／XML 在写后直接做有界语法检查；JSON 使用严格语法而非宽松 JSONObject 解析，最多约 100 万字符、64 层及 50 ms 解析预算。XML 最多约 100 万字符，并检查 128 层深度及协作式时间预算；拒绝 DOCTYPE／外部实体，不加载网络资料。未支持、超限或检查器不可用都有独立状态，不当作通过。发现确定新增语法错误时，文件仍已写入，结果带 result_incomplete，不能作为成功验证。

快照独立保存于 `coding-history/<chat>/<revisions或branches>/<revision>`，不混同任务执行检查点。每个作用域最多 128 份／64 MiB，空间不足时先淘汰最老的 committed 记录；pending 不自动淘汰，必要时明确拒绝新写入以保护证据。列表返回最近 32 份及 total/truncated；旧引用可能已淘汰。会话删除、共同经历重置及旧会话迁移清除所属快照和分析缓存；不会自动写记忆或 Skill。

## 恢复与精确引用

| 工具 | 行为 |
| --- | --- |
| coding_restore | 传 snapshot_id 与 expected_sha256；当前字节必须同时匹配调用方版本及该快照的 after_sha256，缺失文件用 absent |
| coding_reference，kind=file | 返回路径、SHA、行范围与可直接使用的 read_file.read_args；后续改动会触发版本冲突 |
| coding_reference，kind=directory | 返回有界文件清单及 path/size/mtime 指纹，明确 has_more／scan_truncated；不是全文哈希 |
| coding_reference，kind=diff | 比较不可变快照字节，返回版本、路径和有界差异预览，过大时标记部分结果 |

恢复仍执行当前项目规则和路径检查，只处理这份文件的准确版本。后续用户编辑不匹配时拒绝；不提供 force、Git reset 或跨文件事务回滚。恢复已删除文件时可恢复记录的权限，已有文件的当前权限继续保留。快照字节读回会验证哈希，损坏不会被当成可恢复原文。

引用属于工具层结构化项目证据，可与附件并用；本轮没有添加手机／网页的 @ 文件选择器，也没有生成无法打开的伪链接。目录指纹仅代表已返回的元数据；diff 是历史预览，不保证工作区现在仍匹配，也不是 apply_patch 输入。

## Python 异步分析与 LSP 试点

Python 变更会排入诊断队列，文件写入不等待进程或服务冷启动。coding_diagnostics.check 可对选定现有文件显式检查；JSON/XML 立即返回，Python 入队。可传匹配当前版本的 baseline_snapshot_id；没有基线时不修改源文件或凭空制造旧版本。

默认只用 Python ast.parse，不执行被检查代码。coding_diagnostics.configure(python_lsp=true) 为当前会话修订开启 pylsp／Pyflakes 试点；不自动安装。需要时由现有 environment 显式准备 `python-lsp-server[pyflakes]`，缺少服务或插件会返回 unavailable。当前侧重未定义名称等静态发现，不是所有语言、完整跨项目类型检查或编译测试替代。

探针使用 LSP Content-Length／JSON-RPC、initialize、initialized、配置、didOpen 和 publishDiagnostics。基线与修改后分别启动新服务，避免无版本号的迟到通知串到另一个版本；结果校验 URI，LSP UTF-16 坐标转换为工作区 1-based Unicode 列。除只读配置回复外，服务器请求一律拒绝，不接受工作区修改或执行命令。默认配置禁用非本轮所需 lint／项目配置源，不运行项目脚本或构建钩子。接口依据 [LSP 规范](https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.18/specification.md)和 [python-lsp-server 官方项目](https://github.com/python-lsp/python-lsp-server)。

分析输入、输出与 PID 文件位于 coding-jobs 下的内部临时目录，完成／取消后清理，遗留目录随会话删除或共同经历重置清除，不把旧源代码副本留在保留的任务 runs 中。

## 差分、过期与交付

诊断按 source/code/severity/message/行内容锚点做多重集合匹配，区分 new、existing_count、resolved_count。相同问题随行号移动仍可识别为既有问题；新增的同类重复实例会增加计数。没有可靠基线、服务失败或服务截断时使用 unclassified，保留原因；不把缺失结果当作“没有问题”。这是有界匹配策略，不是通用语义等价证明。

每项任务记录路径、前后 SHA、环境指纹、父操作与 job_id。发布和查询时核对当前字节／环境，变化后标为 stale；进程重启后未完成任务标为 interrupted，不自动重跑。新修改可以取消同路径旧任务；历史编辑、依赖变更、会话删除、共同经历重置和应用释放都接入取消或失效。

后台队列最多 16 项、同一服务实例串行分析，单项探针最多 30 秒，单份 before/after 总量不超过约 200 万字节。Python 进程／子服务继承 512 MiB 地址空间限制，输出与 LSP 帧另有限额；不是整个应用的聚合内存沙箱。它使用独立分析队列，不持有前台 program_run／python_run 的执行许可，避免慢诊断占住前台脚本槽。

经文件工具修改 Python／pyi 源码时，会通知热内核失效：正在运行的单元先结束，再丢弃缓存；下一次运行不沿用旧模块。当前单元内部仍须按 Python 语义显式 reload，绕过文件工具的外部变更也不能保证自动感知。此路径有独立的单元内写入与不重放回归。

结果通过写入返回的 job_id、下一轮有界上下文清单、当前修订的界面状态和 coding_diagnostics.result 获取。result 可等待最多 5 秒，不推荐零等待轮询；超时、排队满、错误和不支持都不是检查通过。不会为异步结果自动开启付费模型轮次；任务已结束时，记录可查询，不承诺自动生成另一段模型答复。未支持格式仍需现有执行工具验证。

## 验证与实际限制

电脑侧独立 HEAD 提交副本执行 CodingIntelligenceTest、CodingToolsTest、WorkspaceFileToolsTest、ToolSurfaceTest 和 CapabilityDiscoveryTest，并加上 ProgramEngineTest，共 66 项 JVM 检查通过。新增 14 项专项检查覆盖严格 JSON／安全 XML、真实 LSP 夹具差分、无变化重复写、快照原文与回滚冲突、增删 patch、dry_run、符号链接／项目规则、备份期间外部编辑、缓存预算、字节完整性、范围／diff 引用、异步过期、手动无副作用检查及不可用状态。

另有 4 项 Python 主机测试，使用本工作区独立 venv 中的真实 python-lsp-server 1.15.0／Pyflakes 3.2.0，并用真实管道的模拟服务验证初始化期请求、拒绝 applyEdit、URI 与 UTF-16 转换；同时验证 AST 不执行源代码、缺服务不报通过。真实样本从一个旧未定义名称变为移行后的旧问题和一个新问题，差分只报告 new_missing 为新增。原始记录：`tools/fixtures/coding-lsp-findings.json`。

16,012 字节固定主机样本中，未变化重复写约 3.19 ms，带快照的变更写约 29.87 ms。两个操作不同，且为单次主机样本，不是旧实现对照或手机耗时基准。

构建／JVM 日志：`work/input-ui/coding-commit-build.log`、`work/input-ui/coding-final-guard.log`；主机 LSP 测试：`work/input-ui/coding-probe-tests.log`。这不等同于 Android 端实测：本轮遵照手机停用要求没有运行任何真机检查，也没有安装 APK。手机端路径挂载、解析器、取消、LSP 冷启动／内存与界面状态仍需补验；当前测量不证明整轮模型提速或总体修复成功率。
