package org.intentional.app

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import org.intentional.shared.SessionEngine
import org.intentional.shared.Snapshot
import java.util.UUID

class IntentionalApplication : Application() {
    lateinit var engine: SessionEngine
        private set
    var recoveryMessage: String? = null
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("session_journal", MODE_PRIVATE)
        var snapshot = runCatching {
            prefs.getString("snapshot", null)?.let(SessionEngine::decode) ?: Snapshot()
        }.getOrElse {
            recoveryMessage = "The previous journal could not be read. A fresh session is available."
            Snapshot()
        }
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val participant = prefs.getString("participant_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("participant_id", it).apply()
        }
        snapshot = snapshot.copy(participantId = participant, trackingStartedAt = snapshot.trackingStartedAt ?: System.currentTimeMillis())
        val bootChanged = prefs.getInt("boot", boot) != boot
        engine = SessionEngine(SystemClock::elapsedRealtime, System::currentTimeMillis, snapshot) {
            prefs.edit().putString("snapshot", SessionEngine.encode(it)).putInt("boot", boot).apply()
        }
        engine.recoverAfterRestart(bootChanged)
        prefs.edit().putString("snapshot", SessionEngine.encode(engine.current)).putInt("boot", boot).apply()
    }
}
