package com.deryk.skarmetoo.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatWorkflowSchemasTest {
  @Test
  fun extractionRequiresGenericValueUnitDateReferenceAndEvidenceInstructions() {
    val schema = JSONObject(ChatWorkflowSchemas.forStage(ChatWorkflowStage.PLAN)!!)
    assertEquals(
        setOf("verification_question", "extraction_question", "response_format"),
        schema.getJSONObject("properties").keys().asSequence().toSet())
    for (key in listOf("verification_question", "extraction_question")) {
      assertEquals(
          "string", schema.getJSONObject("properties").getJSONObject(key).getString("type"))
    }
    val template =
        schema
            .getJSONObject("properties")
            .getJSONObject("response_format")
            .getJSONObject("properties")
    ChatWorkflowSchemas.extractionFields.forEach {
      assertEquals("null", template.getJSONObject(it).getString("type"))
    }
    assertNull(ChatWorkflowSchemas.forStage(ChatWorkflowStage.VERIFY))
    assertNull(ChatWorkflowSchemas.forStage(ChatWorkflowStage.REWRITE))
    assertNull(ChatWorkflowSchemas.forStage(ChatWorkflowStage.EXTRACT))
  }

  @Test
  fun schemaChangesCannotReuseAnUnconstrainedResponse() {
    val plain = ChatWorkflowCacheKey.build("3B", ChatWorkflowStage.PLAN, "question", null)
    val constrained =
        ChatWorkflowCacheKey.build(
            "3B",
            ChatWorkflowStage.PLAN,
            "question",
            null,
            ChatWorkflowSchemas.forStage(ChatWorkflowStage.PLAN))
    assertNotEquals(plain, constrained)
  }
}
