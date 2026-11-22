package com.example.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class IndexedUiNode(
    val index: Int,
    val label: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val viewId: String
)

/**
 * High-Speed Human-Like Manual Phone Control (`ControlService`):
 * - `goHome()`: Presses Home button, waits briefly, and returns the home screen UI tree.
 * - `uiRead()`: Extracts active screen accessibility tree as a compact indexed text list
 *   with exact coordinates and 70-char label truncation (zero screenshots, zero token waste).
 * - `uiClick(index, text)`: Clicks via ACTION_CLICK or millisecond exact-center dispatchGesture tap
 *   directly on Send, Like, Comment, Call, Search, and App icons without confirmation popups.
 * - `uiType(index, label, text, submit)`: Focuses input field, sets text instantly, and triggers IME Search/Send.
 * - `uiScroll(direction)`: Smooth human-like swipes up, down, left, and right.
 */
class ControlService : AccessibilityService() {

    private val indexedNodes = mutableMapOf<Int, IndexedUiNode>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Presses Android Home button immediately and returns the Home screen's compact UI tree
     * so JARVIS can locate icons or swipe open the app drawer like a human.
     */
    suspend fun goHome(): String {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        if (!ok) return "error: failed to press home"
        delay(280L)
        return "done\n${uiRead()}"
    }

    /**
     * Extracts active window UI hierarchy as a compact numbered text list with coordinates:
     * `[1] Search (540,180)`, `[2] EditText (input, 420,210)`, `[3] Like (180,960)`, etc.
     * Truncates every label to 70 characters; never captures screenshots.
     */
    fun uiRead(): String {
        val root = rootInActiveWindow ?: return "error: no active window"
        indexedNodes.clear()
        val lines = mutableListOf<String>()
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty()) {
            lines.add("App: $pkg")
        }

