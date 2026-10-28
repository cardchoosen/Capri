# Android原生工具

模型只看到一个 `android` 工具，通过 `action` 选择原生动作，`params` 传递该动作参数。PRoot仍以普通应用身份运行；另提供可选的Android宿主su Root后端。普通原生查询不需要Root，不依赖ADB或Shizuku。Root命令由Capri应用本身请求管理器授权，不能把电脑ADB的Root授权当作应用授权。

```json
{"action":"wifi_info"}
{"action":"request_permissions","params":{"group":"location"}}
{"action":"apps_list","params":{"query":"浏览器"}}
```

## 已实现动作

| action | 能力 | 参数/约束 | 类型 |
| --- | --- | --- | --- |
| `capabilities` | 原生能力清单与权限状态 | 无 | read |
| `permissions` | 查看已授权权限 | 无 | read |
| `request_permissions` | 请求与任务相关的系统授权 | group: location/camera/bluetooth/contacts/calendar/notifications | permission |
| `root_status` | 检测su和查看本应用的Root验证记录 | 不弹授权；区分su线索、设备已验证Root、本应用上次授权与本次身份 | read |
| `root_request` | 请求并验证本应用Root授权 | 无参数；最多等待管理器30秒，只读验证id等；不绕过拒绝 | permission |
| `root_inspect` | Batch read-only host environment queries | queries:1..12 typed items; reuse valid archive evidence and execute only missing items together; refresh forces a new sample | root_read |
| `root_exec` | Execute a managed host root command | command; timeout_seconds=1..120 (default30); output_limit=1000..64000 bytes (default16000); request authorization only when not already granted | root_execute |
| `device_info` | 设备、系统、语言和运行信息 | 无 | read |
| `battery_info` | 电量、充电与省电状态 | 无 | read |
| `storage_info` | 应用存储空间与内存 | 无 | read |
| `display_info` | 屏幕尺寸、密度、亮度与锁屏状态 | 无 | read |
| `network_info` | 网络类型、IP、路由、DNS与VPN状态 | 无 | read |
| `wifi_info` | 当前Wi-Fi连接信息 | SSID/BSSID需要精确定位授权和位置开关；缺少时返回partial | read |
| `wifi_scan` | 读取最近的Wi-Fi扫描结果 | 需精确定位和位置开关；返回缓存结果和时间，不发起新扫描 | read |
| `bluetooth_info` | 蓝牙状态与已配对设备 | 需bluetooth授权；不会扫描或配对 | read |
| `location_current` | 获取当前定位 | 需location授权；timeout_ms:1000..20000，默认15000 | read |
| `sensors_list` | 列出设备传感器 | 无 | read |
| `sensor_read` | 读取一次传感器数据 | sensor_type:accelerometer/gyroscope/light/proximity/magnetic_field/pressure/gravity；timeout_ms:1000..10000 | read |
| `apps_list` | 列出可启动应用 | query:可选名称关键词；limit:1..100。不是所有已安装包 | read |
| `app_info` | 查询可见应用信息 | package_name:包名 | read |
| `launch_app` | 打开指定应用 | package_name:包名；仅返回启动请求已提交 | launch |
| `open_settings` | 打开系统设置页面 | page:wifi/internet/bluetooth/location/app/permissions/accessibility/notifications/battery/display/sound/storage/all | launch |
| `open_url` | 用浏览器打开链接 | url:http或https链接 | launch |
| `share_text` | 打开系统文本分享面板 | text:内容；title:可选。用户选择目标并发送，不等于已发送 | launch |
| `clipboard_read` | 读取剪贴板文本 | 仅前台可用；可能弹出系统提示 | read |
| `clipboard_write` | 写入剪贴板 | text:内容 | write |
| `notifications_info` | 查询Capri通知开关和通道 | 仅本应用，不读取其他应用通知 | read |
| `notification_post` | 发送一条本机通知 | title:标题；text:正文；id:可选1..9999999，默认1；需notifications授权 | write |
| `notification_cancel` | 撤销Capri自己的通知 | id:通知编号1..9999999 | write |
| `audio_info` | 查询媒体、铃声、闹钟音量 | 无 | read |
| `audio_set_volume` | 调整指定音量 | stream:media/ring/alarm/notification；level:0到对应最大值；不自动修改勿扰模式 | write |
| `vibrate` | 短时震动 | duration_ms:1..2000，默认100 | write |
| `torch_set` | 开关手电筒 | enabled:布尔；需camera授权 | write |
| `contacts_query` | 按关键词查找联系人电话 | query:必填关键词；limit:1..30；需contacts授权 | read |
| `calendar_events` | 查询日历事件 | start_ms/end_ms:毫秒时间戳，默认未来7天，最多31天；limit:1..50；需calendar授权 | read |
| `calendar_insert` | 打开系统日历的新建事件页面 | title:标题；start_ms/end_ms:起止时间；text:可选描述；用户保存后才创建 | launch |
| `alarm_create` | 打开系统闹钟设置 | hour:0..23；minute:0..59；title:可选；用户确认，非创建回执 | launch |
| `timer_create` | 打开系统倒计时设置 | duration_seconds:1..86400；title:可选；用户确认，非启动回执 | launch |

