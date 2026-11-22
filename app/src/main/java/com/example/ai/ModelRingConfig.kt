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
     * Exact 6-Model Circular Ring Order:
     * 1. gemini-3.1-flash-live-preview (Multimodal Live WebSocket BidiGenerateContent)
     * 2. gemini-3.8-flash
     * 3. gemini-3.6-flash
     * 4. gemini-3.5-flash-lite
     * 5. gemini-decision-1
     * 6. gemini-4-hydrogen
     */
    val MODEL_RING: List<ModelSlot> = listOf(
        ModelSlot("gemini-3.1-flash-live-preview", "3.1-live", isLiveWebSocket = true),
        ModelSlot("gemini-3.8-flash", "3.8-flash", isLiveWebSocket = false),
        ModelSlot("gemini-3.6-flash", "3.6-flash", isLiveWebSocket = false),
        ModelSlot("gemini-3.5-flash-lite", "3.5-lite", isLiveWebSocket = false),
        ModelSlot("gemini-decision-1", "decision-1", isLiveWebSocket = false),
        ModelSlot("gemini-4-hydrogen", "4-hydrogen", isLiveWebSocket = false)
    )

    const val SYSTEM_PROMPT =
        "You are JARVIS, an intelligent, loyal Android voice assistant created by Mozahid. " +
            "Address your creator respectfully as 'Sir' or 'Mozahid Sir'. " +
            "Respond in the user's language (Hindi, Hinglish, or English) in 1 to 3 concise sentences. " +
            "Act immediately using tools without narrating steps. " +
            "Use ui_read to inspect screens as numbered text lists and ui_click/ui_type/ui_scroll " +
            "to complete multi-step tasks autonomously until finished. " +
            "Never pretend an action succeeded unless the tool returns 'done'."

    /**
     * Builds compact Gemini Function Declarations JSON for zero-token-wastage tool calling.
     */
    fun buildToolDeclarationsJson(): JSONArray {
        val declarations = JSONArray()

        fun addTool(name: String, desc: String, props: Map<String, Pair<String, String>>, required: List<String> = emptyList()) {
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
            "open_app",
            "Open an installed Android app by name or package.",
            mapOf("app_name" to ("STRING" to "App name e.g. WhatsApp, YouTube, Instagram, Chrome")),
            listOf("app_name")
        )
        addTool(
            "ui_read",
            "Read current screen UI as a compact numbered text tree.",
            emptyMap()
        )
        addTool(
            "ui_click",
            "Click a UI element directly by index from ui_read or visible text (including Send buttons).",
            mapOf(
                "index" to ("INTEGER" to "Element index from ui_read"),
                "text" to ("STRING" to "Visible label or button text to click")
            )
        )
        addTool(
            "ui_type",
            "Type text into an input field by index or focused field, optionally tapping Send.",
            mapOf(
                "index" to ("INTEGER" to "Optional element index from ui_read"),
                "text" to ("STRING" to "Text to type"),
                "press_send" to ("BOOLEAN" to "If true, click Send immediately after typing")
            ),
            listOf("text")
        )
        addTool(
            "ui_scroll",
            "Scroll the current screen up or down.",
            mapOf("direction" to ("STRING" to "up or down")),
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
                "action" to ("STRING" to "One of: volume_up, volume_down, mute, unmute, flashlight_on, flashlight_off, battery")
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
            "Schedule a JARVIS full-screen incoming voice-call alarm that rings even when locked and speaks the custom reason.",
            mapOf(
                "delay_minutes" to ("INTEGER" to "Minutes from now until alarm rings"),
                "reason" to ("STRING" to "Custom purpose e.g. Study time, Padhne ka time")
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
            "Auto-generate viral SEO metadata and upload the newest video from the Jarvis_Uploads watch folder to YouTube.",
            mapOf("topic" to ("STRING" to "Optional spoken topic if no .txt note exists")),
            emptyList()
        )

        return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
    }
}
