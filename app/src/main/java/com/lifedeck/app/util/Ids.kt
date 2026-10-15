package com.lifedeck.app.util

import java.util.UUID

/**
 * Stable unique IDs. UUIDv4 strings keep records collision-free across devices
 * so Google Sheets sync can upsert by id without duplicating rows.
 */
fun newId(): String = UUID.randomUUID().toString()
