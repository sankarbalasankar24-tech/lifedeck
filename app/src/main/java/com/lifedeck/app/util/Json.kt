package com.lifedeck.app.util

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One configured JSON codec for the whole app. */
val appJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

inline fun <reified T> encode(value: T): String = appJson.encodeToString(value)

inline fun <reified T> decode(text: String?): T? {
    if (text.isNullOrBlank()) return null
    return try {
        appJson.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

inline fun <reified T> decodeList(text: String?): List<T> = decode<List<T>>(text) ?: emptyList()
