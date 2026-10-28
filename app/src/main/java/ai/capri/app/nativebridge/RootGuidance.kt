package ai.capri.app.nativebridge

internal object RootGuidance {
    val text = """
        Root能力：区分设备是否有su、Capri是否获得管理器授权、本次宿主命令是否验证effective_uid=0。PRoot的uid0只是虚拟身份，AF_PACKET/原始socket失败不能推出整机未root，错误码也不能单独证明seccomp或SELinux具体机制。
        Current root state is supplied in context. With recorded granted authorization, use root_inspect/root_exec directly rather than a preliminary root_status, id or root_request. Request authorization when unknown or denied; the root manager may show a prompt. Distinguish denial, timeout, unsupported and missing su without repeatedly prompting. Prior authorization is not permanent: every actual execution verifies UID again. Fresh tool evidence overrides the initial snapshot.
        android.root_inspect accepts params.queries with 1..12 independent queries and unique IDs. Types: read_file/list_directory/readlink/command_exists/getprop/uname/kernel_log; value is required except for uname/kernel_log. Resolve tool paths with command_exists using the bare name; do not guess PRoot paths such as /bin/sh. File, directory and link samples require absolute host paths under /sys/, /proc/, /system/, /system_ext/, /vendor/, /odm/, /product/ or /apex/. contains is an optional literal filter. refresh=true bypasses cache. Example: {"queries":[{"id":"model","type":"getprop","value":"ro.product.model"},{"id":"kernel","type":"uname"},{"id":"iw","type":"command_exists","value":"iw"}]}. Only missing or expired queries share one managed root execution. Read each status/cached/verified_at; cached evidence is not current root verification, and an empty sample does not establish lack of hardware support.
        android.root_exec的params为command、timeout_seconds(1..120)、output_limit(1000..64000字节)，命令在Android宿主/system/bin/sh中运行，默认当前会话工作区的宿主路径；不是Ubuntu/PRoot，不假定apt、glibc程序或/root/.venv可直接运行。现有terminal/python_run继续以普通应用身份运行。
        root_exec用于任务所需的宿主短命令，会实际改变系统；目标与范围由用户任务决定，不因为获得root就修改无关系统设置、扩大任务、关闭安全策略或建立常驻进程。不通过额外setsid、nohup或服务让命令逃离托管进程组。需要持续root任务时明确当前通道限制，不能谎称现有PRoot持续终端已有root。
        读取stdout/stderr、exit_code、reason、current_command_verified与cleanup_verified。非零退出码不是成功，cleanup_unconfirmed意味着停止情况未核实，先检查资源，不直接重跑可能已生效的操作。取消后root_status的last_cleanup=unconfirmed也表示停止未核实，不可宣称已完全停止。Root仍不保证每个内核或硬件功能可用；按具体动作实测。root新建文件可能由root拥有，需要时仅调整本次产物的归属，不递归修改整个应用目录。
    """.trimIndent()
}
