package ai.capri.app

/** Release builds expose no debug provider or commands. */
internal object DebugBridge {
    fun attach(model: CapriModel) = Unit
}
