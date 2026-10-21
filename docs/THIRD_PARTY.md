# 第三方运行时

这是本地开发预览。发布前需要补齐对应源码、许可证及可复现构建链；本文件并不声称已完成第三方再分发合规审计。

## PRoot / Android loader

原型使用 OperitTerminalCore 提供的 ARM64 二进制：

- https://github.com/AAswordman/OperitTerminalCore
- `src/main/jniLibs/arm64-v8a/liboperit_proot.so` → `libproot.so`
- `src/main/jniLibs/arm64-v8a/liboperit_loader.so` → `libproot-loader.so`
- 仓库许可证副本：`OPERIT-LICENSE.txt`。
- PRoot上游：https://github.com/proot-me/PRoot（GPL-2.0）；Android fork：https://github.com/termux/proot。

不要把宿主仓库的 LGPL 声明直接当作所有捆绑组件的许可证。分发时需核对具体二进制来源、修改内容与对应源码。

下载文件SHA256：

```text
cb40a1ced11cee76569b4008a9e478c87883ce831152be4eb9570763b82e580d libproot.so
f149774236db1e69b36cc1e4ed3866c7094db2eee52da0d4956aa63a9bb26929 libproot-loader.so
```

## Alpine / Python

- 基础镜像：Alpine 3.22.2 aarch64 minirootfs，https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.2-aarch64.tar.gz
- 额外包来自官方 `v3.22/main/aarch64`。版本、源码commit、许可证元数据见 `runtime-packages.json`。Python与各库许可证不同。
- 基础镜像原始SHA256：`6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489`。

为保证初次启动不依赖网络，Python及动态依赖合并进APK内的rootfs。它仍共享Android内核，不是Linux虚拟机。

APK资源使用 `rootfs.bundle` 文件名保存gzip压缩tar，避免AAPT自动展开`.gz`资源并改变资源名称。

## Markdown 显示

回复正文使用 Markwon 4.6.2（core、tables、strikethrough）及其 CommonMark 解析器。顶层表格和代码块使用 Capri 原生 Compose 容器，实现横向滚动和复制；正文使用原生 TextView spans，无 WebView。

- 源码与许可证：https://github.com/noties/Markwon
- 官方文档：https://noties.io/Markwon/docs/v4/
