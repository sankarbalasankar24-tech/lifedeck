package com.lifedeck.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One-line undo: every completion or deletion can be reversed from the
 * snackbar for a configurable window (Settings → Undo window).
 */
class UndoController(
    private val scope: CoroutineScope,
    val hostState: SnackbarHostState,
) {
    var undoWindowSeconds: Int = 8

    /** Show an "action done · Undo" snackbar; [undo] runs if tapped. */
    fun show(message: String, undo: (suspend () -> Unit)? = null) {
        scope.launch {
            if (undo == null) {
                hostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            } else {
                val result = hostState.showSnackbar(
                    message = message,
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    runCatching { undo() }
                }
            }
        }
    }
}

val LocalUndo = staticCompositionLocalOf<UndoController> {
    error("UndoController not provided")
}
