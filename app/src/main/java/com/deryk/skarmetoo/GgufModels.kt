package com.deryk.skarmetoo

data class GgufModelInfo(
    val displayName: String,
    val fileName: String,
    val hfRepo: String,
    val hfFile: String,
    val sizeMb: Long,
    val description: String,
    val isVision: Boolean = false,
    val mmprojFile: String = "",
    val mmprojSizeMb: Long = 0,
    val quantLabel: String = "",
)

val LFM2_5_VARIANTS =
    listOf(
        GgufModelInfo(
            displayName = "LFM 2.5 VL",
            fileName = "LFM2.5-VL-450M-Q8_0.gguf",
            hfRepo = "LiquidAI/LFM2.5-VL-450M-GGUF",
            hfFile = "LFM2.5-VL-450M-Q8_0.gguf",
            sizeMb = 379,
            description = "Ultra-efficient vision model optimized for speed and low thermal impact",
            isVision = true,
            mmprojFile = "mmproj-LFM2.5-VL-450m-Q8_0.gguf",
            mmprojSizeMb = 103,
            quantLabel = "Q8_0",
        ))

val LFM2_5_MODEL = LFM2_5_VARIANTS[0] // Q8_0 default

val PRESET_GGUF_MODELS =
    listOf(
        GgufModelInfo(
            displayName = "Qwen 2.5 1.5B",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            hfRepo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            hfFile = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 986,
            description = "Fast bilingual model; good for tagging and summaries",
        ),
        GgufModelInfo(
            displayName = "Llama 3.2 1B",
            fileName = "llama-3.2-1b-instruct-q4_k_m.gguf",
            hfRepo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            hfFile = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeMb = 720,
            description = "Meta's smallest Llama; fast on CPU",
        ),
        GgufModelInfo(
            displayName = "Llama 3.2 3B",
            fileName = "llama-3.2-3b-instruct-q4_k_m.gguf",
            hfRepo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            hfFile = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 2000,
            description = "Best quality balance for mobile GGUF",
        ),
        GgufModelInfo(
            displayName = "Phi-3 Mini",
            fileName = "phi-3-mini-4k-instruct-q4.gguf",
            hfRepo = "microsoft/Phi-3-mini-4k-instruct-gguf",
            hfFile = "Phi-3-mini-4k-instruct-q4.gguf",
            sizeMb = 2060,
            description = "Microsoft's mini model; strong reasoning",
        ),
        GgufModelInfo(
            displayName = "Gemma 2 2B",
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            hfRepo = "google/gemma-2-2b-it-GGUF",
            hfFile = "2b_it_v2.gguf",
            sizeMb = 1500,
            description = "Google's Gemma 2; vision-capable",
        ),
    )
