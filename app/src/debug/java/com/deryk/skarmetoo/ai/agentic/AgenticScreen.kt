package com.deryk.skarmetoo.ai.agentic

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ui.components.LocalFloatingNavigationBottomInset
import com.deryk.skarmetoo.ui.components.LocalFloatingNavigationEndInset
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgenticScreen(
    viewModel: AgenticViewModel,
    acquire: suspend () -> Unit,
    onOpenSource: (Long) -> Unit,
    @DrawableRes logoRes: Int = R.drawable.app_logo
) {
  val state by viewModel.state.collectAsState()
  var question by rememberSaveable { mutableStateOf("") }
  val context = LocalContext.current
  val window =
      remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.window
      }
  DisposableEffect(window) {
    val originalMode = window?.attributes?.softInputMode
    if (originalMode != null) {
      // Compose handles the keyboard inset; prevent Android from also panning the whole page.
      window.setSoftInputMode(
          (originalMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
              WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    onDispose { if (originalMode != null) window?.setSoftInputMode(originalMode) }
  }
  val canAsk = question.isNotBlank() && state.modelReady && state.embeddingReady && !state.busy
  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  val sendQuestion = {
    if (canAsk) {
      focusManager.clearFocus()
      keyboard?.hide()
      viewModel.ask(question, acquire)
    }
  }
  val density = LocalDensity.current
  val imeInsets = WindowInsets.ime
  LaunchedEffect(imeInsets, density, focusManager) {
    var keyboardWasOpen = false
    snapshotFlow { imeInsets.getBottom(density) > 0 }
        .collect { keyboardOpen ->
          if (keyboardWasOpen && !keyboardOpen) focusManager.clearFocus()
          keyboardWasOpen = keyboardOpen
        }
  }
  val navigationInset =
      maxOf(
          LocalFloatingNavigationBottomInset.current,
          WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
  // Follow Android's animated IME inset, stopping at the navigation clearance instead of
  // switching a second padding on only after the keyboard has finished closing.
  val composerInsets =
      imeInsets.union(WindowInsets(bottom = navigationInset)).only(WindowInsetsSides.Bottom)
  Column(Modifier.fillMaxSize().padding(end = LocalFloatingNavigationEndInset.current)) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
                .heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically) {
          Image(
              painter = painterResource(id = logoRes),
              contentDescription = stringResource(R.string.logo),
              modifier = Modifier.size(36.dp))
          Spacer(Modifier.width(10.dp))
          Text("Chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
          if (state.busy) {
            item {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.progress.message)
                LinearProgressIndicator(Modifier.fillMaxWidth())
              }
            }
          }
          state.error?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
          }
          state.result?.let { result ->
            item {
              Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(result.answer, style = MaterialTheme.typography.bodyLarge)
                  if (result.requestedPeriods.isNotEmpty()) {
                    Text(
                        "${result.requestedPeriods.size} requested ${if (result.requestedPeriods.size == 1) "period" else "periods"}",
                        style = MaterialTheme.typography.titleSmall)
                    result.requestedPeriods.forEach { period ->
                      Text(period, style = MaterialTheme.typography.bodyMedium)
                    }
                  }
                  Text(
                      "${result.elapsedMs/1000.0}s · ${result.state.name.lowercase().replace('_',' ')}",
                      style = MaterialTheme.typography.labelMedium)
                }
              }
            }
            item {
              val sources = state.sources.filter { it.id in result.sources }
              LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(sources, key = { it.id }) { image ->
                  AsyncImage(
                      model = image.uri,
                      contentDescription = "Source receipt",
                      contentScale = ContentScale.Fit,
                      modifier =
                          Modifier.size(120.dp, 180.dp).clickable { onOpenSource(image.entryId) })
                }
              }
            }
            if (result.calculation.unresolvedIds.isNotEmpty()) {
              item {
                Text(
                    "Needs review · Excluded from totals",
                    style = MaterialTheme.typography.titleSmall)
              }
              item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  items(
                      state.sources.filter { it.id in result.calculation.unresolvedIds },
                      key = { it.id }) { image ->
                        AsyncImage(
                            model = image.uri,
                            contentDescription = "Image needing review",
                            contentScale = ContentScale.Fit,
                            modifier =
                                Modifier.size(120.dp, 180.dp).clickable {
                                  onOpenSource(image.entryId)
                                })
                      }
                }
              }
            }
          }
        }
    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 2.dp) {
      ChatQuestionComposer(
          question = question,
          onQuestionChange = { question = it },
          busy = state.busy,
          canSend = canAsk,
          onSend = { sendQuestion() },
          onStop = { viewModel.cancel() },
          modifier =
              Modifier.fillMaxWidth()
                  .windowInsetsPadding(composerInsets)
                  .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
    }
  }
}
