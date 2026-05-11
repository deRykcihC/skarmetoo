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
    val chatTemplate: String = "",
)

val LFM_CHAT_TEMPLATE = "<|user|>\n{0}<|assistant|>\n"

fun GgufModelInfo.withChatTemplate(template: String) = copy(chatTemplate = template)

fun applyChatTemplate(template: String, prompt: String): String {
  if (template.isBlank()) return prompt
  return template.replace("{0}", prompt)
}

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
            chatTemplate = LFM_CHAT_TEMPLATE,
        ))

val LFM2_5_MODEL = LFM2_5_VARIANTS[0] // Q8_0 default

val PRESET_GGUF_MODELS = emptyList<GgufModelInfo>()