## 权限与生命周期

- 正常权限在Manifest声明。定位、相机、蓝牙、联系人、日历、通知授权通过系统弹窗按需申请；不会在首次启动时统一请求。
- `NativePermissions`将Agent请求转为Activity Result授权请求，等待结果后允许Agent继续。App必须在前台；多会话授权串行处理。任务取消不伪造“授权成功”，系统弹窗已出现时仍保留回调身份直到完成。
- Wi-Fi SSID/BSSID、扫描结果需要精确位置权限及系统位置开关。`wifi_scan`只读取缓存并报告缓存年龄，不假装刚完成扫描；不改变Wi-Fi连接或扫描设置。
- 定位等待最多20秒；传感器只采样一次，完成/超时/取消后注销监听。
- 打开App、设置、浏览器、分享、日历、闹钟或计时器，只返回启动请求已提交/需要用户继续操作，不当作保存或发送成功。
- 剪贴板只读取文字，不跟随URI。应用查询只枚举可启动且对Capri可见的应用，不声明QUERY_ALL_PACKAGES；存储查询报告容量，不获得全文件访问权。
- 通知只管理Capri自己的通知，不读取其他App通知。联系人按关键词查询并限量；日历查询限制时间窗口与条数。

## 结果协议

所有动作返回 `action/status/data`。status包括 `ok`、`partial`、`permission_required`、`permission_denied`、`foreground_required`、`setting_required`、`unsupported`、`unavailable`、`restricted`、`user_action_required`、`invalid_argument`、`timeout`、`error`、`busy`。缺少授权时返回建议的权限组；部分字段被隐藏不等于整个工具无法执行。

capabilities的available表示接口已接入，不代表所有权限、系统开关、前台状态或厂商条件均满足。注册表是封闭动作列表，拒绝未知action/params；不暴露任意反射、任意Intent或shell执行接口。

## 目前尚未接入

无障碍界面自动化、截屏/录屏、相机拍摄、麦克风录音、其他应用通知读取、SMS和任意系统设置写入。不把这些API描述成已实现；系统的某些能力也无法由普通应用获得。Android SDK不是有限工具清单，此版本覆盖上表动作，后续可以向同一个工具注册新模块。

## 测试

JVM测试验证工具Schema、动作唯一性、未知参数拒绝、参数类型/范围和日历时间边界。设备测试覆盖无需敏感权限的原生查询、Wi-Fi缺权限时的部分结果、未知动作以及前台要求。开发验证使用ADB安装和测试，不代表应用运行依赖ADB。

### 本次真机验收

在 Android 15 / ARM64 的 V2366HA 上：8项JVM测试通过；原生设备测试通过，覆盖11种只读查询及未知动作/参数和前台边界；聊天调用链成功完成wifi_info部分结果 → request_permissions系统定位弹窗 → 授权 → wifi_info返回SSID/BSSID/信号/频率/连接速率，并获取电量。未为了验收擅自查询联系人/日历内容或执行音量、手电筒等控制动作；这些接口已接入，但不声称所有动作均已逐项真机执行。

## 宿主Root后端 · 2026-10-27

