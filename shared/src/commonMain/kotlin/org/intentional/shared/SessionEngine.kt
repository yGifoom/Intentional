package org.intentional.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Stage { HOME, GATE, INTENTION, DURATION, ACTIVE, PAUSED, EXPIRED, EXTEND, REFLECT, SAVED }
enum class ProtectedApp(val label: String, val androidPackage: String) {
    INSTAGRAM("Instagram", "com.instagram.android"),
    YOUTUBE("YouTube", "com.google.android.youtube");
    companion object {
        fun fromPackage(value: String) = entries.firstOrNull { it.androidPackage == value }
    }
}
enum class Purpose(val label: String) { RELAX("Relax"), CONNECT("Connect"), LEARN("Explore"), OTHER("Something else") }
data class Extension(val reason: String, val minutes: Int)
data class Opening(
    val id: Long, val at: Long, val app: ProtectedApp, val source: String,
    val sessionId: Long? = null, val intention: String? = null,
    val before: Int? = null, val selectedMinutes: Int? = null,
)
data class Session(
    val id: Long,
    val startedAt: Long,
    val demo: Boolean,
    val intention: String,
    val purpose: Purpose,
    val before: Int,
    val plannedMinutes: Int,
    val deadline: Long,
    val segmentStarted: Long,
    val elapsedMs: Long = 0,
    val extensions: List<Extension> = emptyList(),
    val warned: Boolean = false,
    val completed: Boolean? = null,
    val after: Int? = null,
    val endedAt: Long? = null,
    val app: ProtectedApp = ProtectedApp.INSTAGRAM,
    val openingId: Long? = null,
    val timingMethod: String = "legacy_capped_elapsed",
    val pausedAt: Long? = null,
    val pausedWallAt: Long? = null,
    val pausedRemainingMs: Long = 0,
    val lastObservedAt: Long = segmentStarted,
    val lastObservedWallAt: Long = startedAt,
    val finishReason: String? = null,
)
data class Snapshot(
    val stage: Stage = Stage.HOME,
    val session: Session? = null,
    val history: List<Session> = emptyList(),
    val demo: Boolean = false,
    val intention: String = "",
    val purpose: Purpose = Purpose.RELAX,
    val before: Int = 3,
    val minutes: Int = 15,
    val extensionReason: String = "",
    val extensionMinutes: Int = 5,
    val after: Int = 3,
    val app: ProtectedApp = ProtectedApp.INSTAGRAM,
    val participantId: String = "",
    val trackingStartedAt: Long? = null,
    val openings: List<Opening> = emptyList(),
    val draftOpeningId: Long? = null,
    val lastRecordId: Long = 0,
    val suspended: List<Session> = emptyList(),
)
enum class TimerEvent { NONE, WARNING, EXPIRED, INACTIVITY }

