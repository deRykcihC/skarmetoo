package com.deryk.skarmetoo.chat

import com.deryk.skarmetoo.ai.LFM2_5_3B_MODEL
import com.deryk.skarmetoo.ai.LFM2_5_MODEL
import com.deryk.skarmetoo.ai.applyChatTemplate
import org.junit.Assert.*
import org.junit.Test

class LfmChatFramingTest {
  @Test
  fun userInstructionEndsBeforeAssistantGenerationBegins() {
    val prompt = "Return a JSON object."
    for (model in listOf(LFM2_5_3B_MODEL, LFM2_5_MODEL)) {
      val wrapped = applyChatTemplate(model.chatTemplate, prompt)
      assertTrue(wrapped.startsWith("<|im_start|>user\n"))
      assertTrue(wrapped.contains("$prompt<|im_end|>\n"))
      assertTrue(wrapped.endsWith("<|im_start|>assistant\n"))
      assertFalse(wrapped.contains("<|user|>"))
    }
  }

  @Test
  fun changingTheVisionModelCannotReuseTheOld3bImageResponse() {
    val stage = ChatWorkflowStage.VERIFY
    val newKey =
        ChatWorkflowCacheKey.build(
            ChatWorkflowModels.forStage(stage).fileName, stage, "same generated prompt", null)
    val oldKey =
        ChatWorkflowCacheKey.build(LFM2_5_3B_MODEL.fileName, stage, "same generated prompt", null)
    assertNotEquals(oldKey, newKey)
    assertEquals(LFM2_5_MODEL, ChatWorkflowModels.forStage(ChatWorkflowStage.EXTRACT))
    for (textStage in
        listOf(ChatWorkflowStage.REWRITE, ChatWorkflowStage.PLAN, ChatWorkflowStage.ANSWER)) {
      assertEquals(LFM2_5_3B_MODEL, ChatWorkflowModels.forStage(textStage))
    }
  }
}
