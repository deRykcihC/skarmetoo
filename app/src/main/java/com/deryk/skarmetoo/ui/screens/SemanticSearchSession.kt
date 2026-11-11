package com.deryk.skarmetoo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One query/model identity; background index refreshes preserve its last complete results. */
class SemanticSearchSession {
  var scores by mutableStateOf<Map<String, Float>>(emptyMap())
  var isSearching by mutableStateOf(false)
  var isSettled by mutableStateOf(false)
  var isPrepared by mutableStateOf(false)
  private var vector: FloatArray? = null

  suspend fun prepare(embed: suspend () -> FloatArray?) {
    isSearching = true
    try {
      delay(300)
      vector = embed()
      isPrepared = vector != null
      if (vector == null) isSettled = true
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (failure: Exception) {
      isSettled = true
      throw failure
    } finally {
      if (!isPrepared) isSearching = false
    }
  }

  suspend fun refresh(find: suspend (FloatArray) -> Map<String, Float>) {
    val query = vector ?: return
    isSearching = !isSettled
    try {
      val updated = find(query)
      scores = updated
      isSettled = true
    } finally {
      isSearching = false
    }
  }
}
