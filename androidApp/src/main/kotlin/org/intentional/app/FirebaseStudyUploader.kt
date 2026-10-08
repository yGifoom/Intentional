package org.intentional.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.intentional.shared.StudyUploadPolicy
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

/** Automatic sync after the study notice. Firebase Auth ID tokens, never admin credentials.
 * REST keeps Firebase dependencies out of the multiplatform/UI modules. */
class FirebaseStudyUploader(context: Context) {
    private val preferences = context.getSharedPreferences("study_upload", Context.MODE_PRIVATE)
    private val config = runCatching {
        context.assets.open("firebase-study.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    }.getOrNull()
    private val project = config?.get("projectId")?.jsonPrimitive?.content.orEmpty()
    private val apiKey = config?.get("apiKey")?.jsonPrimitive?.content.orEmpty()
    val configured = project.matches(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]")) && apiKey.isNotBlank()
    fun cooldownMs() = StudyUploadPolicy.remainingMs(preferences.getLong("last_submission", 0).takeIf { it != 0L }, System.currentTimeMillis())
    val lastFingerprint: String? get() = preferences.getString("last_fingerprint", null)
    val lastSuccessfulAt: Long get() = preferences.getLong("last_successful_at", 0)

    private class HttpFailure(val status: Int) : Exception("Study server returned HTTP $status")
    private fun request(url: String, body: String? = null, token: String? = null,
                        contentType: String = "application/json"): JsonObject {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            if (connection.responseCode !in 200..299) throw HttpFailure(connection.responseCode)
            return connection.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        } finally { connection.disconnect() }
    }
    private fun authenticate(): Pair<String, String> {
        val refresh = preferences.getString("refresh_token", null)
        if (refresh != null) {
            // Do not silently create a new identity to bypass the cooldown if a refresh fails.
            val response = request("https://securetoken.googleapis.com/v1/token?key=$apiKey",
                "grant_type=refresh_token&refresh_token=${java.net.URLEncoder.encode(refresh, "UTF-8")}",
                contentType = "application/x-www-form-urlencoded")
            preferences.edit().putString("refresh_token", response.getValue("refresh_token").jsonPrimitive.content).apply()
            return response.getValue("user_id").jsonPrimitive.content to response.getValue("id_token").jsonPrimitive.content
        }
        val response = request("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey", "{\"returnSecureToken\":true}")
        preferences.edit().putString("refresh_token", response.getValue("refreshToken").jsonPrimitive.content).apply()
        return response.getValue("localId").jsonPrimitive.content to response.getValue("idToken").jsonPrimitive.content
    }
    suspend fun submit(participantId: String, csv: ByteArray, fingerprint: String): String = withContext(Dispatchers.IO) {
        check(configured) { "Study submissions are not configured. Journal retained on device." }
        StudyUploadPolicy.validate(csv)
        check(cooldownMs() == 0L) { "Please wait 15 minutes between successful submissions." }
        val (uid, token) = authenticate()
        val base = "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents"
        val parent = "projects/$project/databases/(default)/documents/intentionalStudyParticipants/$uid"
        // Read the authoritative limit. An update-time precondition prevents racing submissions.
        val limit = try { request("$base/intentionalStudyParticipants/$uid", token = token) }
            catch (error: HttpFailure) { if (error.status == 404) null else throw error }
        val id = "latest" // One private, replaceable CSV per installation; no duplicate snapshots.
        fun timestampTransform(field: String) = buildJsonObject {
            put("fieldPath", field); put("setToServerValue", "REQUEST_TIME")
        }
        val body = buildJsonObject { putJsonArray("writes") {
            add(buildJsonObject {
                putJsonObject("update") {
                    put("name", "$parent/intentionalStudyExports/$id")
                    putJsonObject("fields") {
                        putJsonObject("participantId") { put("stringValue", participantId) }
                        putJsonObject("schemaVersion") { put("integerValue", "1") }
                        putJsonObject("contentType") { put("stringValue", "text/csv") }
                        putJsonObject("csv") { put("bytesValue", Base64.encodeToString(csv, Base64.NO_WRAP)) }
                        putJsonObject("sha256") { put("stringValue", MessageDigest.getInstance("SHA-256").digest(csv).joinToString("") { "%02x".format(it) }) }
                    }
                }
                putJsonArray("updateTransforms") { add(timestampTransform("uploadedAt")) }
            })
            add(buildJsonObject {
                putJsonObject("update") {
                    put("name", parent)
                    putJsonObject("fields") {
                        putJsonObject("participantId") { put("stringValue", participantId) }
                        putJsonObject("lastSubmissionId") { put("stringValue", id) }
                    }
                }
                putJsonObject("currentDocument") {
                    if (limit == null) put("exists", false)
                    else put("updateTime", limit.getValue("updateTime").jsonPrimitive.content)
                }
                putJsonArray("updateTransforms") { add(timestampTransform("lastSubmittedAt")) }
            })
        } }.toString()
        try {
            request("$base:commit", body, token)
        } catch (error: HttpFailure) {
            if (error.status in listOf(403, 409)) {
                val latest = runCatching { request("$base/intentionalStudyParticipants/$uid", token = token) }.getOrNull()
                val stamp = latest?.get("fields")?.jsonObject?.get("lastSubmittedAt")?.jsonObject?.get("timestampValue")?.jsonPrimitive?.content
                if (stamp != null && Instant.parse(stamp).toEpochMilli() > System.currentTimeMillis() - StudyUploadPolicy.INTERVAL_MS) {
                    preferences.edit().putLong("last_submission", System.currentTimeMillis()).apply()
                    error("The server limits submissions to one every 15 minutes. Please try later.")
                }
            }
            throw error
        }
        // Acknowledge exactly the captured snapshot, not changes made during the request.
        // Persist inside the IO block even if a stopped Android job cancels the caller.
        val receivedAt = System.currentTimeMillis()
        preferences.edit().putLong("last_submission", receivedAt).putLong("last_successful_at", receivedAt)
            .putString("last_fingerprint", fingerprint).apply()
        "Study CSV updated successfully. Your local journal has been kept."
    }
}
