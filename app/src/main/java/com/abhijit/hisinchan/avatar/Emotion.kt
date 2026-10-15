package com.abhijit.hisinchan.avatar

/** Every visual state Sinchan can show. */
enum class Emotion(val label: String) {
    HAPPY("Happy"),
    EXCITED("Excited"),
    CURIOUS("Curious"),
    THINKING("Thinking"),
    EMPATHETIC("Empathetic"),
    SURPRISED("Surprised"),
    SLEEPY("Sleepy"),
    SPEAKING("Speaking"),
    LISTENING("Listening"),
    CELEBRATING("Celebrating"),
    NEUTRAL("Neutral");

    companion object {
        fun fromTag(tag: String): Emotion? = when (tag.lowercase()) {
            "happy" -> HAPPY
            "excited" -> EXCITED
            "curious" -> CURIOUS
            "thinking" -> THINKING
            "empathetic", "sad", "caring" -> EMPATHETIC
            "surprised" -> SURPRISED
            "celebrating", "proud" -> CELEBRATING
            "neutral" -> NEUTRAL
            else -> null
        }
    }
}

/** Short one-shot gestures layered on top of the base emotion (tap reactions, greetings). */
enum class Gesture { NONE, WAVE, GIGGLE, BOUNCE, SPIN_EYES, WINK, NOD, SHY }
