# Capri · 摩羯

Android 原生个人 AI Agent，在手机上执行任务。

- 多会话与独立工作区
- [安装环境档案](docs/INSTALLATION_ENVIRONMENT.md)：Markdown查询、自动更新与有效检测复用
- 固定 Capri 个人对话与任务模式、有来源的个人记忆及跨会话历史回查（[首版说明](docs/PERSONA.md)）
- 多模型 API、会话内模型切换、厂商 Logo 与上下文容量显示
- Python、Node.js、C/C++ 与持续终端
- Android 原生工具与浏览器操作，可选宿主 Root 检测、授权与命令执行
- 全局 Git / SSH 配置
- MCP 扩展与按需加载的 Skills
- Tools 能力目录，查看内置与已连接 MCP 工具
- 免费网页搜索、正文读取与来源引用，可选供应商增强
- 可追溯的历史摘录、分作用域的长期记忆与文本资料检索
- Markdown、文件、图片与 GIF 展示

## 相关文档

- [电脑网页与 USB 自动连接](docs/USB_WEB.md)：登录自启、设备检测、断线恢复和对话模型选择
- [文件与图片输入](docs/INPUT_ATTACHMENTS.md)：两端附件草稿、图片视觉输入与统一工具折叠

- [Agent 内核架构](docs/AGENT_HARNESS.zh-CN.md)：执行循环、工具调度与可恢复上下文
- [Agent 优化待办](docs/AGENT_OPTIMIZATION_TODO.zh-CN.md)：十个方向、60 个子项
- [执行循环与任务控制](docs/AGENT_TURN_CONTROL.zh-CN.md)：阶段、补充要求、澄清、预算、交付证据与停滞处理

- [响应耗时与人物收尾](docs/AGENT_LATENCY.zh-CN.md)：请求用途与耗时归因、纯 JSON 直接交付、辅助摘要离线评测

- [上下文管理](docs/AGENT_CONTEXT.zh-CN.md)：约束原文保护、完整交互归档、类型化预览与微压缩实验

- [工具发现与能力感知](docs/AGENT_DISCOVERY.zh-CN.md)：能力状态、核心工具直供、检索、受控桥接和定义工作集

- [程序化调用与运行效率](docs/AGENT_PROGRAMS.zh-CN.md)：受控 RPC、热内核、批处理、共享并发与可恢复回执

- [终端与长任务生命周期](docs/AGENT_EXECUTION.zh-CN.md)：统一登记、完成事件、持久回执、输出分页与环境失效
- [Coding 能力](docs/AGENT_CODING.zh-CN.md)：写后检查、Python LSP 试点、异步诊断、版本快照与精确引用
