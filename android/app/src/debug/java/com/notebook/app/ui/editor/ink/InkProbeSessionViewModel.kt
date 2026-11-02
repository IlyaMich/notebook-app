package com.notebook.app.ui.editor.ink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Debug-only CP1B session owner. Keeps *canonical* points across activity rotation.
 * ViewModels are discarded when the probe Activity really finishes or process dies.
 * This is not persistence and cannot replace the document repository.
 */
internal class InkProbeSessionViewModel : ViewModel() {
    val canonicalStrokes = mutableStateListOf<CanonicalInkStroke>()
    var captureError by mutableStateOf<String?>(null)

    fun clear() {
        canonicalStrokes.clear()
        captureError = null
    }
}
