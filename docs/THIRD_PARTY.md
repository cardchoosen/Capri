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

## 会话 pip 引导

沿用 rootfs 中 Python 自带的 ensurepip/_bundled/pip-25.0.1-py3-none-any.whl（未额外下载或捆绑第二份 pip）。由 session_bootstrap.py 在各会话 .venv 中离线安装 pip；既有虚拟环境保留；第三方安装按包和版本选择内置预编译渠道或 PyPI。

- 版本、路径、SHA-256 与来源：`python-bootstrap.json`。
- pip MIT 许可证副本：`PIP-LICENSE.txt`；原 wheel 内包含 vendored 依赖的许可说明。
- 官方安装说明：https://pip.pypa.io/en/stable/installation/；ensurepip：https://docs.python.org/3.12/library/ensurepip.html。
- 当前包管理入口安装兼容 wheel，不编译源码包；编译工具和开发头文件属于后续环境扩展。

## Git / curl 独立工具层

`devtools.bundle` 包含 Alpine v3.22/main/aarch64 的 Git 2.49.1-r0、curl 8.14.1-r4、Git 模板、证书及其动态依赖。musl ELF 解释器沿用基础 rootfs，不在工具层替换。包版本、下载地址、源码 aports commit、上游地址、许可证和 APK SHA-256 全部记录在 `devtools-packages.json`；该版本沿用发行版维护分支，并非上游最新版本。

- 构建：`python3 tools/build_devtools.py`，按锁定清单校验 APK SHA-256 后生成确定性资源包；首次解析索引的命令见脚本。索引和初次 APK 均经官方 HTTPS 获取，未执行 Alpine APK 签名验证。后续重建校验已记录摘要，摘要不等同签名认证。
- 运行期：校验 APK 内资源包 SHA-256，解压到版本目录并原子切换可见性，不覆盖基础 rootfs、会话 HOME/venv 或工作区。总计18项元数据，实际附带17个包（musl只记录依赖来源）。首次部署无需联网，后续 Git/HTTP 访问按任务联网。
- Git GPL-2.0 文本：`GIT-COPYING.txt`，来源 https://github.com/git/git/blob/v2.49.1/COPYING；curl 许可证：`CURL-COPYING.txt`，来源 https://github.com/curl/curl/blob/curl-8_14_1/COPYING。其他依赖许可证分别见清单，公开发行前仍需完成对应源码/许可材料收集。
- Git 路径配置：https://git-scm.com/docs/git；curl 证书策略：https://curl.se/docs/sslcerts.html。工具层提供 CA bundle，默认保持证书校验；不配置全局 Git 作者或收集凭据。

## OpenSSH 客户端

独立开发工具层新增 Alpine v3.22/main/aarch64 的 openssh-client-default、openssh-client-common、openssh-keygen 10.0_p1-r10及动态依赖；24项依赖记录，实际附带23个包（musl仍由基础rootfs提供）。Git/curl版本不变；所有版本/URL/aports源码commit/许可证/APK SHA-256记录在devtools-packages.json。APK只增加客户端，没有openssh-server或SSH后台服务。

- 上游许可证副本：OPENSSH-LICENCE.txt，来源 https://github.com/openssh/openssh-portable/blob/V_10_0_P1/LICENCE；其他依赖按清单分别记录，公开发行所需材料沿用前述待完善要求。
- 默认配置与包装器依据 https://man.openbsd.org/ssh_config、https://man.openbsd.org/ssh-keygen、https://man.openbsd.org/sftp；PRoot兼容身份映射见 https://proot-me.github.io/ 的-0说明。
- tools/verify_ssh_device.py使用开发机Paramiko（https://docs.paramiko.org/），仅供loopback测试，不打包进APK或手机Python环境。

## Python 科学计算与绘图预编译渠道

`python-packages.bundle` 固定取自 Alpine v3.22 main/community aarch64：Matplotlib 3.9.3-r0、NumPy 2.2.4-r1、Pillow 11.2.1-r0及其依赖，52项来源记录、34个实际附带APK。完整版本、上游/aports、许可证、URL、APK SHA-256见 `python-native-packages.json`。它与系统 Python 3.12 ABI匹配，不代表最新上游版本。

- `tools/build_python_packages.py`校验锁定APK摘要，把11个Python发行包转换为仅供Capri本地使用的wheel，native tag为`cp312-cp312-linux_aarch64`，不伪称manylinux/musllinux通用轮子；保留发行包元数据/许可文件并增加capri-origin.json。共享动态依赖独立放入版本层，正常pip仍负责会话venv安装、依赖解析和卸载。
- Alpine Matplotlib的数据和字体通过wheel `.data/data/share/matplotlib/mpl-data`方案进入venv，沿用发行版sys.prefix相对查找，不修改Matplotlib源码。默认Agg无图形后端；OpenBLAS默认单线程，适合手机执行。现有DejaVu字体不含完整中文字形，未增加中文字体包。
- 初次解析官方HTTPS索引/APK，未执行APK签名验证；之后重建校验固定SHA-256。APK维护脚本不执行；不改基础rootfs或其不完整的apk数据库，不提供通用apk包管理。
- 资源包约22.8MB，版本目录部署一次；Python依赖按会话安装。匹配内置无extras及版本条件时可离线安装；其余请求使用PyPI兼容wheel加本地候选，不自动放宽版本或切换源码编译。系统Python/musl不替换；旧版本不自动回收。
- 各组件许可证随来源清单分别记录（包括Matplotlib、NumPy、Pillow、OpenBLAS、字体及图像库），公开发行前仍需补齐对应源码、许可和再分发材料；本地重打包不等同已完成发行审计。
