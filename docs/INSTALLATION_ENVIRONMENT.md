# 安装环境档案

Capri 为当前安装维护一份跨会话共享的 Markdown：`files/installation-environment/environment.md`。运行页的「安装环境档案」可以阅读、筛选和分页查询，模型通过 `environment` 工具查询和更新。它与共同经历记忆分开，重置共同经历时保留档案，但清空暂存的执行凭证。

正文就是正式记录，不另存一份可独立修改的 JSON 真相库。每条记录包含标题、环境、结论、证据来源、验证时间、适用条件和有效规则。原始关键证据保存在 `evidence/`，正文只留简短结论；`history/` 保留最近20版文档。相同且仍有效的信息不反复重写文件或验证时间。损坏的文档不会被静默覆盖。

主题不限：硬件、驱动、固件、内核、接口、系统配置、软件与运行能力均可记录。来源分为结构化检测的「已验证／本次失败」和任意工具输出经模型整理的「观察」；观察不等于独立核验，成功运行一个命令也不自动证明模型对其输出的所有解释。

## 使用

```json
{"action":"lookup","query":"无线 驱动"}
{"action":"document","offset":0}
{"action":"inspect","requirements":{"commands":["git"],"imports":["PIL.Image"]}}
{"action":"refresh","requirements":{"commands":["git"]}}
```

`lookup` 返回相关记录及当前有效性，每页10条；`document` 返回同一份 Markdown，每页16000字符。记录的状态与有效性分开：过去验证失败不代表永久不支持，过去验证成功也不是当前权限保证。

新发现通过有真实来源的观察入档：

```json
{
  "action":"record",
  "key":"hardware.wifi.driver",
  "title":"无线驱动观察",
  "conclusion":"简短写明发现，保留仍未确定的部分",
  "source_receipt":"本次工具返回的environment_receipt",
  "excerpt":"该工具结果中实际出现的原文片段",
  "conditions":"仅适用于记录的接口、驱动与当前内核",
  "validity":"boot",
  "ttl_seconds":86400
}
```

来源环境由执行凭证确定，不能自行把 PRoot 发现标成宿主 Root 结论。凭证只在内存中短期保留，最多48条、30分钟；只有被引用的相关片段写入证据，不自动归档所有终端输出。模型收到新环境事实后应在本轮同步记录，已有框架记录则不重复写。凭据、个人经历、任务业务数据和目标扫描结果不进入此档案。

## 自动维护与减少重复检测

首次启动只记录可直接获取的设备、系统与架构基础，不全盘扫描或提前申请 Root。原生 Root 验证、常规设备检测和 `environment` 检测会自动补充记录；先前版本留下的 Root 授权记录可作为明确标注的历史观察，不冒充本次提权。每轮模型请求及检查点恢复只加载简短概览，详细条目按需查询，旧的档案查询工具结果会在继续请求前刷新为当前内容。

`inspect` 按单项检查复用证据。全部命中时不启动检测进程；部分命中时只把缺少的检查交给检测程序，已知版本和导入不重跑。失败记录可在短有效期内复用，但不会阻止实际 `prepare` 安装。命令、导入和本地编译组合按会话环境保存；系统包可按共享 Ubuntu 范围复用，不能把某个会话的虚拟环境包推断成所有会话都已安装。

有效性结合时间、环境指纹和事件：

- `stable`：系统／内核身份，适合较稳定信息。
- `boot`：还受本次启动身份约束，默认用于环境观察。
- `environment`：叠加检测程序版本、Ubuntu 包数据库、可执行目录、会话虚拟环境和项目清单等轻量元数据。命令与导入额外跟踪其实际文件变化。
- `volatile`：默认60秒，最多300秒，用于短期状态；当前网络元数据变化也会使其失效。网络仅记录曾查询及其易变性质，不把 SSID、MAC 和 IP 重复持久化进档案。

依赖准备、兼容包管理和会话依赖重建会使相关记录失效；强制刷新只失效指定检查。新检测可替换失效的旧结论。外部任意修改无法保证全部被立即观察到，指纹和有效期只是减少无效探测的依据，实际操作报错和新证据优先。

Root 实际调用的 UID 核验、权限检查和明确要求的 `verify` 命令不会被缓存跳过。普通 `terminal`、`python_run` 或 `root_exec` 也不会按相同命令文本跳过执行，避免错误省略任务操作或副作用；它们可提供环境观察的来源凭证。

## 验证

设备测试使用独立档案与临时会话，通过一个会递增计数的真实命令验证：第一次探测执行一次、再次查询不执行、部分命中不重跑已知命令、修改文件和强制刷新才再次执行。另验证跨会话隔离、重建对象后的持久化、任意主题观察的来源约束，以及档案查询结果刷新。测试不会清空用户对话，也不修改网络设置。

2026-10-28验证：130项单元测试、6项检测脚本测试及Pixel 8a上5项设备测试通过；已覆盖真实探针计数、跨会话与重载、查询结果刷新、Root和普通原生能力回归。运行页入口、Markdown正文与查询控件已在设备界面确认。日志见work/environment-verification/2026-10-28/。

## Batch reuse and model context · 2026-10-28

`android.root_inspect` groups independent host read-only queries into one managed root execution and automatically archives each bounded sample. A repeated query with applicable valid evidence starts no root process. Mixed batches run only missing items. Expired evidence, watched path changes, and `refresh=true` trigger rechecks. Arbitrary `root_exec` commands retain their original execution semantics.

At the start of a model run, a bounded selection of relevant valid facts is supplied alongside the archive overview. Session facts are restricted to the active session; host and shared Ubuntu evidence can be reused across sessions. Invalidated and expired evidence is excluded. Root history import is attempted once per profile object instead of reparsing Markdown on every UI status refresh. Execution guidance asks for independent checks together and concise intermediate narration while retaining final answers, errors, permission requests and decisions.

Verification after batching: 133 JVM tests passed. Two device suites passed6 and4 tests respectively, covering8 distinct tests including profile isolation, cache persistence, missing-only execution, metadata invalidation, explicit refresh, independent failures, root cancellation/cleanup and ordinary native queries. A real model conversation requested three independent host checks in one call: one managed root execution,744ms tool time and6948ms total. Repeating the request in another conversation reused all three records with zero root executions,34ms tool time and6219ms total. These are sample timings, not a controlled comparison with the earlier Wi-Fi investigation. The final debug APK was installed while preserving application data. Detailed local logs: `work/root-performance-review/2026-10-28/verification/`.
