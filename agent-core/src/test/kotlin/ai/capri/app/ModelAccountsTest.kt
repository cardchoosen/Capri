package ai.capri.app

import ai.capri.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelAccountsTest {
    @Test fun reasoningPersistsPerChatAndRejectsStaleModelUpdates() {
        var saved: String? = null
        fun store() = ModelAccounts({ saved }, { saved = it }, { ModelConfig() })
        val accounts = store()
        val account = ModelAccount(provider = "deepseek", name = "DeepSeek", endpoint = "https://api.deepseek.com",
            key = "fixture", protocol = ModelProtocol.CHAT, models = listOf(ModelOption("deepseek-flash"), ModelOption("deepseek-v4-pro")))
        accounts.save(account)
        accounts.selectReasoning("first", account.id, "deepseek-flash", "max")
        assertEquals("max", store().config("first").reasoningEffort)
        assertEquals("high", accounts.config("second").reasoningEffort)
        assertEquals("high", accounts.config().reasoningEffort)
        accounts.selectForNewChats("first", account.id, "deepseek-flash", listOf("first", "second"))
        assertEquals("max", accounts.config("first").reasoningEffort)
        accounts.selectReasoning("first", account.id, "deepseek-flash", "default")
        assertEquals("high", accounts.config("first").reasoningEffort)
        accounts.select("first", account.id, "deepseek-v4-pro")
        assertTrue(runCatching { accounts.selectReasoning("first", account.id, "deepseek-flash", "low") }.isFailure)
        assertTrue(runCatching { accounts.selectReasoning("first", account.id, "deepseek-v4-pro", "ultra") }.isFailure)
        assertEquals("high", accounts.config("first").reasoningEffort)
        accounts.selectReasoning("first", account.id, "deepseek-v4-pro", "none")
        assertEquals("none", store().config("first").reasoningEffort)
    }

    @Test fun webDefaultChangesOnlyCurrentAndFutureChatsIncludingAfterRestart() {
        var saved:String?=null
        fun store()=ModelAccounts({saved},{saved=it},{ModelConfig()})
        var accounts=store()
        val a=ModelAccount(provider="custom",name="A",endpoint="https://a.example/v1",key="fixture-a",protocol=ModelProtocol.CHAT,models=listOf(ModelOption("a1"),ModelOption("a2")))
        val b=ModelAccount(provider="custom",name="B",endpoint="https://b.example/v1",key="fixture-b",protocol=ModelProtocol.CHAT,models=listOf(ModelOption("b1")))
        accounts.save(a);accounts.save(b);accounts.select("explicit",a.id,"a2")
        accounts.selectForNewChats("current",b.id,"b1",listOf("current","legacy","explicit"))
        assertEquals("b1",accounts.config("current").model)
        assertEquals("a1",accounts.config("legacy").model)
        assertEquals("a2",accounts.config("explicit").model)
        accounts.initializeChat("new")
        assertEquals("b1",accounts.config("new").model)
        accounts=store()
        accounts.selectForNewChats("current",a.id,"a2",listOf("current","legacy","explicit","new"))
        assertEquals("b1",accounts.config("new").model)
        assertEquals("a1",accounts.config("legacy").model)
        assertEquals("a2",accounts.config("future").model)
        assertEquals("a2",accounts.config("explicit").model)
        accounts.remove(b.id)
        assertNull(accounts.selected("new"))
    }
    @Test fun defaultUpdateIsAtomicAndKeepsLegacyChoices() {
        var saved:String?=null;var failWrite=false
        val accounts=ModelAccounts({saved},{if(failWrite)error("disk full");saved=it},{ModelConfig()})
        val a=ModelAccount(provider="custom",name="A",endpoint="https://a.example/v1",key="fixture",protocol=ModelProtocol.CHAT,models=listOf(ModelOption("a1"),ModelOption("a2")))
        accounts.save(a)
        accounts.selectForNewChats("current",a.id,"a2",listOf("current","other"))
        val before=saved;failWrite=true
        try{accounts.selectForNewChats("current",a.id,"a1",listOf("current","other"));fail()}catch(_:IllegalStateException){}
        assertEquals(before,saved);assertEquals("a2",accounts.config("current").model);assertEquals("a1",accounts.config("other").model)
    }
    @Test fun previouslyUnconfiguredChatDoesNotAdoptTheNewDefault() {
        var saved:String?=null
        val accounts=ModelAccounts({saved},{saved=it},{ModelConfig()})
        val empty=ModelAccount(provider="custom",name="Empty",endpoint="https://empty.example/v1",key="fixture",protocol=ModelProtocol.CHAT,models=emptyList())
        val ready=ModelAccount(provider="custom",name="Ready",endpoint="https://ready.example/v1",key="fixture",protocol=ModelProtocol.CHAT,models=listOf(ModelOption("ready")))
        accounts.save(empty);accounts.save(ready)
        assertNull(accounts.selected("other"))
        accounts.selectForNewChats("current",ready.id,"ready",listOf("current","other"))
        assertNull(accounts.selected("other"));assertEquals("ready",accounts.config("new").model)
    }
    @Test fun migrationSelectionDeletionAndRestartNeverSwitchCredentialsSilently() {
        var saved: String? = null
        var reads = 0
        fun store() = ModelAccounts({ saved }, { saved = it }, { reads++; ModelConfig("https://api.deepseek.com/v1", "legacy", "old-key") })
        var accounts = store(); val old = accounts.all().single()
        assertEquals(ModelProtocol.CHAT, old.protocol)
        assertEquals("old-key", accounts.config("chat-a").key)
        accounts.select("chat-a", old.id, "legacy")
        val second = ModelAccount(provider = "openai", name = "second", endpoint = "https://api.openai.com/v1", key = "new-key", protocol = ModelProtocol.RESPONSES,
            models = listOf(ModelOption("model-b", context = 1_048_576)))
        accounts.save(second); accounts.select("chat-b", second.id, "model-b")
        val frozen = accounts.config("chat-b")
        accounts.select("chat-b", old.id, "legacy")
        assertEquals("new-key", frozen.key)
        accounts = store()
        assertEquals(1, reads)
        assertEquals("old-key", accounts.config("chat-a").key)
        accounts.select("chat-b", second.id, "model-b")
        assertEquals("RESPONSES", accounts.config("chat-b").protocol)
        assertEquals(1_048_576, accounts.config("chat-b").contextWindow)
        accounts.remove(second.id)
        assertTrue(accounts.config("chat-b").key.isEmpty())
        assertEquals("old-key", accounts.config("chat-a").key)
        assertTrue(runCatching { accounts.select("chat-a", second.id, "model-b") }.isFailure)
    }
    @Test fun validationAndStorageFailureKeepExistingConfiguration() {
        var saved: String? = null; var fail = false
        val store = ModelAccounts({ saved }, { if (fail) error("disk"); saved = it }, { ModelConfig() })
        val a = ModelAccount(provider = "custom", name = "test", endpoint = "https://example.com/v1", key = "key", protocol = ModelProtocol.CHAT)
        store.save(a)
        for (url in listOf("http://example.com", "https://key@example.com", "https://example.com?key=bad", "https://example.com/#secret")) {
            assertTrue(runCatching { store.save(a.copy(endpoint = url)) }.isFailure)
        }
        fail = true
        assertTrue(runCatching { store.save(a.copy(key = "changed")) }.isFailure)
        assertEquals("key", store.all().single().key)
        assertEquals("1M", Providers.contextLabel(1_048_576))
        assertEquals("1M", Providers.contextLabel(1_000_000))
        assertEquals("128K", Providers.contextLabel(131072))
        assertEquals("上下文未知", Providers.contextLabel(null))
        assertEquals("claude", Providers.logo("openrouter", "anthropic/claude-test"))
        assertEquals("deepseek", Providers.logo("siliconcloud", "deepseek-ai/test"))
        assertEquals(12, Providers.all.size)
        assertFalse(Providers.all.any { it.id.contains("doubao") || it.id.contains("volc") })
    }
}
