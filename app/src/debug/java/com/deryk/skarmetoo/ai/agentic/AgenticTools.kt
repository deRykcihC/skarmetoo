package com.deryk.skarmetoo.ai.agentic

import java.time.Clock
import java.time.ZoneId
import org.json.JSONObject

data class ToolExecution(
    val name: String,
    val arguments: String,
    val result: String,
    val elapsedMs: Long
)

class AgenticTools(
    private val clock: Clock = Clock.systemUTC(),
    private val maximumCalls: Int = 8192
) {
  private data class Registration(
      val schema: JSONObject,
      val handler: suspend (JSONObject) -> JSONObject
  )

  private val tools = linkedMapOf<String, Registration>()
  private val repetitions = mutableMapOf<String, Int>()
  val executions = mutableListOf<ToolExecution>()

  init {
    register("get_current_datetime", schema("timezone" to "string")) { o ->
      datetime(o.getString("timezone"))
    }
  }

  fun datetime(timezone: String): JSONObject {
    val zone = ZoneId.of(timezone)
    val now = clock.instant().atZone(zone)
    return JSONObject()
        .put("schema_version", 1)
        .put("timezone", zone.id)
        .put("datetime", now.toString())
        .put("today", now.toLocalDate().toString())
  }

  fun register(name: String, schema: JSONObject, handler: suspend (JSONObject) -> JSONObject) {
    require(name in NAMES)
    require(name !in tools)
    tools[name] = Registration(schema, handler)
  }

  fun declaration(name: String): JSONObject {
    val entry = tools[name] ?: error("Unknown tool $name")
    return JSONObject()
        .put("name", name)
        .put("description", "Application-controlled $name; use only supplied record IDs.")
        .put("parameters", entry.schema)
  }

  suspend fun dispatch(name: String, args: JSONObject): JSONObject {
    val entry = tools[name] ?: error("Unknown tool $name")
    AgenticContracts.validate(args, entry.schema)
    require(executions.size < maximumCalls) { "Tool-call budget exhausted" }
    val key = name + canonical(args)
    val repeated = (repetitions[key] ?: 0) + 1
    require(repeated <= 2) { "Repeated tool request stopped" }
    repetitions[key] = repeated
    val start = System.nanoTime()
    try {
      val result = entry.handler(args)
      executions +=
          ToolExecution(
              name, args.toString(), result.toString(), (System.nanoTime() - start) / 1_000_000)
      return result
    } catch (failure: Exception) {
      executions +=
          ToolExecution(
              name,
              args.toString(),
              JSONObject().put("error", failure.message).toString(),
              (System.nanoTime() - start) / 1_000_000)
      throw failure
    }
  }

  companion object {
    val NAMES =
        setOf(
            "get_current_datetime",
            "resolve_time_operations",
            "search_images",
            "get_image_metadata",
            "verify_image",
            "calculate_total",
            "get_verified_results")

    fun schema(vararg fields: Pair<String, String>) =
        AgenticContracts.objectSchema(
            fields.associate { (key, type) ->
              key to
                  JSONObject().put("type", type).also {
                    if (type == "array") it.put("items", JSONObject().put("type", "string"))
                  }
            })

    private fun canonical(o: JSONObject): String =
        o.keys().asSequence().sorted().joinToString { key -> "$key:${o.get(key)}" }
  }
}
