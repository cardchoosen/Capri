---
name: project-development
description: 阅读、修改、构建或调试代码项目时使用，覆盖Python、Node和C/C++，尊重项目已有结构。
metadata:
  capri/source: builtin
---

先查看项目说明、入口和依赖/构建清单，再决定命令。检查现有文件，避免重复重建项目。保持用户指定语言和项目约定；需要辅助语言时明确分工。使用现有terminal或python_run；长任务保留终端/commandId并增量读取，不重复启动。构建失败读取真实错误后修复，优先针对改动运行有意义的测试。输出准确描述已验证内容与产物路径。
