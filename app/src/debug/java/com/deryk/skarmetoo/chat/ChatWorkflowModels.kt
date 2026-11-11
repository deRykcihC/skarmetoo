package com.deryk.skarmetoo.chat

import com.deryk.skarmetoo.ai.LFM2_5_3B_MODEL
import com.deryk.skarmetoo.ai.LFM2_5_MODEL

/** Prompt writing uses 3B; the two image stages use the faster 450M and its own projector. */
object ChatWorkflowModels {
  val planner = LFM2_5_3B_MODEL
  val vision = LFM2_5_MODEL

  fun forStage(stage: ChatWorkflowStage) =
      when (stage) {
        ChatWorkflowStage.VERIFY,
        ChatWorkflowStage.EXTRACT -> vision
        else -> planner
      }
}
