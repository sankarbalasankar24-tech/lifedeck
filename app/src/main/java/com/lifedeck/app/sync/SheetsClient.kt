package com.lifedeck.app.sync

import com.lifedeck.app.data.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One row on its way to (or from) the spreadsheet. */
@Serializable
data class PushRow(
    val table: String,
    val id: String,
    val op: String = "UPSERT",
    val row: JsonElement? = null,
)

@Serializable
data class PushRequest(
    val action: String = "push",
    val secret: String = "",
    val deviceId: String = "",
    val sentAt: Long = 0,
    val rows: List<PushRow> = emptyList(),
)

@Serializable
data class PulledRow(
    val table: String,
    val id: String,
    val row: JsonElement,
)

@Serializable
data class PullResponse(
    val ok: Boolean = false,
    val rows: List<PulledRow> = emptyList(),
    val serverTime: Long = 0,
    val error: String = "",
)

@Serializable
data class PushResponse(
    val ok: Boolean = false,
    val applied: Int = 0,
    val error: String = "",
)

/**
 * Thin Google Sheets bridge.
 *
 * The app never embeds Google API secrets: the user pastes their own Apps
 * Script Web App URL (and optional shared secret) into Settings → Sync. The
 * script owns the spreadsheet and performs row upserts keyed by stable id, so
 * pushes are idempotent and cannot duplicate records.
 *
 * See sync/apps-script/LifeDeckSync.gs for the matching server script.
 */
class SheetsClient(private val settings: SettingsRepository) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    suspend fun push(rows: List<PushRow>, deviceId: String): PushResponse = withContext(Dispatchers.IO) {
        val s = settings.get()
        if (s.syncUrl.isBlank()) return@withContext PushResponse(ok = false, error = "Sync URL not configured")
        val body = json.encodeToString(
            PushRequest.serializer(),
            PushRequest(
                secret = s.syncSecret,
                deviceId = deviceId,
                sentAt = System.currentTimeMillis(),
                rows = rows,
            ),
        )
        when (val res = post(s.syncUrl, body)) {
            is NetResult.Error -> PushResponse(ok = false, error = res.message)
            is NetResult.Ok -> runCatching {
                json.decodeFromString<PushResponse>(res.body.ifBlank { """{"ok":true}""" })
            }.getOrElse { PushResponse(ok = false, error = "Unreadable response: ${it.message}") }
        }
    }

    suspend fun pull(since: Long, limit: Int = 500): PullResponse = withContext(Dispatchers.IO) {
        val s = settings.get()
        if (s.syncUrl.isBlank()) return@withContext PullResponse(ok = false, error = "Sync URL not configured")
        val sep = if (s.syncUrl.contains("?")) "&" else "?"
        val url = "${s.syncUrl}${sep}action=pull&since=$since&limit=$limit" +
            (if (s.syncSecret.isNotBlank()) "&secret=${java.net.URLEncoder.encode(s.syncSecret, "UTF-8")}" else "")
        when (val res = get(url)) {
            is NetResult.Error -> PullResponse(ok = false, error = res.message)
            is NetResult.Ok -> runCatching {
                json.decodeFromString<PullResponse>(res.body)
            }.getOrElse { PullResponse(ok = false, error = "Unreadable response: ${it.message}") }
        }
    }

    suspend fun healthCheck(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val s = settings.get()
        if (s.syncUrl.isBlank()) return@withContext false to "No sync URL set"
        val sep = if (s.syncUrl.contains("?")) "&" else "?"
        val url = "${s.syncUrl}${sep}action=ping" +
            (if (s.syncSecret.isNotBlank()) "&secret=${java.net.URLEncoder.encode(s.syncSecret, "UTF-8")}" else "")
        when (val res = get(url)) {
            is NetResult.Error -> false to res.message
            is NetResult.Ok ->
                if (res.body.contains("\"ok\":true") || res.body.contains("\"ok\": true")) true to "Connected"
                else false to res.body.take(160)
        }
    }

    // ------------------------------------------------------------ transport

    private sealed class NetResult {
        data class Ok(val body: String) : NetResult()
        data class Error(val message: String) : NetResult()
    }

    private fun executeWithRedirects(
        initialUrl: String,
        method: String,
        body: String? = null,
    ): NetResult = try {
        var currentUrl = initialUrl
        var currentMethod = method
        var currentBody = body
        var redirects = 0
        val maxRedirects = 6
        var finalResult: NetResult? = null

        while (redirects < maxRedirects) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.requestMethod = currentMethod

            if (currentMethod == "POST" && currentBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(currentBody.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                if (loc != null) {
                    currentUrl = loc
                    currentMethod = "GET"
                    currentBody = null
                    redirects++
                    continue
                }
            }

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            finalResult = if (code in 200..299) NetResult.Ok(text) else NetResult.Error("HTTP $code ${text.take(200)}")
            break
        }
        finalResult ?: NetResult.Error("Too many redirects")
    } catch (e: IOException) {
        NetResult.Error(e.message ?: "Network error")
    } catch (e: Exception) {
        NetResult.Error(e.message ?: "Request failed")
    }

    private fun post(url: String, body: String): NetResult = executeWithRedirects(url, "POST", body)

    private fun get(url: String): NetResult = executeWithRedirects(url, "GET", null)

    /** Small helper for building structured payloads in tests/scripts. */
    fun pingBody(secret: String): String = buildJsonObject {
        put("action", "ping")
        put("secret", secret)
    }.toString()
}

/** Types used by the sync engine. */
object SyncTables {
    const val ENTRIES = "entries"
    const val GOALS = "goals"
    const val PROJECTS = "projects"
    const val MILESTONES = "milestones"
    const val TRIPS = "trips"
    const val CATEGORIES = "categories"
    const val REMINDERS = "reminders"
    const val MEMORIES = "memories"
    const val HISTORY = "history"
}
