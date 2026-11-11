package com.deryk.skarmetoo.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SystemOneClientTest {
  private val questions =
      linkedMapOf(
          "route" to
              SystemOneQuestion.Choice(
                  SystemOneContent.Text("Which team?"),
                  linkedMapOf("billing" to null, "shipping" to "Delivery questions")),
          "urgency" to
              SystemOneQuestion.Score(SystemOneContent.Text("How urgent?"), listOf("low", "high")),
          "angry" to SystemOneQuestion.Noul(SystemOneContent.Text("Is the customer angry?")))

  private val response =
      """{
    "model":"clef-flash",
    "answers":{
      "route":{"type":"choice","choice":"billing","probabilities":{"billing":0.8,"shipping":0.2},"confidence":0.6},
      "urgency":{"type":"score","score":0.75,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.25,"1":0.75},"confidence":0.5},
      "angry":{"type":"noul","noul":0.65}
    },
    "usage":{"input_tokens":239,"output_tokens":0}
  }"""

  @Test
  fun requestUsesSystemOneWireFormatAndExplicitNullOptions() {
    val image = SystemOneImage.fromBytes(byteArrayOf(1, 2, 3), "image/png")
    val request =
        SystemOneRequest(
            SystemOneContent.Json("{\"message\":\"charged twice\"}"),
            questions,
            listOf(image),
            "clef")
    val json = request.toJson()
    assertEquals("charged twice", json.getJSONObject("state").getString("message"))
    assertEquals("clef", json.getString("model"))
    assertEquals("data:image/png;base64,AQID", json.getJSONArray("images").getString(0))
    val encodedQuestions = json.getJSONObject("questions")
    assertTrue(encodedQuestions.getJSONObject("route").getJSONObject("criteria").has("billing"))
    assertTrue(encodedQuestions.getJSONObject("route").getJSONObject("criteria").isNull("billing"))
    assertEquals(
        "high", encodedQuestions.getJSONObject("urgency").getJSONArray("criteria").getString(1))
    assertFalse(encodedQuestions.getJSONObject("angry").has("criteria"))
  }

  @Test
  fun parsesNativeAnswersWithoutTurningNoulIntoABoolean() {
    val parsed = SystemOneCodec.decode(response, questions)
    assertEquals("billing", (parsed.answers.getValue("route") as SystemOneAnswer.Choice).choice)
    val score = parsed.answers.getValue("urgency") as SystemOneAnswer.Score
    assertEquals(0.75, score.score, 0.0)
    assertEquals("high", score.legend[1])
    assertEquals(
        0.65, (parsed.answers.getValue("angry") as SystemOneAnswer.Noul).probabilityTrue, 0.0)
    assertEquals(239L, parsed.usage.inputTokens)
    assertEquals(0L, parsed.usage.outputTokens)
  }

  @Test
  fun rejectsMissingQuestionsUnknownOptionsAndWrongAnswerTypes() {
    val missing = JSONObject(response).also { it.getJSONObject("answers").remove("angry") }
    assertThrows(SystemOneProtocolException::class.java) {
      SystemOneCodec.decode(missing.toString(), questions)
    }
    assertThrows(SystemOneProtocolException::class.java) {
      SystemOneCodec.decode(
          response.replace("\"choice\":\"billing\"", "\"choice\":\"unknown\""), questions)
    }
    assertThrows(SystemOneProtocolException::class.java) {
      SystemOneCodec.decode(response.replace("\"type\":\"noul\"", "\"type\":\"choice\""), questions)
    }
  }

  @Test
  fun rejectsInvalidProbabilitiesScoresAndGeneratedResponses() {
    for (invalid in
        listOf(
            response.replace("\"noul\":0.65", "\"noul\":1.5"),
            response.replace("\"shipping\":0.2", "\"shipping\":0.1"),
            response.replace("\"score\":0.75", "\"score\":2"),
            response.replace("\"confidence\":0.6", "\"confidence\":\"0.6\""),
            response.replace("\"output_tokens\":0", "\"output_tokens\":4"),
            "{\"choices\":[{\"message\":{\"content\":\"true\"}}]}")) assertThrows(
        SystemOneProtocolException::class.java) {
          SystemOneCodec.decode(invalid, questions)
        }
  }

  @Test
  fun validatesStructuredContentAndQuestionConstraints() {
    assertThrows(IllegalArgumentException::class.java) {
      SystemOneRequest(SystemOneContent.Text("x"), emptyMap()).toJson()
    }
    assertThrows(IllegalArgumentException::class.java) {
      SystemOneRequest(SystemOneContent.Json("{} trailing"), questions).toJson()
    }
    assertThrows(IllegalArgumentException::class.java) {
      SystemOneRequest(SystemOneContent.Json("42"), questions).toJson()
    }
    val score = SystemOneQuestion.Score(SystemOneContent.Text("score"), listOf("only one"))
    assertThrows(IllegalArgumentException::class.java) {
      SystemOneRequest(SystemOneContent.Text("x"), mapOf("q" to score)).toJson()
    }
  }

  @Test
  fun normalizesEndpointAndRejectsUnsafeConfiguration() {
    for (url in
        listOf(
            "https://example.com",
            "https://example.com/",
            "https://example.com/v1/",
            "https://example.com/v1/systemone")) assertEquals(
        "https://example.com/v1/systemone", SystemOneConfig(url).endpoint)
    assertEquals(
        "https://example.com/proxy/v1/systemone",
        SystemOneConfig("https://example.com/proxy").endpoint)
    for (url in
        listOf(
            "file:///tmp/model",
            "https://key@example.com",
            "https://example.com?key=secret",
            "https://example.com#x")) assertThrows(IllegalArgumentException::class.java) {
      SystemOneConfig(url)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SystemOneConfig("https://example.com", apiKey = "key\r\nX-Header: x")
    }
  }

  @Test
  fun postsRealHttpRequestAndParsesResponse() = runBlocking {
    val received = AtomicReference<JSONObject>()
    val auth = AtomicReference<String>()
    withServer(
        200,
        response,
        onRequest = { body, authorization ->
          received.set(JSONObject(body))
          auth.set(authorization)
        }) { url ->
          val result =
              SystemOneClient(SystemOneConfig(url, apiKey = "test-key"))
                  .evaluate(SystemOneRequest(SystemOneContent.Text("charged twice"), questions))
          assertEquals("Bearer test-key", auth.get())
          assertEquals("charged twice", received.get().getString("state"))
          assertEquals("clef-flash", result.model)
        }
  }

  @Test
  fun preservesUnsupportedImageErrorWithoutGenerationFallback() = runBlocking {
    withServer(501, "{\"error\":{\"message\":\"Image input not supported by clef\"}}") { url ->
      try {
        SystemOneClient(SystemOneConfig(url))
            .evaluate(
                SystemOneRequest(
                    SystemOneContent.Text("x"),
                    questions,
                    listOf(SystemOneImage.fromBytes(byteArrayOf(1)))))
        fail("Expected HTTP error")
      } catch (error: SystemOneHttpException) {
        assertEquals(501, error.statusCode)
        assertEquals("Image input not supported by clef", error.serverMessage)
      }
    }
  }

  @Test
  fun boundsResponseSizeAndRejectsInvalidJson() = runBlocking {
    withServer(200, response) { url ->
      try {
        SystemOneClient(SystemOneConfig(url, maxResponseBytes = 20))
            .evaluate(SystemOneRequest(SystemOneContent.Text("x"), questions))
        fail("Expected size limit")
      } catch (error: SystemOneProtocolException) {
        assertTrue(error.message!!.contains("size limit"))
      }
    }
    withServer(200, "not JSON") { url ->
      try {
        SystemOneClient(SystemOneConfig(url))
            .evaluate(SystemOneRequest(SystemOneContent.Text("x"), questions))
        fail("Expected invalid response")
      } catch (error: SystemOneProtocolException) {
        assertTrue(error.message!!.startsWith("Invalid System One response"))
      }
    }
  }

  private suspend fun withServer(
      status: Int,
      body: String,
      onRequest: (String, String) -> Unit = { _, _ -> },
      block: suspend (String) -> Unit
  ) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/systemone") { exchange ->
      try {
        assertEquals("POST", exchange.requestMethod)
        assertEquals(
            "application/json; charset=utf-8", exchange.requestHeaders.getFirst("Content-Type"))
        onRequest(
            exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) },
            exchange.requestHeaders.getFirst("Authorization").orEmpty())
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      } finally {
        exchange.close()
      }
    }
    server.start()
    try {
      block("http://127.0.0.1:${server.address.port}")
    } finally {
      server.stop(0)
    }
  }
}
