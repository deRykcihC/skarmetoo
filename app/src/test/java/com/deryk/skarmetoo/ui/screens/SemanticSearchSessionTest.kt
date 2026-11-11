package com.deryk.skarmetoo.ui.screens

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SemanticSearchSessionTest {
  @Test
  fun backgroundRefreshKeepsResultsVisibleAndReusesQueryVector(): Unit = runBlocking {
    val session = SemanticSearchSession()
    var embeddings = 0
    val vector = floatArrayOf(1f)
    session.prepare {
      embeddings++
      vector
    }
    session.refresh { mapOf("first" to 0.8f) }
    val gate = CompletableDeferred<Unit>()
    val refresh =
        launch(start = CoroutineStart.UNDISPATCHED) {
          session.refresh {
            assertSame(vector, it)
            gate.await()
            mapOf("first" to 0.8f, "new" to 0.9f)
          }
        }
    assertTrue(session.isSettled)
    assertFalse(session.isSearching)
    assertEquals(mapOf("first" to 0.8f), session.scores)
    gate.complete(Unit)
    refresh.join()
    assertEquals(2, session.scores.size)
    assertEquals(1, embeddings)
  }

  @Test
  fun cancelledRefreshPreservesCompletedResults(): Unit = runBlocking {
    val session = SemanticSearchSession()
    session.prepare { floatArrayOf(1f) }
    session.refresh { mapOf("first" to 0.8f) }
    val refresh =
        launch(start = CoroutineStart.UNDISPATCHED) { session.refresh { awaitCancellation() } }
    refresh.cancelAndJoin()
    assertEquals(mapOf("first" to 0.8f), session.scores)
    assertTrue(session.isSettled)
    assertFalse(session.isSearching)
    session.refresh { emptyMap() }
    assertTrue(session.scores.isEmpty())
    assertTrue(session.isSettled)
  }

  @Test
  fun unavailableQueryStopsLoading(): Unit = runBlocking {
    val session = SemanticSearchSession()
    session.prepare { null }
    assertTrue(session.isSettled)
    assertFalse(session.isSearching)
    assertFalse(session.isPrepared)
  }
}