        var counter = 1
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || counter > MAX_UI_NODES || depth > 26) return
            if (!node.isVisibleToUser) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val rawLabel = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                hint.isNotEmpty() -> hint
                else -> ""
            }

            val interesting = rawLabel.isNotEmpty() || node.isEditable || (node.isClickable && desc.isNotEmpty())
            if (interesting) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val cleanLabel = rawLabel
                        .replace("\n", " ")
                        .replace(Regex("\\s+"), " ")
                        .ifEmpty { if (node.isEditable) "EditText" else "Button" }
                        .take(MAX_LABEL_CHARS)

                    val cx = rect.centerX()
                    val cy = rect.centerY()
                    val roleTag = if (node.isEditable) "input, " else ""

                    indexedNodes[counter] = IndexedUiNode(
                        index = counter,
                        label = cleanLabel,
                        bounds = rect,
                        isClickable = node.isClickable,
                        isEditable = node.isEditable,
                        viewId = node.viewIdResourceName.orEmpty()
                    )
                    lines.add("[$counter] $cleanLabel ($roleTag$cx,$cy)")
                    counter++
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), depth + 1)
            }
        }

        traverse(root, 0)
        return if (lines.size <= 1) "Screen empty or protected" else lines.joinToString("\n")
    }

    /**
     * Clicks target by index from uiRead() or visible text/description at millisecond speed.
     * Falls back immediately to exact center-point physical dispatchGesture tap.
     */
    suspend fun uiClick(index: Int? = null, text: String? = null): String {
        val root = rootInActiveWindow ?: return "error: no active window"

        if (index != null && index > 0) {
            val cached = indexedNodes[index]
            if (cached != null) {
                val matchedNode = findNodeByBoundsOrLabel(root, cached.bounds, cached.label)
                if (matchedNode != null && clickNodeOrParent(matchedNode)) {
                    return "done"
                }
                if (tapAt(cached.bounds.centerX().toFloat(), cached.bounds.centerY().toFloat())) {
                    return "done"
                }
            }
        }

        val query = text?.trim().orEmpty()
        if (query.isNotEmpty()) {
            val directMatch = findNodeByTextOrDesc(root, query)
            if (directMatch != null) {
                if (clickNodeOrParent(directMatch)) return "done"
                val rect = Rect()
                directMatch.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0 &&
                    tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                ) {
                    return "done"
                }
            }
        }

        return "error: element not found"
    }

    /**
     * Directly clicks Send / Post / Search / Submit controls across WhatsApp, Instagram, YouTube, and Messages.
     */
    suspend fun clickSendButtonDirectly(): Boolean {
        val root = rootInActiveWindow ?: return false
        val idCandidates = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.instagram.android:id/row_thread_composer_button_send",
            "com.google.android.apps.messaging:id/send_message_button_icon",
            "com.google.android.youtube:id/send_button"
        )
        for (resId in idCandidates) {
            val target = root.findAccessibilityNodeInfosByViewId(resId)?.firstOrNull()
            if (target != null && clickNodeOrParent(target)) return true
        }
        val labels = listOf("Send", "Send comment", "Post", "Reply", "Search")
        for (label in labels) {
            if (uiClick(text = label) == "done") return true
        }
        return false
    }

    /**
     * Focuses input field (by index, label, or active focus), sets text instantly via ACTION_SET_TEXT,
     * and when `submit = true`, executes IME Search/Send action or taps the visible Send button.
     */
    suspend fun uiType(
        index: Int? = null,
        label: String? = null,
        text: String,
        submit: Boolean = false
    ): String {
        val root = rootInActiveWindow ?: return "error: no active window"
        var targetNode: AccessibilityNodeInfo? = null

        if (index != null && index > 0) {
            indexedNodes[index]?.let { cached ->
                targetNode = findNodeByBoundsOrLabel(root, cached.bounds, cached.label)
            }
        }
        if (targetNode == null && !label.isNullOrBlank()) {
            targetNode = findNodeByTextOrDesc(root, label)
        }
        if (targetNode == null) {
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableNode(root)
        }

        val input = targetNode ?: return "error: no editable input found"
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        clickNodeOrParent(input)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val typed = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!typed) return "error: failed to type text"

        if (submit) {
            delay(140L)
            var submitted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                submitted = input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (!submitted) {
                clickSendButtonDirectly()
            }
        }
        return "done"
    }

    /**
     * Smooth human-like swipe gesture supporting up, down, left, and right directions
     * (including app drawer opening and horizontal home-screen paging).
     */
    suspend fun uiScroll(direction: String): String {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        return when (direction.lowercase().trim()) {
            "down" -> {
                // Swipe finger from bottom to top to scroll content down / open app drawer
                if (swipe(w * 0.5f, h * 0.78f, w * 0.5f, h * 0.24f, 190L)) "done" else "error: scroll down failed"
            }
            "up" -> {
                // Swipe finger from top to bottom to scroll content up
                if (swipe(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.78f, 190L)) "done" else "error: scroll up failed"
            }
            "left" -> {
                // Swipe right-to-left to move to next home screen page
                if (swipe(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f, 180L)) "done" else "error: swipe left failed"
            }
            "right" -> {
                // Swipe left-to-right to move to previous home screen page
                if (swipe(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f, 180L)) "done" else "error: swipe right failed"
            }
            else -> {
                if (swipe(w * 0.5f, h * 0.76f, w * 0.5f, h * 0.26f, 190L)) "done" else "error: scroll failed"
            }
        }
    }

    fun uiBack(): String {
        return if (performGlobalAction(GLOBAL_ACTION_BACK)) "done" else "error: back failed"
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodeByBoundsOrLabel(
        node: AccessibilityNodeInfo,
        targetBounds: Rect,
        label: String
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect == targetBounds) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByBoundsOrLabel(child, targetBounds, label)
            if (found != null) return found
        }
        return findNodeByTextOrDesc(node, label)
    }

    private fun findNodeByTextOrDesc(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val lower = query.lowercase()
        root.findAccessibilityNodeInfosByText(query)?.firstOrNull { it.isVisibleToUser }?.let { return it }

        fun dfs(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            val t = node.text?.toString()?.lowercase().orEmpty()
            val d = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (t == lower || d == lower || t.contains(lower) || d.contains(lower)) {
                return node
            }
            for (i in 0 until node.childCount) {
                val res = dfs(node.getChild(i))
                if (res != null) return res
            }
            return null
        }
        return dfs(root)
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val res = findFirstEditableNode(node.getChild(i))
            if (res != null) return res
        }
        return null
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 35L))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)
        if (!dispatched) return false
        return withTimeoutOrNull(1200L) { deferred.await() } == true
    }

    private suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)
        if (!dispatched) return false
        return withTimeoutOrNull(1200L) { deferred.await() } == true
    }

    companion object {
        private const val MAX_UI_NODES = 65
        private const val MAX_LABEL_CHARS = 70

        @Volatile
        var instance: ControlService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, ControlService::class.java).flattenToString()
            return enabledServices.contains(expected, ignoreCase = true) ||
                enabledServices.contains(ControlService::class.java.simpleName, ignoreCase = true)
        }
    }
}
