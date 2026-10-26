package ai.capri.app

import ai.capri.app.model.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Only hosts the real model components, without initializing the Linux runtime or real accounts. */
class ModelUiTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = this
        var saved: String? = null
        val accounts = ModelAccounts({ saved }, { saved = it }, { ModelConfig() })
        val first = ModelAccount(provider = "deepseek", name = "DeepSeek 测试", endpoint = "https://example.com/v1", key = "fixture", protocol = ModelProtocol.CHAT,
            models = listOf(ModelOption("ds", "DeepSeek Fixture", 1_048_576)))
        val second = ModelAccount(provider = "gemini", name = "Gemini 测试", endpoint = "https://example.com/v1beta", key = "fixture", protocol = ModelProtocol.GEMINI,
            models = listOf(ModelOption("gm", "Gemini Fixture", 131072), ModelOption("unknown", "Unknown Fixture")))
        accounts.save(first); accounts.save(second)
        setContent {
            var revision by remember { mutableIntStateOf(0) }
            var manager by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalContext provides target) {
                MaterialTheme(shapes = Shapes(extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(2.dp), medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(4.dp), extraLarge = RoundedCornerShape(4.dp)), colorScheme = darkColorScheme(primary = Color(0xFFA5FF75), background = Color(0xFF080B09), surface = Color(0xFF111612))) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(20.dp)) {
                            ModelPicker(accounts, "fixture-chat", false, revision, { revision++ }, { manager = true })
                            if (manager) ModelAccountsPanel(accounts, revision, { revision++ }, { manager = false })
                        }
                    }
                }
            }
        }
    }
}

