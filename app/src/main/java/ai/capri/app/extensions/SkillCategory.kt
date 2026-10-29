package ai.capri.app.extensions

/** Organization only: categories never grant permissions or imply verified capability. */
internal enum class SkillCategory(val id: String, val title: String) {
    GENERAL("general", "General"), WEAPON("weapon", "Weapon");
    companion object {
        fun from(value: String?) = entries.firstOrNull { it.id == value } ?: GENERAL
    }
}
