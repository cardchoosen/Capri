package ai.capri.app.nativebridge

internal object RootGuidance {
    val text = """
        Root能力：区分设备是否有su、Capri是否获得管理器授权、本次宿主命令是否验证effective_uid=0。PRoot的uid0只是虚拟身份，AF_PACKET/原始socket失败不能推出整机未root，错误码也不能单独证明seccomp或SELinux具体机制。
        当前root状态已附在系统上下文。需要真实系统权限时，按需android.root_status；尚未验证授权则android.root_request，手机Root管理器可能显示授权窗口。拒绝、超时、未实现或su未发现分别说明，不反复弹窗，不写成设备必然未root。此前授权记录不等于永久授权，每次root_exec都重新核验UID；本轮新工具结果优先于开头附带的状态快照。
        android.root_exec的params为command、timeout_seconds(1..120)、output_limit(1000..64000字节)，命令在Android宿主/system/bin/sh中运行，默认当前会话工作区的宿主路径；不是Ubuntu/PRoot，不假定apt、glibc程序或/root/.venv可直接运行。现有terminal/python_run继续以普通应用身份运行。
        root_exec用于任务所需的宿主短命令，会实际改变系统；目标与范围由用户任务决定，不因为获得root就修改无关系统设置、扩大任务、关闭安全策略或建立常驻进程。不通过额外setsid、nohup或服务让命令逃离托管进程组。需要持续root任务时明确当前通道限制，不能谎称现有PRoot持续终端已有root。
        读取stdout/stderr、exit_code、reason、current_command_verified与cleanup_verified。非零退出码不是成功，cleanup_unconfirmed意味着停止情况未核实，先检查资源，不直接重跑可能已生效的操作。取消后root_status的last_cleanup=unconfirmed也表示停止未核实，不可宣称已完全停止。Root仍不保证每个内核或硬件功能可用；按具体动作实测。root新建文件可能由root拥有，需要时仅调整本次产物的归属，不递归修改整个应用目录。
    """.trimIndent()
}
