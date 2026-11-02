package ai.capri.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Shared structure only; character visuals remain a separate concern. */
@Composable internal fun SettingsRow(title: String, subtitle: String, icon: ImageVector? = null, status: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
            if(status.isNotBlank()) Text(status, modifier = Modifier.widthIn(max = 96.dp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
        } }, modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick))
}
@Composable internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.padding(horizontal = 12.dp)) { Column(content = content) }
    }
}
@Composable internal fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
/** Full-screen details retain each feature's existing scrolling and consequential confirmations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DetailPage(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit = {}, dismissButton: (@Composable () -> Unit)? = null) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = title, navigationIcon = { IconButton(onClick = onDismissRequest) { Icon(Icons.Outlined.ArrowBack, "返回") } }) },
                bottomBar = { Surface { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) { dismissButton?.invoke(); confirmButton() } } }) { insets ->
                Box(Modifier.padding(insets).consumeWindowInsets(insets).imePadding().fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) { text() }
            }
        }
    }
}
@Composable internal fun ScrollDetail(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DetailPage(onBack, { Text(title) }, { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) })
}
