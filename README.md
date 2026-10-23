# Capri · 摩羯

Android 原生个人 AI Agent。在手机上执行任务，推理由你配置的模型 API 提供。

开发预览 `0.1.0` · Android 9+ · ARM64 · 无需 Root / Shizuku

- 多会话与独立工作区，支持文件、图片和 GIF 交付。
- 本地 Ubuntu 环境，支持 Python、Node.js、C/C++ 和持续终端。
- Android 原生工具、内置浏览器操作、全局 Git / SSH 配置。
- MCP 扩展与按需加载的 Skills，可在「运行」页管理。

## 使用

配置兼容 Chat Completions 和工具调用的模型地址、模型名及 API Key，然后创建会话。首次自动联网准备运行环境，后续复用。

## 开发

```sh
python3 tools/verify.py --profile local
```

需要 JDK 17+、Android SDK 36。PRoot 与 Android 共用内核，工作区分离不构成强安全沙箱。

[项目文档与计划](https://github.com/cardchoosen/CapriDocs) · [第三方来源](docs/THIRD_PARTY.md)
