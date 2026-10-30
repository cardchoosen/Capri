# Capri 程序化调用与运行效率

2026-10-30，对应优化待办第 5 项，六个子项完成首版。新增 `program_run`，用于受控工具编排、只读批处理和会话内 Python 状态复用。原 `python_run`、终端和单项工具保留，Skills 内容优化继续由独立专项负责。

## 执行入口与数据流

| action | 行为 |
| --- | --- |
| execute | 在当前会话、当前历史修订的 Python 内核执行代码 |
| batch | 批量执行独立只读工具，不启动 Python 内核 |
| status | 查看内核状态和最近 8 份执行回执，不恢复或重跑 |
| read | 按 run_id、offset 分页读取本作用域回执，返回 next_offset/has_more |
| reset | 停止内核并丢弃变量与导入；不回滚文件或已发生的操作 |

脚本使用 `capri.call(name, arguments)`、`capri.batch([{name, arguments}])` 和 `capri.emit(value)`。只接受已知准确契约，不模糊匹配后直接执行。

RPC 白名单为工作区 read/list/search/project/write/edit/patch 与 web_search/web_read。batch 仅接受以下六种只读能力：read_file、list_files、search_files、project、web_search、web_read。控制工具、另一个 program、tool_invoke、MCP 激活、记忆、环境准备和终端均不能嵌套调用；需要这些能力时回到正常工具路径。

RPC 通过同一个 CapriModel.executeTool 进入既有注册表校验与执行分支，沿用会话路径、项目规则、文件租约、权限检查、取消和能力撤回语义。执行前以及排队后重新检查用户插话，未开始的动作不派发；已发生的操作不回滚。脚本中直接文件／子进程操作具有与 python_run 相同的 OS 权限，RPC 白名单不是 Python 安全沙箱。

## 中间数据与回执

工具结果返回执行侧，变量可持有数据；模型只收到显式 emit 的 JSON 值、受限 stdout 和执行摘要。子 RPC 不作为嵌套 assistant/tool 消息插入供应商历史，避免协议不合法或把全部中间结果再次送入模型。

每个子调用都有独立 ID，意图在派发前以 AtomicFile 保存，结果在完成后保存。回执还关联父工具调用 ID。代码、变量和完整命名空间不会被自动持久化；调用参数、工具结果和输出属于任务证据，保存在 `agent-programs/<chat>/revisions/<revision>/`，旧分支沿用 branches 隔离。可通过 status 找回丢失结果对应的 run_id，再 read 核验。

回执不是重放队列。进程重启后只剩可查询记录，未记录结果的意图视为效果未知，不重新执行旧程序。取消后的待定调用可能已产生副作用，必须核验。程序不提供事务回滚；写入及 apply_patch 保持原有原子性与部分失败边界。

批处理先验证整批目标与参数容器，出现不允许的目标时不会先执行前面的项。合法批次逐项返回结果，独立项允许继续，失败与省略明确标出。call 遇失败默认抛出含 result 的异常；即使代码捕获失败，该轮仍为 partial。新产生或已归档的截断 RPC 预览也使本轮标记 partial，不能把不完整材料误当作全文。完整原始数据沿用工具自身归档和回执分页，需要时缩小读取范围。

只有 execute/batch 的实际执行结果进入工作证据评估；status/read/reset 不作为“完成行动任务”的成功证据。脚本正常退出、工具返回成功或 emit 自述均不能证明任意用户目标，仍需实际产物与必要验证。

## 持久状态与失效

同一会话和历史修订可以复用变量与导入。首次启动沿用现有 venv 引导；新 venv 准备可能显著慢于热内核，不自动安装额外依赖。已知依赖在运行前用既有 environment 准备。

内核在最后一次执行完成约 60 秒后自动关闭，使用一次性延迟任务，不持续轮询。reset、历史修订变化、已知环境指纹变化、未处理错误、超时、取消、应用进程重启和会话删除都会丢弃状态。当前会话显式准备／修改依赖前也会关闭其内核。内核代际 ID、reused 和失效原因出现在结果中，丢失变量会报错，不伪装恢复成功。

环境指纹使用基础环境及 venv 元数据，不扫描或哈希整个项目。任意外部原地修改无法全部感知；本地模块变动应显式 reload/reset。文件删除或改写不会同步改写旧变量。共同经历重置关闭所有内核并删除回执；会话删除清理所属回执；普通 reset 保留文件与执行证据。

空闲内核仍由 RuntimeGate／OwnedProcesses 登记，因此会影响环境维护的准入。需要维护时 reset，或等待自动释放。当前实例最多 2 个内核，超额明确拒绝，不强行结束其他会话。

## 资源、并发与兼容边界

默认执行预算为 10 秒、16 次 RPC、8000 输出字符；上限 60 秒、32 次 RPC、16000 输出字符。排队、启动和 RPC 计入执行计时；首次冷环境可显式请求较大预算。输出、emitted 值、协议帧、单次 RPC 返回、每轮归档结果均有上限；归档结果总量超过 200 万字符会失败，已保存的前缀仍可核验。返回过大时提供预览和 run_id，不伪称完整。RPC 只允许一层调度，不支持递归控制工具。

Android Python 进程设置 384 MiB 硬地址空间上限和 CPU 软限制，主机墙钟超时负责销毁内核。它不是整个 Android 应用或所有子进程的聚合 RSS 上限，也不新增 UID 隔离。stdout 与 emit 共用输出预算；子进程应显式捕获输出，不支持直接写协议文件描述符／读取协议 stdin。后台线程、未回收子进程和长任务使用终端，不能依赖内核保活。

ToolScheduler 的资源信号量由普通模型工具批次和程序 RPC 共用：磁盘 4、网络 2、程序／短脚本执行槽 1；只读组内最多 4 个任务，写操作仍为顺序屏障。程序持有执行槽期间包括等待 RPC，属于保守的初版策略，并未实现 CPU 实际利用率调度。等待资源后再次检查控制状态，取消会释放许可。旧 python_run 保留“准备之外最多执行 60 秒”的兼容语义，终端仍有自己的生命周期，不混称所有入口具有同一总墙钟期限。

## 验证与测量

提交候选在独立 HEAD 副本中构建，排除未提交 Skills、REA 和人物专项。ProgramEngineTest、AgentHarnessTest、TurnControlTest、CapabilityDiscoveryTest、ToolSurfaceTest 共验证 60 个 JVM 用例，其中程序专项新增 15 项。最后针对归档预览的部分结果语义复核了 15 项专项用例。主机协议测试使用真实 Python 子进程，但模拟资源／子进程探测；Android 用真实限制验证。

一项原生用例 `ProgramDeviceTest#persistentKernelRpcIsolationLimitsAndReceipts` 通过真实 venv／PRoot、文件工具和 AtomicFile 路径，覆盖状态复用、筛选后输出、只读批次部分失败、384 MiB 上限、超时销毁、回执与跨会话隔离。首次发现 Android 不提供所依赖的 proc 子进程文件，现改用不消费退出状态的 waitid 检查。通过报告：`work/verify/20261030-173945-648065/report.json`。

固定真机样本首次执行含新 venv 准备 9523 ms，热复用执行 21 ms，供应商请求 0；整项夹具 11137 ms。它不是模型端到端提速或费用基准，不能据此承诺所有任务更快。新增工具定义也有上下文成本，适合有组合与复用价值的任务。程序工具耗时包含子 RPC 的等待，分析日志时应使用区间归因，不能将父子耗时直接相加。提交候选日志：`work/input-ui/program-commit-build.log`、`work/input-ui/program-final-build.log`。
