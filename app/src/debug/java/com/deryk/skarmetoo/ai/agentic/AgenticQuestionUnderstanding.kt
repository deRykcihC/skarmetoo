package com.deryk.skarmetoo.ai.agentic

import org.json.JSONArray
import org.json.JSONObject

/**
 * The first model turn determines the task and subject, independently of calendar interpretation.
 */
object AgenticQuestionUnderstanding {
  private fun string() = JSONObject().put("type", "string")

  private fun strings() = JSONObject().put("type", "array").put("items", string())

  val schema: JSONObject
    get() =
        AgenticContracts.objectSchema(
            linkedMapOf(
                "intent" to string().put("enum", JSONArray(Intent.entries.map { it.name })),
                "operation" to
                    string()
                        .put(
                            "enum",
                            JSONArray(
                                listOf(
                                    "sum",
                                    "count",
                                    "retrieve",
                                    "answer",
                                    "request_clarification"))),
                "subject_quote" to string(),
                "search_keywords" to strings(),
                "required_evidence" to strings(),
                "optional_evidence" to strings(),
                "clarification_question" to string()))

  val instruction =
      """
    First understand WHAT the original user is asking, without interpreting WHEN yet.
    Classify the intent and operation. Do not answer the user or invent saved evidence.
    Spending, expenses, money paid, purchases and buying summaries use calculate_spending + sum.
    Bare spending/payment questions also request sum. Explicit how-many/count uses count_transactions + count.
    Explicit list/show/find/which transactions uses find_transaction + retrieve.
    Other image content questions use the appropriate search_receipts or describe_image intent.
    If the intent is unclear, use unknown + request_clarification and ask one clarification_question.
    For understood requests clarification_question is empty.
    subject_quote copies ONLY an explicitly requested item/category/merchant from the original.
    General spending/payment/receipt words are not a subject; use an empty subject_quote then.
    Time phrases never belong in subject_quote, even when they follow spending. For example,
    "expenses yesterday and this month" has subject_quote=""; "coffee yesterday" has subject_quote="coffee".
    Generate search_keywords and observable required/optional evidence for ONE candidate image.
    Spending requires completed outgoing payment, final amount actually paid and currency.
    Never place dates, relative time words or aggregate totals in these semantic evidence fields.
    The next model turn will reread the original question and return its actual date windows.
    Return only the supplied JSON schema.
  """
          .trimIndent()

  fun parse(raw: String, original: String): QueryPlan {
    val value = AgenticContracts.parse(raw)
    AgenticContracts.validate(value, schema)
    val intent = Intent.valueOf(value.getString("intent"))
    val operation = Operation.valueOf(value.getString("operation"))
    require(
        when (intent) {
          Intent.calculate_spending -> operation == Operation.sum
          Intent.count_transactions -> operation == Operation.count
          Intent.find_transaction,
          Intent.search_receipts -> operation == Operation.retrieve
          Intent.describe_image -> operation in setOf(Operation.answer, Operation.retrieve)
          Intent.unknown -> operation == Operation.request_clarification
        }) {
          "The operation must match the model-classified intent."
        }
    val subject = AgenticPlanParser.subject(value.getString("subject_quote"), original)
    require(subject == null || !AgenticContracts.isTemporalConcept(subject)) {
      "subject_quote must contain only the requested item/category/merchant, never timing or the whole request. Use an empty subject_quote for general spending; keep any actual named item separately."
    }
    val clarification = value.getString("clarification_question").trim()
    require(intent != Intent.unknown || clarification.isNotEmpty()) {
      "Ask a clarification question for an unknown intent."
    }
    fun concepts(key: String) =
        AgenticContracts.strings(value, key).filterNot(AgenticContracts::isTemporalConcept)
    return QueryPlan(
        intent,
        operation,
        subject,
        null,
        concepts("search_keywords"),
        concepts("required_evidence"),
        concepts("optional_evidence"),
        clarification,
        if (intent == Intent.unknown) Operation.request_clarification else Operation.retrieve)
  }
}
