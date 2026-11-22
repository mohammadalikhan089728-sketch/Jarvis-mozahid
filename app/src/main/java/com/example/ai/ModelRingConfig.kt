package com.example.ai

import org.json.JSONArray
import org.json.JSONObject

enum class AssistantState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    CONTROLLING
}

data class ModelSlot(
    val modelId: String,
    val badgeLabel: String,
    val isLiveWebSocket: Boolean
)

data class ActiveEngineState(
    val modelSlot: ModelSlot = ModelRingConfig.MODEL_RING.first(),
    val activeKeyNumber: Int = 1,
    val isLiveConnected: Boolean = false,
    val hasConfiguredKeys: Boolean = false,
    val isAutoRotate: Boolean = true,
    val resolvedModelId: String? = null
) {
    val badgeText: String
        get() = if (!hasConfiguredKeys) {
            "No API Key"
        } else {
            val modePrefix = if (isAutoRotate) "" else "Locked · "
            "$modePrefix${modelSlot.badgeLabel} · K$activeKeyNumber"
        }
}

object ModelRingConfig {

    const val MODEL_MODE_AUTO = "auto"

    /**
     * Exact Active Model Priority Ring (Zero deprecated models):
     * 1. gemini-3.1-flash-live-preview (Default Live Voice WebSocket BidiGenerateContent)
     * 2. gemini-3.8-flash
     * 3. gemini-3.6-flash
     * 4. gemini-3.5-flash-lite
     * 5. gemini-decision-1
     * 6. gemini-4-hydrogen
     */
    val MODEL_RING: List<ModelSlot> = listOf(
        ModelSlot("gemini-3.1-flash-live-preview", "3.1-flash-live-preview", isLiveWebSocket = true),
        ModelSlot("gemini-3.8-flash", "3.8-flash", isLiveWebSocket = false),
        ModelSlot("gemini-3.6-flash", "3.6-flash", isLiveWebSocket = false),
        ModelSlot("gemini-3.5-flash-lite", "3.5-flash-lite", isLiveWebSocket = false),
        ModelSlot("gemini-decision-1", "decision-1", isLiveWebSocket = false),
        ModelSlot("gemini-4-hydrogen", "4-hydrogen", isLiveWebSocket = false)
    )

    fun findSlotById(modelId: String): ModelSlot? {
        return MODEL_RING.firstOrNull { it.modelId.equals(modelId.trim(), ignoreCase = true) }
    }

    fun labelForPreference(preferredModelId: String): String {
        if (preferredModelId.isBlank() || preferredModelId == MODEL_MODE_AUTO) {
            return "Auto-Rotate (Circular Fallback)"
        }
        return findSlotById(preferredModelId)?.badgeLabel ?: preferredModelId
    }

    const val SYSTEM_PROMPT =
        "You are JARVIS, an intelligent, loyal Android AI assistant created by Mozahid. " +
            "Address your creator respectfully as 'Sir' or 'Mozahid Sir'. " +
            "Reply in the user's language (Hindi, Hinglish, or English) in 1 to 2 concise sentences. " +
            "Execute multi-step phone actions autonomously in a loop using " +
            "go_home, ui_read, ui_click, ui_type, and ui_scroll without narrating steps. " +
            "Keep token usage minimal."

