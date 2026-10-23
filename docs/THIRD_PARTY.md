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

## Ubuntu ARM64 用户空间（当前）

当前APK不再携带Alpine/开发工具/科学包归档，只附带来源描述与准备脚本；PRoot二进制仍随APK。首次创建会话从官方HTTPS下载Ubuntu Base 24.04.5 ARM64，固定29,936,675字节，SHA-256 `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`。来源与基线包见 `app/src/main/assets/runtime/ubuntu-source.json`；对应官方SHA256SUMS经HTTPS核对，未执行镜像GPG验证。

- 镜像：https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz
- apt源：Ubuntu ports noble/noble-updates/noble-security，main/universe；保持发行版签名/内置密钥校验，未使用trusted=yes。CA安装前HTTP、之后HTTPS。实际安装版本和许可位于运行时dpkg数据库及/usr/share/doc；不把历史Alpine版本清单当作现状。
- Python/venv、CA、Git/curl、OpenSSH为基线，Node/npm/GCC和第三方Python库按需正常apt/pip获取。venv的pip由Ubuntu python3-venv提供的ensurepip种子离线创建；源码构建按实际依赖，不维护自制Alpine wheel。
- Android不允许硬链接，固定镜像中的两个硬链接别名解包后转相对符号链接；运行PRoot --link2symlink供dpkg正常维护。该兼容映射不是额外权限。
- 镜像、包及其依赖各自遵守许可证；公开发行仍需核对PRoot来源/对应源码及分发材料，当前本地预览不宣称完成合规审计。

## 界面与测试依赖

Markdown使用Markwon 4.6.2（core/tables/strikethrough），源码及许可：https://github.com/noties/Markwon 。宿主SSH测试使用Paramiko，只供loopback测试，不进入主APK。Gradle依赖版本以app/build.gradle.kts为准。

旧运行时来源和许可证清单保留作历史审计，见 [LEGACY_RUNTIME.md](LEGACY_RUNTIME.md) 及原JSON记录；脚本移至tools/legacy，不再生成或随APK发布那些资源。


## Android WebView（03-A）

浏览器使用手机系统WebView，不将Chromium内核打包入APK。新增适配依赖AndroidX WebKit 1.14.0（Apache-2.0），运行时检测MULTI_PROFILE。API来源：https://developer.android.com/jetpack/androidx/releases/webkit 。借鉴Operit、Playwright MCP与browser-use的架构/观察思路，未复制其浏览器实现代码；具体参考与差异记录在独立文档仓库architecture/Browser.md。
