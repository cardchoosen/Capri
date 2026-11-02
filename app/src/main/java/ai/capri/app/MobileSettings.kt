package ai.capri.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable internal fun MobileSettings(vm: CapriModel, open: (String) -> Unit) {
    val revision by vm.updates.collectAsState()
    val models = remember(revision) { vm.store.models.all() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeading("设置", "全局配置对所有对话生效；会话专属内容单独标注。")
        SettingsSection("模型与账号") {
            SettingsRow("模型配置", "添加 API、管理模型与默认选择", Icons.Outlined.Tune, "${models.size} 个账号") { open("models") }
            SettingsRow("Git 账号", "全局 · GitHub 与其他 Git 服务", Icons.Outlined.AccountCircle) { open("git") }
            SettingsRow("SSH 连接", "全局 · 身份与密钥", Icons.Outlined.Key, if(vm.runtime.ready) "" else "需准备环境", enabled = vm.runtime.ready) { open("ssh") }
        }
        SettingsSection("能力与扩展") {
            SettingsRow("Tools", "内置工具与能力说明", Icons.Outlined.Build) { open("tools") }
            SettingsRow("MCP", "外部服务连接与配置", Icons.Outlined.Hub) { open("mcp") }
            SettingsRow("Skills", "技能库、资源与公共仓库同步", Icons.Outlined.AutoAwesome) { open("skills") }
            SettingsRow("增强能力", "全局 · 搜索、抓取与分析配置", Icons.Outlined.Extension) { open("enhancements") }
        }
        SettingsSection("记忆与规则") {
            SettingsRow("记忆与资料", "查看历史、记忆与文档；详情内选择范围", Icons.Outlined.Bookmarks) { open("knowledge") }
            SettingsRow("规则管理", "分别管理全局与当前对话规则", Icons.Outlined.Rule) { open("rules") }
        }
        SettingsSection("连接与权限") {
            SettingsRow("电脑网页连接", "通过 USB 在电脑继续使用 Capri", Icons.Outlined.Devices) { open("connection") }
            SettingsRow("Android 权限", "设备能力与系统授权状态", Icons.Outlined.PhonelinkSetup) { open("native") }
        }
        SettingsSection("环境与存储") {
            SettingsRow("执行环境", "环境状态、安装档案与诊断", Icons.Outlined.Terminal, vm.environmentState().message) { open("environment") }
            SettingsRow("存储管理", "当前对话临时文件、依赖与共享缓存", Icons.Outlined.Storage) { open("storage") }
        }
        SettingsSection("高级与关于") {
            SettingsRow("Root", "Android 宿主授权与核验", Icons.Outlined.AdminPanelSettings) { open("root") }
            SettingsRow("关于 Capri", "版本信息与运行说明", Icons.Outlined.Info) { open("about") }
        }
    }
}