/** All mutations run on the UI thread. Clock is monotonic; wall time is only for records. */
class SessionEngine(
    private val now: () -> Long,
    private val wallTime: () -> Long,
    initial: Snapshot = Snapshot(),
    private val persist: (Snapshot) -> Unit = {},
) {
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()
    val current get() = mutable.value
    private fun update(value: Snapshot) { mutable.value = value; persist(value) }
    private fun fresh(stage: Stage = Stage.HOME, demo: Boolean = false, app: ProtectedApp = ProtectedApp.INSTAGRAM) = Snapshot(
        stage = stage, demo = demo, app = app, history = current.history,
        participantId = current.participantId, trackingStartedAt = current.trackingStartedAt,
        openings = current.openings, lastRecordId = current.lastRecordId,
        suspended = current.suspended,
    )
    private fun nextId() = maxOf(wallTime(), current.lastRecordId + 1, (current.history.maxOfOrNull { it.id } ?: 0) + 1)
    private var expectedLaunch: Pair<ProtectedApp, Long>? = null
    fun expectManagedLaunch(app: ProtectedApp) { expectedLaunch = app to now() }
    fun cancelManagedLaunch() { expectedLaunch = null }
    fun recordOpening(app: ProtectedApp, source: String = "foreground_entry") {
        val expected = expectedLaunch
        expectedLaunch = null
        if (source == "foreground_entry" && expected?.first == app && now() - expected.second in 0..5000) return
        if (current.demo && current.stage !in listOf(Stage.HOME, Stage.SAVED)) return
        val id = nextId()
        val opening = Opening(id, wallTime(), app, source,
            current.session?.takeIf { it.app == app }?.id ?: current.suspended.firstOrNull { it.app == app }?.id)
        update(current.copy(openings = current.openings + opening, lastRecordId = id))
    }
    private fun draftOpening(transform: (Opening) -> Opening) {
        update(current.copy(openings = current.openings.map { if (it.id == current.draftOpeningId) transform(it) else it }))
    }
    fun open(demo: Boolean, app: ProtectedApp = ProtectedApp.INSTAGRAM, recordAttempt: Boolean = true) {
        tick()
        if (current.session != null || current.stage !in listOf(Stage.HOME, Stage.SAVED)) return
        if (!demo && recordAttempt) recordOpening(app, "intentional_launcher")
        if (!demo && restorePaused(app)) return
        val openingId = if (demo) null else current.openings.lastOrNull { it.app == app }?.id
        update(fresh(Stage.GATE, demo, app).copy(draftOpeningId = openingId))
    }
    private fun restorePaused(app: ProtectedApp): Boolean {
        val session = current.suspended.firstOrNull { it.app == app } ?: return false
        update(fresh(Stage.PAUSED, app = app).copy(session = session, intention = session.intention,
            before = session.before, purpose = session.purpose, minutes = session.plannedMinutes,
            suspended = current.suspended.filterNot { it.id == session.id }))
        return true
    }
    /** Each moderated app retains its own paused interaction for up to five minutes. */
    fun visit(app: ProtectedApp): Boolean {
        if (current.demo && current.stage !in listOf(Stage.HOME, Stage.SAVED)) return false
        tick()
        var session = current.session
        if (session != null && session.app != app) {
            pause()
            if (current.stage == Stage.PAUSED) {
                update(fresh().copy(suspended = current.suspended + current.session!!))
                session = null
            } else {
                reflect()
                return true
            }
        }
        if (session == null && !restorePaused(app)) {
            if (current.app != app && current.stage in listOf(Stage.GATE, Stage.INTENTION, Stage.DURATION)) {
                update(fresh(Stage.GATE, app = app).copy(draftOpeningId = current.openings.lastOrNull { it.app == app }?.id))
            } else open(false, app, recordAttempt = false)
        }
        if (current.stage == Stage.PAUSED && current.session?.app == app) resume()
        return current.stage != Stage.ACTIVE
    }
    fun acceptGate() { if (current.stage == Stage.GATE) update(current.copy(stage = Stage.INTENTION)) }
    fun intention(text: String) {
        update(current.copy(intention = text.take(500)))
        draftOpening { it.copy(intention = current.intention) }
    }
    fun purpose(value: Purpose) { update(current.copy(purpose = value)) }
    fun before(value: Int) {
        update(current.copy(before = value.coerceIn(1, 5)))
        draftOpening { it.copy(before = current.before) }
    }
    fun after(value: Int) { update(current.copy(after = value.coerceIn(1, 5))) }
    fun duration(value: Int) {
        update(current.copy(minutes = value.coerceIn(1, 120)))
        draftOpening { it.copy(selectedMinutes = current.minutes) }
    }
    fun extensionReason(value: String) { update(current.copy(extensionReason = value.take(500))) }
    fun extensionDuration(value: Int) { update(current.copy(extensionMinutes = value.coerceIn(1, 120))) }
    fun chooseDuration() {
        if (current.stage == Stage.INTENTION && current.intention.isNotBlank()) {
            update(current.copy(stage = Stage.DURATION))
            draftOpening { it.copy(before = current.before, selectedMinutes = current.minutes) }
        }
    }
    fun start(): Boolean {
        val s = current
        if (s.stage != Stage.DURATION || s.intention.isBlank()) return false
        val time = now()
        val duration = if (s.demo) 75_000L else s.minutes * 60_000L
        val id = nextId()
        update(s.copy(stage = if (s.demo) Stage.ACTIVE else Stage.PAUSED, lastRecordId = id,
            openings = s.openings.map { if (it.id == s.draftOpeningId) it.copy(sessionId = id) else it }, session = Session(
            id = id, startedAt = wallTime(), demo = s.demo, intention = s.intention.trim(),
            purpose = s.purpose, before = s.before, plannedMinutes = s.minutes,
            deadline = time + duration, segmentStarted = time,
            app = s.app,
            openingId = s.draftOpeningId,
            timingMethod = if (s.demo) "demo_elapsed" else "observed_foreground",
            pausedAt = if (s.demo) null else time, pausedWallAt = if (s.demo) null else wallTime(),
            pausedRemainingMs = duration,
        )))
        return true
    }
    fun remainingMs(): Long = current.session?.let {
        if (current.stage == Stage.PAUSED) it.pausedRemainingMs else (it.deadline - now()).coerceAtLeast(0)
    } ?: 0
    fun inactivityRemainingMs(): Long = current.session?.pausedAt?.let {
        (INACTIVITY_MS - (now() - it)).coerceAtLeast(0)
    } ?: INACTIVITY_MS
    private fun stopSegment(s: Session): Session = s.copy(
        elapsedMs = s.elapsedMs + (now() - s.segmentStarted).coerceAtLeast(0),
        segmentStarted = now(), lastObservedAt = now(), lastObservedWallAt = wallTime(),
    )
    fun foregroundChanged(app: ProtectedApp?) {
        if (current.session?.demo == true) return
        tick()
        if (current.session?.app != app) pause()
        else if (current.stage == Stage.PAUSED) resume()
    }
    fun pause() {
        val session = current.session ?: return
        if (current.stage != Stage.ACTIVE || session.demo) return
        val remaining = remainingMs()
        update(current.copy(stage = Stage.PAUSED, session = stopSegment(session).copy(
            pausedAt = now(), pausedWallAt = wallTime(), pausedRemainingMs = remaining)))
    }
    private fun resume() {
        tick() // A return at or after five minutes starts a new interaction instead.
        val session = current.session ?: return
        if (current.stage != Stage.PAUSED) return
        update(current.copy(stage = Stage.ACTIVE, session = session.copy(
            deadline = now() + session.pausedRemainingMs, segmentStarted = now(),
            pausedAt = null, pausedWallAt = null, lastObservedAt = now(), lastObservedWallAt = wallTime())))
    }
    private fun inactive(session: Session) = session.pausedAt?.let { now() - it >= INACTIVITY_MS } == true
    private fun unattended(session: Session) = session.copy(completed = null, after = null,
        finishReason = "inactivity_timeout", endedAt = (session.pausedWallAt ?: wallTime()) + INACTIVITY_MS)
    /** Restore only confirmed usage; never count an unobserved process/service gap as usage. */
    fun recoverAfterRestart(bootChanged: Boolean) {
        fun recover(session: Session, wasActive: Boolean): Session {
            val pausedWall = if (wasActive) session.lastObservedWallAt else session.pausedWallAt ?: wallTime()
            val pausedTime = if (bootChanged) now() - (wallTime() - pausedWall).coerceAtLeast(0)
                else if (wasActive) session.lastObservedAt else session.pausedAt ?: now()
            return session.copy(pausedAt = pausedTime, pausedWallAt = pausedWall,
                pausedRemainingMs = if (wasActive) (session.deadline - session.lastObservedAt).coerceAtLeast(0) else session.pausedRemainingMs)
        }
        val active = current.stage == Stage.ACTIVE && current.session?.demo == false
        update(current.copy(stage = if (active) Stage.PAUSED else current.stage,
            session = current.session?.let { if (active || current.stage == Stage.PAUSED) recover(it, active) else it },
            suspended = current.suspended.map { recover(it, false) }))
        tick()
    }
    /** Export an ongoing session without mutating it or inventing a post-session rating. */
    fun exportSnapshot(): Snapshot = current.copy(session = current.session?.let {
        if (current.stage == Stage.ACTIVE) stopSegment(it) else it
    })
    fun tick(): TimerEvent {
        val timedOut = current.suspended.filter(::inactive)
        if (timedOut.isNotEmpty()) update(current.copy(history = timedOut.map(::unattended) + current.history,
            suspended = current.suspended.filterNot(::inactive)))
        val s = current.session ?: return TimerEvent.NONE
        if (current.stage == Stage.PAUSED) {
            if (inactive(s)) {
                update(fresh().copy(history = listOf(unattended(s)) + current.history))
                return TimerEvent.INACTIVITY
            }
            return TimerEvent.NONE
        }
        if (current.stage != Stage.ACTIVE) return TimerEvent.NONE
        // Monotonic time moving behind a segment means the device rebooted.
        if (remainingMs() == 0L || now() < s.segmentStarted) {
            update(current.copy(stage = Stage.EXPIRED, session = stopSegment(s)))
            return TimerEvent.EXPIRED
        }
        if (!s.warned && remainingMs() <= 60_000) {
            update(current.copy(session = stopSegment(s).copy(warned = true)))
            return TimerEvent.WARNING
        }
        // Bound data loss on process death to the uncheckpointed observation interval.
        if (!s.demo && now() - s.lastObservedAt >= 5000) update(current.copy(session = stopSegment(s)))
        return TimerEvent.NONE
    }
    fun requestExtension() {
        val s = current.session ?: return
        if (current.stage !in listOf(Stage.ACTIVE, Stage.PAUSED, Stage.EXPIRED, Stage.REFLECT)) return
        update(current.copy(stage = Stage.EXTEND, session = if (current.stage == Stage.ACTIVE) stopSegment(s) else s,
            extensionReason = "", extensionMinutes = 5))
    }
    fun extend(): Boolean {
        val s = current.session ?: return false
        if (current.stage != Stage.EXTEND || current.extensionReason.isBlank()) return false
        val time = now()
        update(current.copy(stage = if (s.demo) Stage.ACTIVE else Stage.PAUSED, session = s.copy(
            extensions = s.extensions + Extension(current.extensionReason.trim(), current.extensionMinutes),
            segmentStarted = time, deadline = time + if (s.demo) 75_000L else current.extensionMinutes * 60_000L,
            warned = false,
            pausedAt = if (s.demo) null else time, pausedWallAt = if (s.demo) null else wallTime(),
            pausedRemainingMs = if (s.demo) 75_000L else current.extensionMinutes * 60_000L,
            lastObservedAt = time, lastObservedWallAt = wallTime(),
        )))
        return true
    }
    fun reflect() {
        val s = current.session ?: return
        if (current.stage !in listOf(Stage.ACTIVE, Stage.PAUSED, Stage.EXPIRED, Stage.EXTEND)) return
        update(current.copy(stage = Stage.REFLECT, session = if (current.stage == Stage.ACTIVE) stopSegment(s) else s))
    }
    fun complete(success: Boolean) {
        val s = current.session ?: return
        if (current.stage != Stage.REFLECT) return
        val result = s.copy(completed = success, after = current.after, endedAt = wallTime(), finishReason = "completed")
        update(current.copy(stage = Stage.SAVED, session = null, history = listOf(result) + current.history))
    }
    fun home() {
        if (current.session == null) update(fresh())
    }
    fun back() {
        when (current.stage) {
            Stage.DURATION -> update(current.copy(stage = Stage.INTENTION))
            Stage.INTENTION -> update(current.copy(stage = Stage.GATE))
            Stage.GATE, Stage.SAVED -> home()
            Stage.ACTIVE, Stage.PAUSED, Stage.EXTEND -> reflect()
            else -> Unit
        }
    }
    fun demoJump(toWarning: Boolean) {
        val s = current.session ?: return
        if (!s.demo || current.stage != Stage.ACTIVE) return
        update(current.copy(session = s.copy(deadline = now() + if (toWarning) 60_000 else 0)))
    }
    fun clearHistory() { update(current.copy(history = emptyList(), openings = emptyList(), draftOpeningId = null, trackingStartedAt = wallTime())) }
    companion object {
        const val INACTIVITY_MS = 5 * 60_000L
        fun decode(text: String): Snapshot = JournalCodec.decode(text)
        fun encode(value: Snapshot): String = JournalCodec.encode(value)
    }
}
