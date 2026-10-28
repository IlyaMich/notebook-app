package com.notebook.app.data.library.model

/** Input to the atomic new-note operation; STO-02 owns serialization and validation. */
data class InitialPageSpec(
    val width: Double,
    val height: Double,
    /** Canonical tagged background JSON, not a rasterized page. */
    val backgroundJson: String,
) {
    companion object {
        fun a4PortraitBlank(): InitialPageSpec = InitialPageSpec(
            width = 595.276,
            height = 841.890,
            backgroundJson = """{"type":"blank","color":"#FFFFFF"}""",
        )
    }
}
