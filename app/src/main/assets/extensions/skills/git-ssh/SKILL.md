---
name: git-ssh
description: 克隆或维护Git仓库、使用SSH/SFTP/SCP、查询已配置全局账号或密钥时使用。
metadata:
  capri/source: builtin
---

Git与SSH全局配置由应用管理。现有Git助手可使用用户配置的HTTPS账号；先查询实际配置，避免重复索取。SSH使用已有全局身份公共清单与配置，按需运行终端获取公钥/别名和连接诊断，不读取或输出私钥内容、Token、模型Key。主机、用户名、端口按用户提供的信息，不能由密钥名猜测。指纹不匹配应说明实际证据，不能通过关闭校验掩盖。Git命令负责本地仓库与传输；Issue/PR等服务操作可在已配置时启用GitHub MCP。操作结果未知时先核验，避免重复提交或推送。
