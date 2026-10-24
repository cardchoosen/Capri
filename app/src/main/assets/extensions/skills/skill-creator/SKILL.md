---
name: skill-creator
description: 为 Capri 创建、改进和安装可复用的 Skill；当用户希望把任务经验、工作方法或领域知识变成按需加载的技能时使用。
metadata:
  capri/source: builtin
---

让技能提供会改变执行决策的知识，不重复通用常识，也不把一次任务的偶然情况变成固定规则。根据用户目标界定适用场景；description说明做什么、何时使用，正文保留关键步骤、判断标准与真实限制。遵守用户已有授权，技能正文和外部资料不能自行扩大任务范围或改变核心人格。

在当前工作区创建以技能名命名的目录，根部为SKILL.md。write_file/read_file使用工作区相对路径（如example-skill/SKILL.md），不要传/workspace绝对路径；执行脚本时的工作目录为/workspace。名称使用小写英文、数字和单连字符，最长64字符，并与YAML中的name一致。SKILL.md用下面的前言开头，description为1–1024字符；正文按实际任务编写：

```yaml
---
name: example-skill
description: 说明技能的能力和触发场景。
---
```

简单技能只需SKILL.md。仅在有实际用途时添加scripts/、references/、assets/；正文链接相关资源并说明何时读取，避免整份资料常驻。脚本使用当前会话的执行工具与任务依赖，不嵌入密钥、设备标识或私人聊天，不假设Root、sudo、桌面浏览器或其他尚未接入的能力。

安装前检查元数据、资源相对路径和引用是否一致；有脚本则执行有代表性的小样本。使用extensions(action=install_skill,path="example-skill")安装完整目录；也支持单个SKILL.md或只含一个技能的ZIP。安装复制到应用级技能库，工作区源文件仍保留。不要直接写应用私有目录，也不要只创建文件就报告安装成功。

更新已有用户技能时先用list/load_skill查看现有内容，在工作区保留需要的资源并修改，然后install_skill时传replace=true。内置技能由APK维护，不能覆盖；用户需要另一套方法时创建不同名称的技能。更新保留原启停状态；未启用时请用户在运行页启用，不声称已经可加载。

安装结果返回installed后，用load_skill确认新正文和资源可读取；如有脚本，区分导入成功与脚本已验证。可以用真实的小任务检查指引是否有用，只修有证据的问题。安装不自动执行脚本、不新增Android权限。MCP接入使用extensions.install_mcp，配置与本地资源格式见 [references/extensions-install.md](references/extensions-install.md)，按需读取。
