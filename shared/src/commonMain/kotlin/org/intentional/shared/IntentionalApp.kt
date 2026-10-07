package org.intentional.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Ink = Color(0xFF0D0F18)
private val PanelColor = Color(0xFF1A1C2C)
private val Violet = Color(0xFFB99AFF)
private val Muted = Color(0xFFA5A5BA)
private val Mint = Color(0xFF9BD9C4)

data class DeviceStatus(val protection: Boolean = false, val installedApps: Set<ProtectedApp> = emptySet(), val message: String? = null)
interface AppActions {
    fun enableProtection()
    fun speak(onResult: (String) -> Unit)
    fun openApp(app: ProtectedApp): Boolean
    fun leave()
    fun exportHistory()
    fun shareStudyData()
    fun uninstallApp()
}

@Composable
fun IntentionalApp(engine: SessionEngine, device: DeviceStatus, actions: AppActions) {
    val s by engine.state.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var selectedApp by remember { mutableStateOf(ProtectedApp.INSTAGRAM) }
    var disclosure by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var uninstallDialog by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(engine.remainingMs()) }
    var inactivityRemaining by remember { mutableStateOf(engine.inactivityRemainingMs()) }
    LaunchedEffect(s.stage, s.session?.deadline) {
        while (s.session != null || s.suspended.isNotEmpty()) {
            engine.tick()
            remaining = engine.remainingMs()
            inactivityRemaining = engine.inactivityRemainingMs()
            delay(250)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Violet, onPrimary = Ink, background = Ink, surface = PanelColor,
        onSurface = Color(0xFFF5F2FC), onBackground = Color(0xFFF5F2FC), outline = Color(0xFF44405B),
    )) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF191329), Ink, Ink)))) {
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 540.dp).fillMaxSize()
                .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("✦", color = Violet, fontSize = 30.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("intentional", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        Text(if (s.demo && s.stage != Stage.HOME) "DEMO SESSION" else "A LITTLE PAUSE. A CLEARER PURPOSE.", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                    if (s.stage !in listOf(Stage.HOME, Stage.EXPIRED, Stage.REFLECT)) TextButton(onClick = { engine.back() }) { Text("Back") }
                }
                device.message?.let { Panel { Text(it, color = Mint, fontSize = 13.sp) } }
                if (!s.demo && s.stage !in listOf(Stage.HOME, Stage.SAVED) && !device.protection) {
                    Panel {
                        Text("Opening check-ins are off. Enable them to interrupt Instagram and YouTube and show reminders.", color = Violet)
                        TextButton({ disclosure = true }) { Text("Enable check-ins") }
                    }
                }
                when (s.stage) {
                    Stage.HOME -> {
                        Spacer(Modifier.height(12.dp))
                        Heading("Make room for", "what you came for.")
                        Body("A small pause before Instagram or YouTube. A moment to check in after.")
                        Secondary("Export study data (CSV)") { actions.shareStudyData() }
                        Caption("Includes your written intentions and ratings. Choose an app and recipient to share with.")
                        if (s.openings.isNotEmpty()) Caption(ProtectedApp.entries.joinToString(" · ") { app ->
                            "${app.label}: ${s.openings.count { it.app == app }} opening attempts"
                        })
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(tab == 0, { tab = 0 }, label = { Text("Your space") })
                            FilterChip(tab == 1, { tab = 1 }, label = { Text("Your patterns") })
                        }
                        if (tab == 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProtectedApp.entries.forEach { app ->
                                    FilterChip(selectedApp == app, { selectedApp = app }, label = { Text(app.label) })
                                }
                            }
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppMark(selectedApp)
                                    Spacer(Modifier.width(14.dp))
                                    Column { Text(selectedApp.label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                        Text(if (device.protection) "Opening check-ins enabled" else "Opening check-ins are off", color = if (device.protection) Mint else Muted, fontSize = 13.sp) }
                                }
                                HorizontalDivider(color = Color.White.copy(alpha = .08f))
                                Text("Set an intention → Choose your time → Check in", color = Muted, fontSize = 13.sp)
                            }
                            Primary("Open ${selectedApp.label} intentionally", enabled = selectedApp in device.installedApps && device.protection) { engine.open(false, selectedApp) }
                            if (!device.protection) Secondary("Set up opening check-ins") { disclosure = true }
                            if (selectedApp !in device.installedApps) Caption("${selectedApp.label} is not installed. You can still try the demo.")
                            Secondary("Try the 75-second demo") { engine.open(true, selectedApp) }
                            Panel {
                                Text("Did scrolling give you what you needed?", color = Violet, fontWeight = FontWeight.SemiBold)
                                Text("Capture why you opened the app and how you felt afterward. Your patterns start with your own answers.", color = Muted, fontSize = 14.sp)
                            }
                            Caption("Your session journal stays on this device.\nYou’re in control, every step of the way.")
                        } else History(s.history, actions, { deleteDialog = true }, s.openings.isNotEmpty())
                    }
                    Stage.GATE -> {
                        Hero("pause")
                        Heading("You’re about to open", "${s.app.label}.")
                        Body("Is this intentional? Take a breath and notice what brought you here.")
                        Spacer(Modifier.height(12.dp))
                        Primary("Yes, I have something in mind") { engine.acceptGate() }
                        Secondary("Not really — I’ll step away") { engine.home(); actions.leave() }
                        Caption("There’s no right or wrong answer.")
                    }
                    Stage.INTENTION -> {
                        Step(1)
                        Heading("What are you opening", "${s.app.label} to do?")
                        Body("Give this session a purpose. Say it out loud or write a few words.")
                        VoiceButton { actions.speak(engine::intention) }
                        OutlinedTextField(s.intention, engine::intention, Modifier.fillMaxWidth(),
                            label = { Text("I’m here to…") }, placeholder = { Text("Unwind after a busy day") },
                            minLines = 2, maxLines = 5, shape = RoundedCornerShape(20.dp))
                        Column(Modifier.fillMaxWidth()) {
                            Text("What kind of moment is this?", color = Muted, fontSize = 13.sp)
                            Purpose.entries.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { purpose -> FilterChip(s.purpose == purpose, { engine.purpose(purpose) }, label = { Text(purpose.label) }) }
                                }
                            }
                        }
                        Rating("How relaxed do you feel right now?", s.before, engine::before)
                        Primary("Choose my time  →", s.intention.isNotBlank()) { engine.chooseDuration() }
                        Caption("Voice uses your phone’s speech provider, which may process audio online. You can always type.")
                    }
                    Stage.DURATION -> {
                        Step(2)
                        Hero("clock")
                        Heading("A little time.", "A clear intention.")
                        Intention(s.intention, s.app)
                        Body("How long would you like this session to be?")
                        DurationPicker(s.minutes, engine::duration)
                        Caption(if (s.demo) "Demo: the timer runs for 75 seconds.\nYour selected duration is only a preview." else "We’ll remind you with 1 minute left, then pause for a check-in.")
                        Primary(if (s.demo) "Start demo session" else "Set ${s.minutes} min & open ${s.app.label}", enabled = s.demo || device.protection) {
                            if (s.demo || (device.protection && actions.openApp(s.app))) engine.start()
                        }
                    }
                    Stage.ACTIVE -> {
                        Spacer(Modifier.height(16.dp))
                        Caption(if (s.demo) "DEMO • NO ${s.app.label.uppercase()} REQUIRED" else "YOUR ${s.app.label.uppercase()} SESSION")
                        TimerFace(remaining, s.session!!)
                        Heading(if (remaining <= 60_000) "One minute left." else "Stay with", if (remaining <= 60_000) "Still on track?" else "your intention.")
                        Intention(s.session!!.intention, s.app)
                        if (remaining <= 60_000) Panel { Text("Wrap up what you came to do. We’ll check in when your time is up.", color = Violet) }
                        if (!s.demo) Primary("Back to ${s.app.label}") { actions.openApp(s.app) }
                        Secondary("I’m done — check in") { engine.reflect() }
                        TextButton(onClick = { engine.requestExtension() }) { Text("I need more time") }
                        if (s.demo) {
                            Panel {
                                Text("Demo controls", color = Muted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TextButton({ engine.demoJump(true) }) { Text("1-minute reminder") }
                                    TextButton({ engine.demoJump(false) }) { Text("Time’s up") }
                                }
                            }
                        }
                        Caption(if (s.demo) "Demo time runs here in Intentional." else "Only time in ${s.app.label} counts. Leaving pauses this timer.")
                    }
                    Stage.PAUSED -> {
                        Hero("pause")
                        Heading("Away from ${s.app.label}.", "Your timer is paused.")
                        TimerFace(remaining, s.session!!)
                        Intention(s.session!!.intention, s.app)
                        val seconds = (inactivityRemaining + 999) / 1000
                        Body("Return within ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} to resume. Otherwise, we’ll save your usage time with no outcome rating and reset the timer.")
                        Primary("Resume ${s.app.label}") { actions.openApp(s.app) }
                        Secondary("Finish and check in now") { engine.reflect() }
                    }
                    Stage.EXPIRED -> {
                        Hero("hourglass")
                        Heading("Your planned", "${s.app.label} time is up.")
                        Intention(s.session!!.intention, s.app)
                        Body("Did you finish what you came to do?")
                        OutcomeButtons(yes = { engine.reflect() }, no = { engine.requestExtension() })
                        Caption("You’re in control. Continuing starts with a new intention and a time limit.")
                    }
                    Stage.EXTEND -> {
                        Heading("A moment to pause.", "What’s still left to do?")
                        Intention(s.session!!.intention, s.app)
                        VoiceButton { actions.speak(engine::extensionReason) }
                        OutlinedTextField(s.extensionReason, engine::extensionReason, Modifier.fillMaxWidth(),
                            label = { Text("I’d like more time because…") }, minLines = 2, maxLines = 5, shape = RoundedCornerShape(20.dp))
                        DurationPicker(s.extensionMinutes, engine::extensionDuration)
                        Primary(if (s.demo) "Continue demo (+75 seconds)" else "Continue for ${s.extensionMinutes} minutes", s.extensionReason.isNotBlank() && (s.demo || device.protection)) {
                            if (s.demo || (device.protection && actions.openApp(s.app))) engine.extend()
                        }
                        Secondary("Actually, I’m ready to stop") { engine.reflect() }
                    }
                    Stage.REFLECT -> {
                        Hero("check")
                        Heading("Before you go…", "Did you get what you needed?")
                        Intention(s.session!!.intention, s.app)
                        Rating("How relaxed do you feel now?", s.after, engine::after)
                        Body("Was your original intention fulfilled?")
                        OutcomeButtons(yes = { engine.complete(true) }, no = { engine.complete(false) })
                        Caption("An honest “no” is useful too. This is reflection, not a score.")
                    }
                    Stage.SAVED -> {
                        Hero("check")
                        Heading("A little more aware.", "That’s a good place to stop.")
                        s.history.firstOrNull { it.app == s.app && it.completed != null }?.let { result ->
                            Panel {
                                Text(if (result.demo) "DEMO CHECK-IN SAVED" else "CHECK-IN SAVED", color = Mint, fontSize = 12.sp, letterSpacing = 1.sp)
                                Text(result.intention, fontSize = 20.sp)
                                Text(if (result.completed == true) "You got what you came for." else "This session didn’t quite meet your intention.", color = Muted)
                                Text("Relaxation: ${result.before}/5 → ${result.after}/5", color = Violet)
                            }
                        }
                        Primary("Close & take a breath") { engine.home(); actions.leave() }
                        Secondary("See my patterns") { engine.home(); tab = 1 }
                    }
                }
                if (s.stage != Stage.HOME) TextButton(onClick = { actions.shareStudyData() }) { Text("Export study data (CSV)") }
                TextButton(onClick = { uninstallDialog = true }) { Text("Uninstall Intentional", color = Muted) }
                Spacer(Modifier.height(8.dp))
            }
        }
        }
        if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Enable app check-ins") },
            text = { Text("Intentional uses Android Accessibility access to detect which app opens and show check-ins over Instagram and YouTube. It does not read your posts, messages, or screen content. Session intentions and ratings are stored on this device.\n\nIn Settings, choose Intentional and enable the service. You can turn it off there at any time.") },
            confirmButton = { TextButton({ disclosure = false; actions.enableProtection() }) { Text("Agree & open Settings") } },
            dismissButton = { TextButton({ disclosure = false }) { Text("Not now") } })
        if (uninstallDialog) AlertDialog(
            onDismissRequest = { uninstallDialog = false },
            title = { Text("Uninstall Intentional?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Uninstalling stops the check-ins and removes your journal and any current session from this device. Instagram and YouTube stay installed.\n\nAndroid will ask you to confirm. You can cancel without losing your data.")
                    if (s.history.isNotEmpty() || s.openings.isNotEmpty() || s.session != null) TextButton(onClick = {
                        uninstallDialog = false
                        actions.shareStudyData()
                    }) { Text("Export my journal first") }
                }
            },
            confirmButton = { TextButton({ uninstallDialog = false; actions.uninstallApp() }) { Text("Continue to uninstall") } },
            dismissButton = { TextButton({ uninstallDialog = false }) { Text("Keep the app") } },
        )
        if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Delete your session journal?") },
            text = { Text("This removes saved sessions and opening counts from this device. It cannot be undone. Export your study CSV first if you need these records.") },
            confirmButton = { TextButton({ engine.clearHistory(); deleteDialog = false }) { Text("Delete journal") } },
            dismissButton = { TextButton({ deleteDialog = false }) { Text("Keep it") } })
    }
}

