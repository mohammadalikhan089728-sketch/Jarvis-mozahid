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
    val hasConfiguredKeys: Boolean = false
) {
    val badgeText: String
        get() = if (!hasConfiguredKeys) {
            "No API Key"
        } else {
            "${modelSlot.badgeLabel} · K$activeKeyNumber"
        }
}

object ModelRingConfig {

    /**
     * Exact 6-Model Circular Ring mapped to real, valid Google Gemini API model IDs:
     * 1. gemini-3.1-flash-live-preview (Live WebSocket, with fallback live models)
     * 2. gemini-2.5-flash              (UI badge: 3.8-flash)
     * 3. gemini-2.5-flash-lite         (UI badge: 3.6-flash)
     * 4. gemini-2.0-flash              (UI badge: 3.5-flash-lite)
     * 5. gemini-2.0-flash-lite         (UI badge: decision-1)
     * 6. gemini-1.5-flash              (UI badge: 4-hydrogen)
     */
    val MODEL_RING: List<ModelSlot> = listOf(
        ModelSlot("gemini-3.1-flash-live-preview", "3.1-live", isLiveWebSocket = true),
        ModelSlot("gemini-2.5-flash", "3.8-flash", isLiveWebSocket = false),
        ModelSlot("gemini-2.5-flash-lite", "3.6-flash", isLiveWebSocket = false),
        ModelSlot("gemini-2.0-flash", "3.5-flash-lite", isLiveWebSocket = false),
        ModelSlot("gemini-2.0-flash-lite", "decision-1", isLiveWebSocket = false),
        ModelSlot("gemini-1.5-flash", "4-hydrogen", isLiveWebSocket = false)
    )

    val LIVE_FALLBACK_MODELS: List<String> = listOf(
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-preview-09-2025",
        "gemini-2.0-flash-exp"
    )

    const val SYSTEM_PROMPT =
        "You are JARVIS, an autonomous, high-speed Android AI assistant created by Mozahid. " +
            "Address your creator respectfully as 'Sir' or 'Mozahid Sir'. " +
            "Reply in the user's language (Hindi, Hinglish, or English) in 1 to 2 concise sentences. " +
            "Never narrate steps—immediately execute multi-step actions in a loop using " +
            "go_home, ui_read, ui_click, ui_type, and ui_scroll until the task is completely done. " +
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
            "Schedule a JARVIS full-screen incoming voice-call alarm that rings over the lock screen and speaks the custom reason.",
            mapOf(
                "delay_minutes" to ("INTEGER" to "Minutes from now until alarm rings"),
                "reason" to ("STRING" to "Custom purpose e.g. Padhne ka time, Study time")
            ),
            listOf("delay_minutes", "reason")
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
