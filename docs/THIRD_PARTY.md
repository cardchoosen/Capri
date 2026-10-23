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
- 运行期：校验 APK 内资源包 SHA-256，解压到单一当前目录并原子切换可见性，不覆盖基础 rootfs、会话 HOME/venv 或工作区。总计18项元数据，实际附带17个包（musl只记录依赖来源）。首次部署无需联网，后续 Git/HTTP 访问按任务联网。
- Git GPL-2.0 文本：`GIT-COPYING.txt`，来源 https://github.com/git/git/blob/v2.49.1/COPYING；curl 许可证：`CURL-COPYING.txt`，来源 https://github.com/curl/curl/blob/curl-8_14_1/COPYING。其他依赖许可证分别见清单，公开发行前仍需完成对应源码/许可材料收集。
- Git 路径配置：https://git-scm.com/docs/git；curl 证书策略：https://curl.se/docs/sslcerts.html。工具层提供 CA bundle，默认保持证书校验；不配置全局 Git 作者或收集凭据。

## OpenSSH 客户端

独立开发工具层新增 Alpine v3.22/main/aarch64 的 openssh-client-default、openssh-client-common、openssh-keygen 10.0_p1-r10及动态依赖；24项依赖记录，实际附带23个包（musl仍由基础rootfs提供）。Git/curl版本不变；所有版本/URL/aports源码commit/许可证/APK SHA-256记录在devtools-packages.json。APK只增加客户端，没有openssh-server或SSH后台服务。

- 上游许可证副本：OPENSSH-LICENCE.txt，来源 https://github.com/openssh/openssh-portable/blob/V_10_0_P1/LICENCE；其他依赖按清单分别记录，公开发行所需材料沿用前述待完善要求。
- 默认配置与包装器依据 https://man.openbsd.org/ssh_config、https://man.openbsd.org/ssh-keygen、https://man.openbsd.org/sftp；PRoot兼容身份映射见 https://proot-me.github.io/ 的-0说明。
- tools/verify_ssh_device.py使用开发机Paramiko（https://docs.paramiko.org/），仅供loopback测试，不打包进APK或手机Python环境。

## Python 科学计算与绘图预编译渠道

`python-packages.bundle` 固定取自 Alpine v3.22 main/community aarch64：Matplotlib 3.9.3-r0、NumPy 2.2.4-r1、Pillow 11.2.1-r0及其依赖，52项来源记录、34个实际附带APK。完整版本、上游/aports、许可证、URL、APK SHA-256见 `python-native-packages.json`。它与系统 Python 3.12 ABI匹配，不代表最新上游版本。

- `tools/build_python_packages.py`校验锁定APK摘要，把11个Python发行包转换为仅供Capri本地使用的wheel，native tag为`cp312-cp312-linux_aarch64`，不伪称manylinux/musllinux通用轮子；保留发行包元数据/许可文件并增加capri-origin.json。共享动态依赖独立放入当前组件层，正常pip仍负责会话venv安装、依赖解析和卸载。
- Alpine Matplotlib的数据和字体通过wheel `.data/data/share/matplotlib/mpl-data`方案进入venv，沿用发行版sys.prefix相对查找，不修改Matplotlib源码。默认Agg无图形后端；OpenBLAS默认单线程，适合手机执行。现有DejaVu字体不含完整中文字形，未增加中文字体包。
- 初次解析官方HTTPS索引/APK，未执行APK签名验证；之后重建校验固定SHA-256。APK维护脚本不执行；不改基础rootfs或其不完整的apk数据库，不提供通用apk包管理。
- 资源包约22.8MB，当前目录部署一次；Python依赖按会话安装。匹配内置无extras及版本条件时可离线安装；其余请求使用PyPI兼容wheel加本地候选，不自动放宽版本或切换源码编译。系统Python/musl不替换；旧SHA目录在空闲且当前组件部署成功后清理。
- 各组件许可证随来源清单分别记录（包括Matplotlib、NumPy、Pillow、OpenBLAS、字体及图像库），公开发行前仍需补齐对应源码、许可和再分发材料；本地重打包不等同已完成发行审计。

## Node.js/npm 与 C/C++ 工具链（V2-02）

开发工具层扩展为 Alpine v3.22 main/community ARM64 的固定66项记录/65个附带包：Node.js 22.23.2-r0、npm 11.6.4-r0、GCC/G++ 14.2.0-r6、make 4.4.1-r3、binutils 2.44-r3、pkgconf 2.4.3-r0、Python 3.12.15开发头文件、Linux 6.14.2头文件及依赖。原Git/curl/OpenSSH版本不变。完整来源、aports commit、许可证和APK SHA-256继续记录在devtools-packages.json；不是上游最新版本承诺。

