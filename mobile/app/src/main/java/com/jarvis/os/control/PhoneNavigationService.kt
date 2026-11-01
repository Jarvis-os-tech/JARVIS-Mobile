package com.jarvis.os.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** User-enabled native navigation adapter; no desktop connection is required. */
class PhoneNavigationService : AccessibilityService() {
    override fun onServiceConnected() { current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

    private fun action(args: Map<String, Any?>): JSONObject {
        val action = args["action"] as? String ?: error("Navigation action required")
        val result = JSONObject().put("device", "phone").put("action", action)
        when (action) {
            "read_screen" -> {
                val root = rootInActiveWindow ?: error("No active app screen. Unlock the phone first.")
                val queue = java.util.ArrayDeque<AccessibilityNodeInfo>();queue.add(root)
                val elements = JSONArray()
                var visited = 0
                while (queue.isNotEmpty() && visited++ < 400 && elements.length() < 120) {
                    val node = queue.removeFirst()
                    val label = when {
                        node.isPassword -> "[password field]"
                        !node.text.isNullOrBlank() -> node.text.toString()
                        !node.contentDescription.isNullOrBlank() -> node.contentDescription.toString()
                        node.isEditable -> "[text field]"
                        else -> ""
                    }
                    if (node.isVisibleToUser && label.isNotBlank()) {
                        val bounds = Rect();node.getBoundsInScreen(bounds)
                        elements.put(JSONObject().put("text", label.take(1000)).put("clickable", node.isClickable)
                            .put("bounds", bounds.flattenToString()).put("package", node.packageName?.toString()))
                    }
                    for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it) }
                }
                return result.put("status", "completed").put("untrusted_screen_content", true).put("elements", elements)
            }
            "click_text" -> {
                val text = (args["text"] as? String)?.trim() ?: error("Button text required")
                require(text.isNotEmpty())
                val nodes = rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
                    .filter { it.isVisibleToUser && (it.text?.toString().equals(text, true) || it.contentDescription?.toString().equals(text, true)) }
                val targets = nodes.mapNotNull { candidate ->
                    var node: AccessibilityNodeInfo? = candidate
                    repeat(6) { if (node != null && !node!!.isClickable) node = node!!.parent }
                    node?.takeIf { it.isClickable }
                }.distinctBy { val bounds = Rect();it.getBoundsInScreen(bounds);bounds.flattenToString() }
                if (targets.size != 1) return result.put("status", "needs_clarification")
                    .put("message", "Button text was missing or matched more than one button. Read the screen and use coordinates.")
                check(targets.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Android could not click this button" }
            }
            "type_text" -> {
                val text = args["text"] as? String ?: error("Text required")
                require(text.length in 1..4000)
                val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: error("Focus a text field first")
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })) { "The app does not allow direct text entry in this field" }
            }
            "tap", "swipe" -> {
                val metrics = resources.displayMetrics
                fun coordinate(name: String, max: Int): Float {
                    val value = (args[name] as? Number)?.toFloat() ?: error("Screen coordinate required")
                    require(value.isFinite() && value >= 0 && value < max)
                    return value
                }
                val x = coordinate("x", metrics.widthPixels);val y = coordinate("y", metrics.heightPixels)
                val path = Path().apply {
                    moveTo(x, y)
                    if (action == "swipe") lineTo(coordinate("end_x", metrics.widthPixels), coordinate("end_y", metrics.heightPixels))
                }
                val duration = if (action == "tap") 80L else (args["duration_ms"] as? Number)?.toLong() ?: 350L
                require(duration in 50..3000)
                check(dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null))
                return result.put("status", "gesture_dispatched")
            }
            else -> {
                val global = when (action) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                    else -> error("Unsupported phone navigation action")
                }
                check(performGlobalAction(global)) { "Android could not perform this navigation action" }
            }
        }
        return result.put("status", "action_accepted")
    }

    companion object {
        @Volatile private var current: PhoneNavigationService? = null
        val available: Boolean get() = current != null
        fun execute(args: Map<String, Any?>): JSONObject {
            val service = current ?: return JSONObject().put("status", "permission_required")
                .put("permission", "JARVIS voice navigation in Accessibility settings")
            if (Looper.myLooper() == Looper.getMainLooper()) return service.action(args)
            val ready = CountDownLatch(1)
            var result: JSONObject? = null
            Handler(Looper.getMainLooper()).post {
                try { result = service.action(args) }
                catch (_: Exception) { result = JSONObject().put("status", "failed").put("message", "Phone navigation was not accepted. Read the screen before retrying.") }
                finally { ready.countDown() }
            }
            if (!ready.await(5, TimeUnit.SECONDS)) return JSONObject().put("status", "unknown")
                .put("message", "Navigation result is unknown; inspect the screen before repeating.")
            return result ?: JSONObject().put("status", "failed")
        }
    }
}
