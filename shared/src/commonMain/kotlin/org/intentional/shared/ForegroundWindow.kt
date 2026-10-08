package org.intentional.shared

enum class WindowKind { APPLICATION, SYSTEM, KEYBOARD, ACCESSIBILITY_OVERLAY, OTHER }
data class ForegroundWindow(val kind: WindowKind, val focused: Boolean, val active: Boolean)

/** Windows arrive in descending layer order. Notifications are not app switches.
 * A focused system panel is; a keyboard or accessibility reminder is not. */
fun foregroundWindowIndex(windows: List<ForegroundWindow>): Int? {
    val focused = windows.indexOfFirst {
        it.focused && it.kind !in listOf(WindowKind.KEYBOARD, WindowKind.ACCESSIBILITY_OVERLAY)
    }
    if (focused >= 0) return focused.takeIf { windows[it].kind == WindowKind.APPLICATION }
    return windows.indexOfFirst { it.active && it.kind == WindowKind.APPLICATION }.takeIf { it >= 0 }
}