@Composable private fun Heading(first: String, accent: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(first, fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(accent, fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold, color = Violet, textAlign = TextAlign.Center)
    }
}
@Composable private fun Body(text: String) { Text(text, color = Muted, fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center) }
@Composable private fun Caption(text: String) { Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center) }
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = PanelColor.copy(alpha = .9f), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Violet.copy(alpha = .16f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun Primary(text: String, enabled: Boolean = true, action: () -> Unit) {
    Button(action, Modifier.fillMaxWidth().heightIn(min = 58.dp), enabled = enabled, shape = RoundedCornerShape(20.dp)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
@Composable private fun Secondary(text: String, action: () -> Unit) {
    OutlinedButton(action, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(20.dp)) { Text(text, fontSize = 15.sp, textAlign = TextAlign.Center) }
}
@Composable private fun OutcomeButtons(yes: () -> Unit, no: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf("Yes" to yes, "No" to no).forEach { (label, action) ->
            OutlinedButton(onClick = action, modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = PanelColor, contentColor = Color(0xFFF5F2FC)),
                border = BorderStroke(1.dp, Muted)) {
                Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
@Composable private fun Step(number: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { Box(Modifier.width(32.dp).height(3.dp).clip(CircleShape).background(if (it < number) Violet else PanelColor)) }
    }
}
@Composable private fun InstagramMark() {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Color(0xFF773BDD), Color(0xFFD73F98), Color(0xFFFFBB68)))), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(29.dp)) {
            drawRoundRect(Color.White, style = Stroke(2.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
            drawCircle(Color.White, size.width * .24f, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, 1.6.dp.toPx(), Offset(size.width * .78f, size.height * .22f))
        }
    }
}
@Composable private fun AppMark(app: ProtectedApp) {
    if (app == ProtectedApp.INSTAGRAM) InstagramMark()
    else Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFDD2424)), contentAlignment = Alignment.Center) {
        Text("▶", color = Color.White, fontSize = 25.sp)
    }
}
@Composable private fun Intention(text: String, app: ProtectedApp) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark(app); Spacer(Modifier.width(14.dp))
            Column { Text("${app.label.uppercase()} · YOUR INTENTION", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp)); Text(text, fontSize = 16.sp, lineHeight = 23.sp) }
        }
    }
}
@Composable private fun Rating(title: String, selected: Int, onChange: (Int) -> Unit) {
    Panel {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { value ->
                OutlinedButton({ onChange(value) }, Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { contentDescription = "Relaxation $value of 5" },
                    contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected == value) Violet else Color.Transparent,
                        contentColor = if (selected == value) Ink else Muted)) { Text("$value", fontWeight = FontWeight.Bold) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Not at all", color = Muted, fontSize = 11.sp); Text("Very relaxed", color = Muted, fontSize = 11.sp)
        }
    }
}
@Composable private fun DurationPicker(minutes: Int, change: (Int) -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ change(minutes - 1) }, enabled = minutes > 1, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("−", fontSize = 24.sp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$minutes min", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Violet)
                Text(if (minutes == 15) "DEFAULT SESSION" else "YOUR CHOICE", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            OutlinedButton({ change(minutes + 1) }, enabled = minutes < 120, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)) { Text("+", fontSize = 24.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(5, 10, 15, 30).forEach { FilterChip(minutes == it, { change(it) }, label = { Text("$it m") }) }
        }
    }
}
@Composable private fun VoiceButton(action: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalButton(action, modifier = Modifier.size(108.dp).semantics { contentDescription = "Speak your intention" },
            shape = CircleShape, contentPadding = PaddingValues(26.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF7044B8))) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                drawLine(Color.White, Offset(w/2, h*.15f), Offset(w/2, h*.52f), w*.25f, StrokeCap.Round)
                drawArc(Color.White, 0f, 180f, false, Offset(w*.18f, h*.26f), androidx.compose.ui.geometry.Size(w*.64f,h*.5f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawLine(Color.White, Offset(w/2,h*.76f), Offset(w/2,h*.92f), 3.dp.toPx())
                drawLine(Color.White, Offset(w*.32f,h*.92f), Offset(w*.68f,h*.92f), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        Spacer(Modifier.height(10.dp)); Caption("Tap to speak")
    }
}
@Composable private fun Hero(kind: String) {
    Box(Modifier.size(156.dp).background(Brush.radialGradient(listOf(Violet.copy(alpha = .25f), Color.Transparent))), contentAlignment = Alignment.Center) {
        Box(Modifier.size(110.dp).clip(CircleShape).background(Color(0xFF211637)), contentAlignment = Alignment.Center) {
            Text(when(kind) { "clock" -> "◷"; "hourglass" -> "⌛"; "check" -> "✓"; else -> "Ⅱ" }, fontSize = 58.sp, color = Violet)
        }
    }
}
@Composable private fun TimerFace(remaining: Long, session: Session) {
    val seconds = (remaining + 999) / 1000
    Box(Modifier.size(214.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            drawCircle(PanelColor, style = Stroke(5.dp.toPx()))
            val total = if (session.demo) 75_000L else (session.extensions.lastOrNull()?.minutes ?: session.plannedMinutes) * 60_000L
            drawArc(Violet, -90f, 360f * (remaining.toFloat() / total).coerceIn(0f,1f), false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", fontSize = 49.sp, fontWeight = FontWeight.Light)
            Text("TIME REMAINING", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
        }
    }
}
@Composable private fun History(history: List<Session>, actions: AppActions, clear: () -> Unit, hasOpenings: Boolean) {
    val real = history.filter { !it.demo }
    val relax = real.filter { it.purpose == Purpose.RELAX && it.after != null }
    Panel {
        Text("Intention → outcome", color = Violet, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("${real.size} sessions  ·  ${real.count { it.completed == true }} intentions fulfilled", color = Muted)
        if (relax.isEmpty()) Text("Do a session to relax, then check in. Your before-and-after pattern will appear here.", color = Muted)
        else {
            val change = relax.map { it.after!! - it.before }.average()
            Text("${relax.count { it.after!! > it.before }} of ${relax.size} relaxation sessions ended with a higher relaxation rating.", fontSize = 17.sp)
            Text("Average change: ${if (change >= 0) "+" else ""}${(change * 10).toInt() / 10.0} / 5", color = Mint)
            Caption("Self-reported feelings, not proof that scrolling caused a change. Demo sessions are excluded.")
        }
    }
    if (history.isEmpty()) Body("A fresh start. Your first check-in will show up here.")
    history.take(30).forEach { result ->
        Panel {
            Text(result.app.label, color = Violet, fontWeight = FontWeight.SemiBold)
            val outcome = when (result.completed) { true -> "YES"; false -> "NO"; null -> "N/A · ENDED WHILE AWAY" }
            Text("${if (result.demo) "DEMO · " else ""}${result.purpose.label.uppercase()} · $outcome", color = Muted, fontSize = 11.sp)
            Text(result.intention, fontWeight = FontWeight.Medium)
            Text("${result.elapsedMs / 60_000}m ${(result.elapsedMs / 1000) % 60}s elapsed · ${result.extensions.size} extensions", color = Muted, fontSize = 13.sp)
            Text("Relaxation ${result.before}/5 → ${result.after?.let { "$it/5" } ?: "N/A"}", color = Muted, fontSize = 13.sp)
            result.extensions.forEach { Text("+${it.minutes}m · ${it.reason}", color = Muted, fontSize = 12.sp) }
        }
    }
    if (history.isNotEmpty()) {
        Secondary("Export session journal (JSON)") { actions.exportHistory() }
    }
    if (history.isNotEmpty() || hasOpenings) TextButton(clear) { Text("Delete journal", color = Muted) }
}
