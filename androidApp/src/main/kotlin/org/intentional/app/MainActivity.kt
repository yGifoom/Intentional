package org.intentional.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.intentional.shared.*
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity(), AppActions {
    private val engine get() = (application as IntentionalApplication).engine
    private var device by mutableStateOf(DeviceStatus())
    private var speechCallback: ((String) -> Unit)? = null
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) speechCallback?.invoke(text)
        else device = device.copy(message = "No words captured. Try again or type your intention.")
        speechCallback = null
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) device = device.copy(message = "Notifications are off. The on-screen reminder still works while Instagram or YouTube is open.")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    private val export: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                val text = JournalCodec.export(engine.current.history)
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("No output stream")
            }.onSuccess { device = device.copy(message = "Session journal exported.") }
                .onFailure { device = device.copy(message = "Export didn’t finish. Please try another location.") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        device = device.copy(message = (application as IntentionalApplication).recoveryMessage)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (engine.current.stage == Stage.HOME) leave() else engine.back()
            }
        })
        setContent { IntentionalApp(engine, device, this) }
    }
    override fun onResume() {
        super.onResume()
        engine.foregroundChanged(null)
        val manager = getSystemService(AccessibilityManager::class.java)
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == ComponentName(this, InstagramAccessibilityService::class.java)
        }
        device = device.copy(protection = enabled, installedApps = ProtectedApp.entries.filter {
            packageManager.getLaunchIntentForPackage(it.androidPackage) != null
        }.toSet())
        engine.tick()
    }
    override fun enableProtection() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    override fun speak(onResult: (String) -> Unit) {
        val requestedStage = engine.current.stage
        speechCallback = { if (engine.current.stage == requestedStage) onResult(it) }
        try {
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "What are you opening ${engine.current.app.label} to do?")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: ActivityNotFoundException) {
            speechCallback = null
            device = device.copy(message = "No speech provider is installed. Please type your intention.")
        }
    }
    override fun openApp(app: ProtectedApp): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(app.androidPackage)
        if (launch == null) {
            device = device.copy(installedApps = device.installedApps - app, message = "${app.label} isn’t installed. Try a demo from Your space.")
            return false
        }
        engine.expectManagedLaunch(app)
        return runCatching { startActivity(launch); true }.getOrElse {
            engine.cancelManagedLaunch()
            device = device.copy(message = "Couldn’t open ${app.label}. Please try again."); false
        }
    }
    override fun leave() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
    override fun exportHistory() { export.launch("intentional-sessions.json") }
    override fun shareStudyData() {
        val snapshot = engine.exportSnapshot()
        val exportedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(cacheDir, "study_exports").apply { mkdirs() }
                    File.createTempFile("intentional-${snapshot.participantId.take(8)}-$exportedAt-", ".csv", directory).apply {
                        writeText("\uFEFF" + StudyCsv.encode(snapshot, exportedAt), Charsets.UTF_8)
                    }
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.studyexports", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Intentional study data")
                    clipData = ClipData.newRawUri("Study CSV", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share study data"))
            }.onFailure { device = device.copy(message = "Couldn’t share the study CSV. Please try again or install an app that accepts CSV attachments.") }
        }
    }
    @Suppress("DEPRECATION") // Launches Android's confirmation UI on our supported API levels.
    override fun uninstallApp() {
        val ownPackage = Uri.fromParts("package", packageName, null)
        // Never clear data or disable check-ins before the user confirms with Android.
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, ownPackage))
        }.isSuccess
        if (!opened) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ownPackage))
            }.onSuccess {
                device = device.copy(message = "Tap Uninstall on Android’s App info screen to remove Intentional.")
            }.onFailure {
                device = device.copy(message = "To uninstall, press and hold the Intentional app icon, then choose App info → Uninstall.")
            }
        }
    }
}
