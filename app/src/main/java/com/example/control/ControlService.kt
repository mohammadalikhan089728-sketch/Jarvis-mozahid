package com.example.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
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
 * Zero-Touch AccessibilityService (`ControlService`):
 * - Reads active window UI hierarchy as a compact numbered text list (no screenshots, labels <= 70 chars).
 * - Executes direct clicks (including Send buttons in WhatsApp, Instagram, Messages) without popups.
 * - Types text, scrolls, navigates back, and dispatches physical gesture taps as fallback.
 */
class ControlService : AccessibilityService() {

    private val indexedBounds = mutableMapOf<Int, IndexedUiNode>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Lightweight passive hook; screen trees are only traversed on demand via uiRead()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Extracts the active window UI hierarchy as a compact numbered text list:
     * `[1] Search`, `[2] Send`, etc. Every node label is truncated to 70 characters.
     */
    fun uiRead(): String {
        val root = rootInActiveWindow ?: return "error: no active window"
        indexedBounds.clear()
        val lines = mutableListOf<String>()
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty()) {
            lines.add("App: $pkg")
        }

        var counter = 1
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || counter > MAX_UI_NODES || depth > 24) return
            if (!node.isVisibleToUser) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
                        .ifEmpty { if (node.isEditable) "InputField" else "Button" }
                        .take(MAX_LABEL_CHARS)

                    val tag = when {
                        node.isEditable -> " (input)"
                        node.isClickable -> ""
                        else -> ""
                    }
                    indexedBounds[counter] = IndexedUiNode(
                        index = counter,
                        label = cleanLabel,
                        bounds = rect,
                        isClickable = node.isClickable,
                        isEditable = node.isEditable,
                        viewId = node.viewIdResourceName.orEmpty()
                    )
                    lines.add("[$counter] $cleanLabel$tag")
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
     * Direct UI click by index or label text (clicks Send buttons directly without confirmation popups).
     */
    suspend fun uiClick(index: Int? = null, text: String? = null): String {
        val root = rootInActiveWindow ?: return "error: no active window"

        // 1. Try by cached index from uiRead()
        if (index != null && index > 0) {
            val cached = indexedBounds[index]
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

        // 2. Try by exact/partial text or contentDescription (supports WhatsApp/Instagram/SMS Send buttons)
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
     * Directly clicks any visible Send button on WhatsApp, Instagram, or Messages.
     */
    suspend fun clickSendButtonDirectly(): Boolean {
        val sendLabels = listOf("Send", "send", "Send message", "Post", "Reply")
        for (label in sendLabels) {
            if (uiClick(text = label) == "done") return true
        }
        val root = rootInActiveWindow ?: return false
        val idCandidates = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.instagram.android:id/row_thread_composer_button_send",
            "com.google.android.apps.messaging:id/send_message_button_icon"
        )
        for (resId in idCandidates) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resId)
            val target = nodes?.firstOrNull()
            if (target != null && clickNodeOrParent(target)) return true
        }
        return false
    }

    /**
     * Types text directly into a target input index or currently focused editable node.
     */
    suspend fun uiType(index: Int? = null, text: String, pressSend: Boolean = false): String {
        val root = rootInActiveWindow ?: return "error: no active window"
        var targetNode: AccessibilityNodeInfo? = null

        if (index != null && index > 0) {
            indexedBounds[index]?.let { cached ->
                targetNode = findNodeByBoundsOrLabel(root, cached.bounds, cached.label)
            }
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

        if (pressSend) {
            kotlinx.coroutines.delay(220L)
            clickSendButtonDirectly()
        }
        return "done"
    }

    suspend fun uiScroll(direction: String): String {
        val root = rootInActiveWindow ?: return "error: no active window"
        val down = direction.lowercase() != "up"
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            val action = if (down) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return "done"
        }

        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels * 0.5f
        val startY = if (down) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.28f
        val endY = if (down) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.72f
        return if (swipe(cx, startY, cx, endY)) "done" else "error: scroll failed"
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
        val exactText = root.findAccessibilityNodeInfosByText(query)
        exactText?.firstOrNull { it.isVisibleToUser }?.let { return it }

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

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val res = findScrollableNode(node.getChild(i))
            if (res != null) return res
        }
        return null
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
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
        return withTimeoutOrNull(1500L) { deferred.await() } == true
    }

    private suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 260L))
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
        return withTimeoutOrNull(1500L) { deferred.await() } == true
    }

    companion object {
        private const val MAX_UI_NODES = 60
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
