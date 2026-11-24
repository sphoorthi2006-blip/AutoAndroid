package com.example.kokorospeak

/**
 * One Kokoro voice. [sid] is the speaker id inside kokoro-multi-lang-v1_0's voices.bin
 * (54 voices, ids 0-53, taken from the sherpa-onnx model page).
 */
data class Voice(val sid: Int, val label: String, val female: Boolean)

object Voices {

    /** English voices (US and UK). These work best with English text. */
    private val english = listOf(
        // US female (af_*)
        Voice(3, "Heart (US female)", true),
        Voice(2, "Bella (US female)", true),
        Voice(0, "Alloy (US female)", true),
        Voice(1, "Aoede (US female)", true),
        Voice(4, "Jessica (US female)", true),
        Voice(5, "Kore (US female)", true),
        Voice(6, "Nicole (US female)", true),
        Voice(7, "Nova (US female)", true),
        Voice(8, "River (US female)", true),
        Voice(9, "Sarah (US female)", true),
        Voice(10, "Sky (US female)", true),
        // US male (am_*)
        Voice(11, "Adam (US male)", false),
        Voice(12, "Echo (US male)", false),
        Voice(13, "Eric (US male)", false),
        Voice(14, "Fenrir (US male)", false),
        Voice(15, "Liam (US male)", false),
        Voice(16, "Michael (US male)", false),
        Voice(17, "Onyx (US male)", false),
        Voice(18, "Puck (US male)", false),
        Voice(19, "Santa (US male)", false),
        // UK female (bf_*)
        Voice(20, "Alice (UK female)", true),
        Voice(21, "Emma (UK female)", true),
        Voice(22, "Isabella (UK female)", true),
        Voice(23, "Lily (UK female)", true),
        // UK male (bm_*)
        Voice(24, "Daniel (UK male)", false),
        Voice(25, "Fable (UK male)", false),
        Voice(26, "George (UK male)", false),
        Voice(27, "Lewis (UK male)", false),
    )

    /**
     * Hindi-trained voices. sherpa-onnx only supports English and Chinese text for this model,
     * so these speak English text and may sound accented or odd. Experimental.
     */
    private val experimental = listOf(
        Voice(31, "Alpha (Hindi voice, experimental)", true),
        Voice(32, "Beta (Hindi voice, experimental)", true),
        Voice(33, "Omega (Hindi voice, experimental)", false),
        Voice(34, "Psi (Hindi voice, experimental)", false),
    )

    val all: List<Voice> = english + experimental
}