- tools/build_devtools.py分别解析官方main/community索引，初次HTTPS解析未执行APK签名校验；之后严格校验锁定APK摘要，不执行APK维护脚本。基础Python包只记录依赖而不附带，rootfs/venv不替换。
- 工具层约133.2MB压缩/约389MiB展开；资源按32MiB分成4个devtools-NNN.bundle，devtools.parts.json定义次序，运行时串接校验整个gzip流SHA-256再解包。首次解包上限120秒，已有匹配摘要的当前目录复用；空闲且部署成功后回收旧SHA目录。仅保留当前一套组件，原子提交使用临时暂存与恢复目录。
- GCC/cc/g++/c++/cpp包装器通过--sysroot及-B指向/opt/capri/tools内的头文件、启动对象和工具；musl副本用于完整编译sysroot，实际ELF解释器仍使用原rootfs的/lib，不取得Android Root。不依赖基础rootfs完整apk数据库，不提供通用apk安装/系统升级。
- Android拒绝硬链接，构建时把tar硬链接别名转换成相对符号链接，保留普通符号链接。Node外部ICU数据跟随当前组件层设置NODE_ICU_DATA/ICU_DATA，解决搬迁后npm实际安装时的ICU错误；版本命令本身不足以验证完整可用性。
- Node项目node_modules在当前工作区；npm缓存/root/.cache/npm和全局前缀/root/.local/npm按会话隔离，CLI目录加入PATH。CA保持校验，Node配置额外CA；未设置全局账号或复制Git Token到npm。npm项目脚本仍可执行同UID代码，目录分开不构成强安全沙箱。
- Python开发头文件和编译器允许手工构建原生扩展；python_env仍只安装wheel，不自动转源码构建。没有加入CMake、Rust、Go或Node开发头文件，不保证任意Python/npm原生依赖都能构建；Node原生扩展可能仍需下载对应头文件。
- Node/各依赖许可与npm Artistic-2.0、GCC/binutils/GNU make及Linux头文件的GPL等要求分别遵循清单；公开发行前仍需补齐对应源码/许可证/构建材料，不将本地预览视为已完成再分发审计。

官方参考：[Node.js包](https://pkgs.alpinelinux.org/package/v3.22/main/aarch64/nodejs)、[npm包](https://pkgs.alpinelinux.org/package/v3.22/community/aarch64/npm)、[build-base](https://pkgs.alpinelinux.org/package/v3.22/main/aarch64/build-base)、[GCC目录与sysroot选项](https://gcc.gnu.org/onlinedocs/gcc/Directory-Options.html)。

2026-10-23 搬迁补充：启动器用MAGIC指向工具层usr/share/misc/magic.mgc，file不依赖基础rootfs的默认数据路径；CPATH同时包含工具层Python.h和原基础Python包提供的/usr/include/python3.12/pyconfig.h，避免跳过重复Python运行时包后缺少ABI配置。两者仍对应锁定的Python3.12.15，并未生成替代配置或覆盖系统文件；诊断公开实际magic路径及ABI头文件存在状态。

## 当前环境检查与修复（2026-10-23）

基础系统继续使用runtime/rootfs；工具及科学库改用runtime/tools/current与runtime/python-packages/current，仅保留当前应用内置的一套资源。资源摘要用于识别内容，不是安装版本目录；不在线追踪任意最新版本，也不提供多版本选择或回退。升级应用后，工具/科学库在下一次使用且空闲时更新；已有基础系统不隐式覆盖，需显式重新部署。

- tools/build_environment_inventory.py从准确的APK资源包生成rootfs/devtools/python-packages.files.json及rootfs.sha256；修改任一资源包后必须重新生成清单，并更新runtime-sha256.json。清单不从手机现有文件生成。
- 完整检查对照资源清单逐文件核对SHA-256、符号链接目标及所需执行权限；排除运行时生成的__pycache__/pyc/pyo，不扫描额外手装文件。实际命令诊断独立执行，校验有差异时先停止诊断；文件差异也可能是用户手工修改。
- 资源整体摘要、解包后清单均校验通过才原子替换；.staging/.previous仅用于短暂提交/中断恢复，成功后删除。旧SHA目录安全清理不跟随符号链接。替换过程仍需足够的额外磁盘空间。
- RuntimeGate统一登记短脚本与持续终端的PRoot进程，任何一个仍存活都拒绝替换；启动/检查/维护共享进程内锁。它不抵御同UID恶意代码、外部文件修改或完整进程树逃逸。
- 修复仅重新部署所选组件，保留sessions/、会话venv/npm依赖、全局SSH与Git账号；基础系统修复会覆盖rootfs中的手工修改，不保证原依赖跨ABI兼容。空间显示是逻辑文件字节数，不等于文件系统实际分配空间。缓存清理和会话依赖重置尚未提供。
