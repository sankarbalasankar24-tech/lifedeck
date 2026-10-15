package com.lifedeck.app.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.lifedeck.app.data.AppContainer
import com.lifedeck.app.data.repo.AppSettings

/** Dependency access for composables (the manual DI container). */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LifeDeckApp container not provided")
}

/** Latest settings snapshot; screens that need one-off reads use this. */
val LocalSettings = compositionLocalOf { AppSettings() }