root_status仅检查已知su路径与之前的应用验证记录，不为每轮查询弹授权。su存在是线索，`device_root=confirmed`表示此前实测过UID0；`app_authorization=granted`是上次验证记录，不是永不失效的权限。只有当前root_request/root_exec返回`current_command_verified=true`与`effective_uid=0`，才表示此次真实提权。授权被拒、未发现su、核验失败、超时分别返回，不把它们归结为“手机未root”。`device_info`及`capabilities`附带这些状态，模型每个新请求与恢复检查点也重新加载状态和使用规则。

```json
{"action":"root_status"}
{"action":"root_request"}
{"action":"root_exec","params":{"command":"id\npwd","timeout_seconds":5}}
```

Root命令直接运行在Android宿主`/system/bin/sh`中，默认工作目录是当前会话工作区的宿主路径。不是Ubuntu/PRoot，不会让原来的terminal/python_run自动提权；Ubuntu的glibc程序与Python虚拟环境不能假定在宿主可直接运行。新建文件可能属于root，需要时只处理相应产物的归属。运行页提供“Root · Android宿主”的状态与“请求并验证Root授权”按钮。

实现通过独立`setsid -w`进程组启动监督脚本。用户脚本单独写入应用私有临时文件，不插入su命令或可信监督脚本；核验真实UID0及进程身份后才放行。命令返回、超时、输出上限或协程取消时，通过本次进程组PID/启动时间核验后清理，并检查进程组是否消失，不按模型传入PID杀进程。若清理未确认返回cleanup_unconfirmed，不声称已经完全停止；已有副作用不回滚。取消后的最后清理状态也能通过root_status的last_cleanup回查。应用退出时监督进程自带看门狗，上限为命令超时加40秒授权／收尾余量，不建立永久Root服务。

仅支持受管短命令，不支持通过setsid/nohup/服务逃离管理来做持续Root任务。输出响应有字节上限及JSON转义长度预算，输出超量时停止命令并标记截断，避免通用工具传输截断了身份或清理字段。执行失败保留退出码与stderr。原生工具返回的权限和状态不保证任意内核功能可用，具体功能仍需实测。

设备验证只运行身份、工作目录、临时文件、非零退出码、超时、取消和输出限制测试；未进行ARP操作、抓取流量或修改网络设置。

本轮验证：121项单元测试、Pixel 8a上3项设备测试通过。Capri UID10314获得Magisk授权，实际宿主命令验证effective_uid=0；超时、取消、输出上限均验证受管进程组已清理，普通原生查询回归通过。日志与APK摘要见work/root-verification/2026-10-27/。

## Batched host environment inspection · 2026-10-28

With granted authorization already in context, call `root_inspect` or `root_exec` directly; a preliminary `root_status`, `id` or `root_request` is unnecessary. Every actual managed root execution still verifies UID and cleanup. `root_exec` always executes task commands; arbitrary scripts are never cached.

```json
{"action":"root_inspect","params":{"queries":[{"id":"model","type":"getprop","value":"ro.product.model"},{"id":"kernel","type":"uname"},{"id":"shell","type":"command_exists","value":"sh"}]}}
```

Queries support `read_file`, `list_directory`, `readlink`, `command_exists`, `getprop`, `uname`, and `kernel_log`. `value` is required except for `uname` and `kernel_log`. File samples are limited to system environment paths, without traversal or process/kernel memory interfaces. `command_exists` resolves a path without executing the program. `contains` is an optional literal sample filter; it accepts no shell expressions. Samples use `limit_bytes` 100..1800 (default1000); timeout is 1..40 seconds (default15).

`ttl_seconds=0` disables reuse. Defaults are 600 seconds for stable queries, 5 seconds for dynamic samples, and zero for kernel logs. Dynamic TTL is capped at30 seconds; other TTLs at86400. Dynamic queries always use volatile validity. Each result reports `status`, `cached`, and original `verified_at`; failures have at most30 seconds of reuse. `refresh=true` bypasses all requested cache entries. System/boot/environment fingerprints and watched path metadata invalidate evidence. Directory metadata does not guarantee every external tool modification is observed immediately; actual failures and fresh evidence take precedence.

Only cache misses share one managed `RootRuntime.execute` call. `root_invocations` counts these managed executions, not individual su subprocesses (the existing cleanup can use a second su). When everything is cached, no root process starts and `current_command_verified=false`. Cached facts cannot establish current authorization. Interpret per-item failures and incomplete frames independently; empty or filtered samples do not prove unsupported hardware. Permission denial and unconfirmed cleanup remain explicit.
