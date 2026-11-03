# Capri 内核独立专项

## 本轮范围与结构

本轮优先解决普通退后台/黑屏时已有任务执行、空闲后 Web 发起任务，以及内核必须由页面初始化的问题。执行内核抽为独立 JVM 模块 `agent-core`；Android 进程继续承载平台适配与会话控制器，不要求宿主 Root，不启动额外 TCP/LAN 服务。

```
手机 UI ──────────────┐
已鉴权 USB Web 桥 ────┼─> 进程级控制器 / Android 适配 ─> agent-core
JobScheduler 适配 ────┘               │
                              AgentTaskService
                          待命 / 活跃任务 / 主动关闭
```

`agent-core` 包含真实执行循环、工具调度、进展反馈、完成检查、模型请求预算与 30 秒重试、任务所有权、定时唤醒/周期计算。循环 Host 接口提供模型调用、上下文组装、工具执行、权限、事件、持久化和交付。保留原包名兼容现有调用；Android 不再维护这些实现的副本。

会话控制器暂保留 `CapriModel` 名称，但已不继承 AndroidViewModel，也不使用页面的 viewModelScope。`AgentRuntime` 持有进程唯一实例，页面、服务和 Web 共享同一份权威会话状态；Android 服务、原生工具、浏览器、上下文/Skill/MCP 装配和存储适配仍在 app 模块。进一步迁移这些适配不是本轮完成的范围。

## 生命周期与后台入口

- 用户打开 Capri 时启动可见的内核前台宿主。无任务时显示“内核待命”，保留后台 Web 接入；不再在每轮任务结束后关闭宿主。
- 已运行宿主直接接纳后台 Web 新任务，不依赖再次从后台启动服务。任务仍按会话互斥，运行前必须确认宿主可用；服务启动失败不能当作执行成功。
- 活跃任务持有有超时的部分 CPU 唤醒锁，只保持必要 CPU 执行、不点亮屏幕；任务仍在执行时定期续期，任务完成、失败、取消或服务销毁时释放。空闲待命不持有 CPU 唤醒锁。
- 通知分别提供“停止任务”和“关闭内核”。前者取消 Agent 任务但保留待命；后者持久记录用户关闭意图，阻止新任务和后台分析调度，取消现有任务并给检查点收尾留出有界时间，再退出宿主。Web 健康轮询不撤销这个选择，重新打开 Capri 后才能启用。
- 宿主允许系统在进程回收后重新创建待命服务，但不自动重放中断的工具或会话。继续沿用检查点核验和显式“继续”。设备重启后不增加常驻内核的开机启动；已有持久 JobScheduler 任务保留原调度语义。

前台服务与部分唤醒锁用于解决普通后台执行，不等于无限保活。强行停止、未首次解锁、深度省电、厂商限制、断电和真实断网的支持边界应分别验收；短时间熄屏通过不证明所有策略下长期在线。保持最多 30 秒网络恢复窗口，不引入永久等待。

## Web 冷启动与权限

电脑 USB 服务在读取手机桥接信息返回 not_ready 后，启动 `AgentTaskService`，不再拉起 MainActivity；宿主可自行初始化会话、工具适配与已鉴权 Unix socket。

ADB 启动入口只在 Debug 清单中导出，要求 `android.permission.DUMP`。Release 服务保持私有，没有 Debug provider 或 USB 管理命令。原有 Unix socket 对端 UID 检查、随机令牌、HTTP 同源限制、方法白名单、会话/历史版本校验和不重放未确认写操作的机制保持有效。主动关闭返回 kernel_stopped，Web 明确提示用户打开 Capri，不静默重新启动。

手机需要原生交互、权限确认或用户在场的工具仍可能要求返回前台；内核独立不扩大工具权限。

## 定时任务扩展点

`KernelTasks` 按归属键统一管理执行与取消；触发源不能改变权限或自动扩大执行范围。`KernelSchedule` 提供有界唤醒延时和完成时刻相对的周期计算，已接入现有可靠性调度。关闭内核会取消系统待调度入口并阻止再次派发，已有任务定义保留，重新打开 Capri 后再恢复调度。JobScheduler 负责系统唤醒，持久任务库继续负责 occurrence ID、领取、结果、暂停取消与未知效果，错过多个周期不突发补跑。

本轮不新增全能力无人值守 cron，也不改变已有后台分析任务的显式授权、只读范围和预算。以后新增定时触发器可复用内核执行接口与宿主，不需要重新依赖页面。

## 独立构建与检查

```
./gradlew -p agent-core test
python3 tools/verify.py --core-test ai.capri.core.KernelTest
```

独立入口不配置 Android 项目。模块只依赖 JVM、coroutines 和宿主提供的 org.json；不会引用 Activity、Compose、Android 服务或设备权限。集成检查继续覆盖执行/停止/预算/完成证据/纠正/恢复语义，不把模块能编译当作后台问题已解决。

端到端检查使用 `KernelHostDeviceTest` 与 `tools/kernel_host_smoke.py --serial <serial> --screen-off`：启动无页面宿主，真实 USB/Web 通道提交隔离会话任务，黑屏期间在 localhost TLS 模拟模型响应处等待，再确认一次推理完成、任务结束后 CPU 锁释放及宿主继续待命。不使用用户模型 Key，不改写用户聊天，不启用代理。该用例会重启应用且短暂熄屏，运行前要求所有用户任务空闲。

## 本轮验收记录（2026-11-03）

- `./gradlew -p agent-core test` 独立入口构建通过；集成版本的 9 个内核测试通过，覆盖无 Android 的执行循环、任务所有权、取消/延迟任务清理、周期计算及 30 秒重试。
- Android Debug APK、测试 APK 构建通过；执行循环、用户纠正、完成修复、上下文拒绝与输入消息的聚焦 JVM 检查通过，另有 14 个可靠性检查通过。只含本专项修改的独立源码副本同样构建和验证通过，未依赖其他未提交专项。
- 23 个 Web 边界/桥接检查、7 个验证入口检查通过。
- 单个隔离真机用例 `KernelHostDeviceTest#headlessStandbyAdmitsScreenOffWebTask` 通过（12.278 秒）。实际 USB/Web 提交任务，黑屏保持 7 秒；一次 localhost TLS 推理，任务结束后 CPU 锁释放并保持宿主待命。系统策略记录为 `procState=FGS`、`effective=NONE`。
- 实际 ADB/USB 验证宿主服务退出但进程仍在时的无页面恢复，以及主动关闭不被 Web 轮询撤销；测试后恢复 Capri 前台。
- 测试前后逐条比较原有会话，所有原会话完全一致，无额外测试会话残留在会话列表；手机保留数据更新。生成的构建、检查和数据保全记录位于 `work/kernel-independence/`，不提交用户原始聊天或生成物。

这些结果是当前 Pixel 8a 的受控验收，不是长时间熄屏、所有设备或所有省电策略下的普遍保证。未测试深度省电、首次解锁前和开机自启动；没有让真实用户任务继续跑、自动重试未知副作用，或消费真实模型额度来验收。

## 后续验证与迁移

- 记录实际设备、黑屏持续时间和网络策略，检查长时间/深度省电等尚未覆盖的边界。
- 分批将剩余上下文、配置、事件和工具装配从 Android 控制器整理成接口清晰的适配，不另建第二份会话存储。
- 独立进程、远程宿主、完整定时任务 UI 及无人值守工具执行如需实施，另行确认隔离、费用、权限和迁移方案。

参考：[前台服务启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[唤醒锁释放](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/release)。