    fun buildToolDeclarationsJson(): JSONArray {
        val declarations = JSONArray()

        fun addTool(
            name: String,
            desc: String,
            props: Map<String, Pair<String, String>>,
            required: List<String> = emptyList()
        ) {
            val propertiesObj = JSONObject()
            props.forEach { (k, pair) ->
                propertiesObj.put(
                    k,
                    JSONObject()
                        .put("type", pair.first)
                        .put("description", pair.second)
                )
            }
            val schema = JSONObject()
                .put("type", "OBJECT")
                .put("properties", propertiesObj)
            if (required.isNotEmpty()) {
                schema.put("required", JSONArray(required))
            }
            declarations.put(
                JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put("parameters", schema)
            )
        }

        addTool(
            "go_home",
            "Press Android Home button immediately and return the home screen UI tree.",
            emptyMap()
        )
        addTool(
            "open_app",
            "Open an installed Android app by name or package.",
            mapOf("app_name" to ("STRING" to "App name e.g. WhatsApp, YouTube, Instagram, Chrome")),
            listOf("app_name")
        )
        addTool(
            "ui_read",
            "Extract current active screen as a compact numbered text tree with coordinates.",
            emptyMap()
        )
        addTool(
            "ui_click",
            "Click a UI element directly by index from ui_read or visible label (Send, Like, Comment, Call, app icons).",
            mapOf(
                "index" to ("INTEGER" to "Element index from ui_read"),
                "text" to ("STRING" to "Visible label, icon description, or button text to click")
            )
        )
        addTool(
            "ui_type",
            "Focus input field by index or label, type text instantly, and optionally press IME Search/Send.",
            mapOf(
                "index" to ("INTEGER" to "Optional element index from ui_read"),
                "label" to ("STRING" to "Optional input field hint/label if index omitted"),
                "text" to ("STRING" to "Text to type"),
                "submit" to ("BOOLEAN" to "If true, immediately trigger IME Search/Send or tap Send")
            ),
            listOf("text")
        )
        addTool(
            "ui_scroll",
            "Smooth human-like swipe gesture in any direction (up, down, left, right).",
            mapOf("direction" to ("STRING" to "up, down, left, or right")),
            listOf("direction")
        )
        addTool(
            "ui_back",
            "Press Android system Back button.",
            emptyMap()
        )
        addTool(
            "call_contact",
            "Call a phone number or saved contact name directly.",
            mapOf("target" to ("STRING" to "Contact name or phone number")),
            listOf("target")
        )
        addTool(
            "whatsapp_send",
            "Send a WhatsApp message directly to a contact or phone number.",
            mapOf(
                "contact" to ("STRING" to "Contact name or phone number with country code"),
                "message" to ("STRING" to "Message text to send")
            ),
            listOf("contact", "message")
        )
        addTool(
            "instagram_dm",
            "Open Instagram DM and send a message directly to a username.",
            mapOf(
                "username" to ("STRING" to "Instagram username or display name"),
                "message" to ("STRING" to "Direct message text")
            ),
            listOf("username", "message")
        )
        addTool(
            "sms_send",
            "Send an SMS message directly to a phone number or contact.",
            mapOf(
                "target" to ("STRING" to "Phone number or contact name"),
                "message" to ("STRING" to "SMS body text")
            ),
            listOf("target", "message")
        )
        addTool(
            "device_control",
            "Control device hardware: volume_up, volume_down, mute, unmute, flashlight_on, flashlight_off, battery.",
            mapOf(
                "action" to ("STRING" to "volume_up, volume_down, mute, unmute, flashlight_on, flashlight_off, or battery")
            ),
            listOf("action")
        )
        addTool(
            "set_default_timer",
            "Set a standard Android countdown timer via AlarmClock.",
            mapOf(
                "seconds" to ("INTEGER" to "Timer duration in seconds"),
                "label" to ("STRING" to "Optional timer label")
            ),
            listOf("seconds")
        )
        addTool(
            "set_jarvis_call_alarm",
            "Schedule a JARVIS full-screen incoming voice-call alarm that rings over the lock screen and speaks the custom reason. Provide either (hour + minute in 24h time) or delay_minutes.",
            mapOf(
                "hour" to ("INTEGER" to "Optional 24-hour clock hour (0-23) e.g. 6 for 6:00 AM"),
                "minute" to ("INTEGER" to "Optional clock minute (0-59) e.g. 0"),
                "delay_minutes" to ("INTEGER" to "Optional minutes from now if clock hour is not given"),
                "reason" to ("STRING" to "Custom purpose e.g. Padhne ka time, Study time")
            ),
            listOf("reason")
        )
        addTool(
            "save_memory",
            "Save a personal note or fact to local on-device Room memory.",
            mapOf("note" to ("STRING" to "Fact or note to remember")),
            listOf("note")
        )
        addTool(
            "recall_memory",
            "Search local on-device Room memory for a matching snippet.",
            mapOf("query" to ("STRING" to "Keywords to recall")),
            listOf("query")
        )
        addTool(
            "youtube_upload_folder",
            "Start background Auto-SEO YouTube upload of the newest video from the Jarvis_Uploads watch folder.",
            mapOf("topic" to ("STRING" to "Optional spoken topic in Hindi, Hinglish, or English")),
            emptyList()
        )

        return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
    }
}
