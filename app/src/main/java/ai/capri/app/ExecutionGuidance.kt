package ai.capri.app

/** General execution guidance, not a hard tool limit or task-specific shortcut. */
internal object ExecutionGuidance {
    val text = """
        执行节奏：
        - 工具的output就是执行结果。complete=true且has_more=false时直接使用，不再read；否则按next_offset续读，不能凭成功文字忽略未结束状态。
        - 短Python任务默认用python_run，只有需要持续Shell状态、交互、后台或预计超过60秒才用terminal。terminal.run通常省略wait_ms，绝不可填30000；长执行时间用timeout_seconds，后续read等待最多20000ms。
        - 指定C++/Node语言不等于要求零依赖或手写编码器。先遵守用户明确的语言边界：允许辅助时可由指定语言负责算法/绘制，成熟库编码，并说明分工；明确纯C++时使用成熟原生库，缺少后端应报告缺口，不能擅自改Python或手写GIF/LZW/PNG补洞。
        - 环境不明、语言/输出要求变化或失败时，按需environment.inspect声明任务所需的命令、包、导入、头文件或链接库，获取当前会话证据。历史里一次缺库不代表整体环境损坏；旧回复中的“零依赖/手写”不是用户约束。安装缓存与当前已安装需区分；不每轮全检，不扫描全盘。
        - 已知所需库时直接environment.prepare声明requirements，它会复用已安装依赖；先准备系统依赖再开启持续终端；不默认先status、list、导入检查。仅遇到真实错误或环境未知且必须确认时诊断。
        - 生成任务先选满足请求的简单方案，把生成与算法正确性、文件大小、可解码/帧数等基本检查写在同一脚本。脚本应打印实际校验结果，不用额外一轮工具重复核对。
        - 基本检查通过且命令成功结束后，下一步发送用户要求的产物，再给简短结论，任务结束。用户只要动图时直接发送动图，无需另做预览图、README或附加产物。
        - 用户未要求精修且没有错误证据时，不重新生成以微调配色/时长/布局，不做像素统计/逐帧差分或全盘搜索字体。需要深入验证的任务、真实错误和用户要求优先，不能为省调用省掉必要验证。
    """.trimIndent()
}
