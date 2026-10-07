package org.intentional.shared

import kotlinx.serialization.json.*

/** Explicit, versioned JSON schema shared by persistence and user-initiated export. */
object JournalCodec {
    private fun session(value: Session): JsonObject = buildJsonObject {
        put("id", value.id); put("startedAt", value.startedAt); put("demo", value.demo)
        put("app", value.app.name)
        put("openingId", value.openingId?.let(::JsonPrimitive) ?: JsonNull)
        put("timingMethod", value.timingMethod)
        put("pausedAt", value.pausedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("pausedWallAt", value.pausedWallAt?.let(::JsonPrimitive) ?: JsonNull)
        put("pausedRemainingMs", value.pausedRemainingMs)
        put("lastObservedAt", value.lastObservedAt); put("lastObservedWallAt", value.lastObservedWallAt)
        put("finishReason", value.finishReason?.let(::JsonPrimitive) ?: JsonNull)
        put("intention", value.intention); put("purpose", value.purpose.name); put("before", value.before)
        put("plannedMinutes", value.plannedMinutes); put("deadline", value.deadline)
        put("segmentStarted", value.segmentStarted); put("elapsedMs", value.elapsedMs); put("warned", value.warned)
        put("completed", value.completed?.let(::JsonPrimitive) ?: JsonNull)
        put("after", value.after?.let(::JsonPrimitive) ?: JsonNull)
        put("endedAt", value.endedAt?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("extensions") { value.extensions.forEach { extension -> add(buildJsonObject {
            put("reason", extension.reason); put("minutes", extension.minutes)
        }) } }
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun readSession(o: JsonObject) = Session(
        id = o.long("id"), startedAt = o.long("startedAt"), demo = o.bool("demo"),
        app = o["app"]?.jsonPrimitive?.content?.let(ProtectedApp::valueOf) ?: ProtectedApp.INSTAGRAM,
        openingId = o["openingId"]?.jsonPrimitive?.longOrNull,
        timingMethod = o["timingMethod"]?.jsonPrimitive?.content ?: "legacy_capped_elapsed",
        pausedAt = o["pausedAt"]?.jsonPrimitive?.longOrNull,
        pausedWallAt = o["pausedWallAt"]?.jsonPrimitive?.longOrNull,
        pausedRemainingMs = o["pausedRemainingMs"]?.jsonPrimitive?.longOrNull ?: 0,
        lastObservedAt = o["lastObservedAt"]?.jsonPrimitive?.longOrNull ?: o.long("segmentStarted"),
        lastObservedWallAt = o["lastObservedWallAt"]?.jsonPrimitive?.longOrNull ?: o.long("startedAt"),
        finishReason = o["finishReason"]?.jsonPrimitive?.contentOrNull,
        intention = o.text("intention"), purpose = Purpose.valueOf(o.text("purpose")), before = o.int("before"),
        plannedMinutes = o.int("plannedMinutes"), deadline = o.long("deadline"), segmentStarted = o.long("segmentStarted"),
        elapsedMs = o.long("elapsedMs"), warned = o.bool("warned"),
        completed = o["completed"]?.jsonPrimitive?.booleanOrNull,
        after = o["after"]?.jsonPrimitive?.intOrNull, endedAt = o["endedAt"]?.jsonPrimitive?.longOrNull,
        extensions = o.getValue("extensions").jsonArray.map { val e = it.jsonObject; Extension(e.text("reason"), e.int("minutes")) },
    )
    fun export(history: List<Session>): String = buildJsonObject {
        put("schemaVersion", 1)
        put("timing", "elapsed session time, not measured foreground screen time")
        putJsonArray("sessions") { history.forEach { add(session(it)) } }
    }.toString()
    fun encode(s: Snapshot): String = buildJsonObject {
        put("schemaVersion", 1); put("stage", s.stage.name); put("demo", s.demo)
        put("app", s.app.name)
        put("participantId", s.participantId)
        put("trackingStartedAt", s.trackingStartedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("draftOpeningId", s.draftOpeningId?.let(::JsonPrimitive) ?: JsonNull)
        put("lastRecordId", s.lastRecordId)
        putJsonArray("openings") { s.openings.forEach { opening -> add(buildJsonObject {
            put("id", opening.id); put("at", opening.at); put("app", opening.app.name); put("source", opening.source)
            put("sessionId", opening.sessionId?.let(::JsonPrimitive) ?: JsonNull)
            put("intention", opening.intention?.let(::JsonPrimitive) ?: JsonNull)
            put("before", opening.before?.let(::JsonPrimitive) ?: JsonNull)
            put("selectedMinutes", opening.selectedMinutes?.let(::JsonPrimitive) ?: JsonNull)
        }) } }
        put("session", s.session?.let(::session) ?: JsonNull)
        putJsonArray("history") { s.history.forEach { add(session(it)) } }
        putJsonArray("suspended") { s.suspended.forEach { add(session(it)) } }
        put("intention", s.intention); put("purpose", s.purpose.name); put("before", s.before); put("minutes", s.minutes)
        put("extensionReason", s.extensionReason); put("extensionMinutes", s.extensionMinutes); put("after", s.after)
    }.toString()
    fun decode(text: String): Snapshot {
        val o = Json.parseToJsonElement(text).jsonObject
        require(o.int("schemaVersion") == 1) { "Unsupported journal schema" }
        return Snapshot(
            stage = Stage.valueOf(o.text("stage")), session = o["session"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::readSession),
            app = o["app"]?.jsonPrimitive?.content?.let(ProtectedApp::valueOf) ?: ProtectedApp.INSTAGRAM,
            participantId = o["participantId"]?.jsonPrimitive?.content ?: "",
            trackingStartedAt = o["trackingStartedAt"]?.jsonPrimitive?.longOrNull,
            draftOpeningId = o["draftOpeningId"]?.jsonPrimitive?.longOrNull,
            lastRecordId = o["lastRecordId"]?.jsonPrimitive?.longOrNull ?: 0,
            openings = o["openings"]?.jsonArray?.map { element ->
                val e = element.jsonObject
                Opening(e.long("id"), e.long("at"), ProtectedApp.valueOf(e.text("app")), e.text("source"),
                    e["sessionId"]?.jsonPrimitive?.longOrNull, e["intention"]?.jsonPrimitive?.contentOrNull,
                    e["before"]?.jsonPrimitive?.intOrNull, e["selectedMinutes"]?.jsonPrimitive?.intOrNull)
            } ?: emptyList(),
            history = o.getValue("history").jsonArray.map { readSession(it.jsonObject) }, demo = o.bool("demo"),
            suspended = o["suspended"]?.jsonArray?.map { readSession(it.jsonObject) } ?: emptyList(),
            intention = o.text("intention"), purpose = Purpose.valueOf(o.text("purpose")), before = o.int("before"), minutes = o.int("minutes"),
            extensionReason = o.text("extensionReason"), extensionMinutes = o.int("extensionMinutes"), after = o.int("after"),
        )
    }
}
