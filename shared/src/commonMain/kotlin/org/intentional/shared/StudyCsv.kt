package org.intentional.shared

/** One file, explicit record types. Empty cells mean unobserved, never zero by default. */
object StudyCsv {
    val columns = listOf("schema_version", "participant_id", "exported_at_utc_ms", "tracking_started_at_utc_ms",
        "record_type", "record_id", "app", "is_demo", "opening_source", "opening_id", "session_id", "status",
        "opened_at_utc_ms", "started_at_utc_ms", "ended_at_utc_ms", "selected_minutes", "relaxation_before",
        "motivation", "purpose", "tracked_elapsed_seconds", "relaxation_after", "task_completed",
        "extension_count", "extension_minutes", "extension_reason", "timing_method")

    // CSV quoting alone does not stop spreadsheet formula interpretation.
    fun cell(value: Any?): String {
        var text = value?.toString() ?: ""
        if (value is String && (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || text.firstOrNull() in listOf('\t', '\r', '\n'))) text = "'$text"
        return "\"${text.replace("\"", "\"\"")}\""
    }
    fun encode(snapshot: Snapshot, exportedAt: Long): String = buildString {
        append(columns.joinToString(",", transform = ::cell)); append("\r\n")
        fun row(type: String, id: Any, values: Map<String, Any?> = emptyMap()) {
            val fields = mapOf("schema_version" to 1, "participant_id" to snapshot.participantId,
                "exported_at_utc_ms" to exportedAt, "tracking_started_at_utc_ms" to snapshot.trackingStartedAt,
                "record_type" to type, "record_id" to id) + values
            append(columns.joinToString(",") { cell(fields[it]) }); append("\r\n")
        }
        row("metadata", "study")
        snapshot.openings.forEach { opening ->
            row("opening", opening.id, mapOf("app" to opening.app.label, "is_demo" to false,
                "opening_source" to opening.source, "opening_id" to opening.id, "session_id" to opening.sessionId,
                "opened_at_utc_ms" to opening.at, "selected_minutes" to opening.selectedMinutes,
                "relaxation_before" to opening.before, "motivation" to opening.intention))
        }
        (snapshot.history + listOfNotNull(snapshot.session) + snapshot.suspended).forEach { session ->
            val timedOut = session.finishReason == "inactivity_timeout"
            row("session", session.id, mapOf("app" to session.app.label, "is_demo" to session.demo,
                "opening_id" to session.openingId, "session_id" to session.id,
                "status" to when {
                    timedOut -> "inactivity_timeout"
                    session.endedAt != null -> "completed"
                    session in snapshot.suspended -> "paused"
                    else -> snapshot.stage.name.lowercase()
                },
                "started_at_utc_ms" to session.startedAt, "ended_at_utc_ms" to session.endedAt,
                "selected_minutes" to session.plannedMinutes, "relaxation_before" to session.before,
                "motivation" to session.intention, "purpose" to session.purpose.label,
                "tracked_elapsed_seconds" to session.elapsedMs / 1000.0, "relaxation_after" to if (timedOut) "N/A" else session.after,
                "task_completed" to if (timedOut) "N/A" else session.completed, "extension_count" to session.extensions.size,
                "extension_minutes" to session.extensions.sumOf { it.minutes }, "timing_method" to session.timingMethod))
            session.extensions.forEachIndexed { index, extension ->
                row("extension", "${session.id}-${index + 1}", mapOf("app" to session.app.label,
                    "is_demo" to session.demo, "session_id" to session.id,
                    "extension_minutes" to extension.minutes, "extension_reason" to extension.reason))
            }
        }
    }
}
