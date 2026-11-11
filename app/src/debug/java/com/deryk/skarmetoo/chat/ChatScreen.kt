package com.deryk.skarmetoo.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.deryk.skarmetoo.ai.LFM2_5_3B_MODEL
import com.deryk.skarmetoo.ui.components.LocalFloatingNavigationBottomInset
import com.deryk.skarmetoo.ui.components.LocalFloatingNavigationEndInset
import com.deryk.skarmetoo.viewmodel.ModelType

@Composable
fun ChatScreen(
    viewModel: RagChatViewModel,
    selectedModel: ModelType?,
    onOpenSource: (Long) -> Unit,
    onManageModels: () -> Unit,
    onSetupModels: () -> Unit = onManageModels,
) {
  val turns by viewModel.turns.collectAsState()
  val busy by viewModel.busy.collectAsState()
  var question by rememberSaveable { mutableStateOf("") }
  var testing by rememberSaveable { mutableStateOf(false) }
  val ggufModel by viewModel.ggufModel.collectAsState()
  val receiptDateOrder by viewModel.receiptDateOrder.collectAsState()
  var choosingDateOrder by remember { mutableStateOf(false) }
  val listState = androidx.compose.foundation.lazy.rememberLazyListState()
  LaunchedEffect(turns.size) {
    if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
  }
  Column(
      Modifier.fillMaxSize()
          .imePadding()
          .padding(
              start = 16.dp,
              end = 16.dp + LocalFloatingNavigationEndInset.current,
              bottom =
                  maxOf(
                      LocalFloatingNavigationBottomInset.current,
                      WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
              "LFM", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
          TextButton(
              enabled = !busy && !testing && turns.isNotEmpty(), onClick = { viewModel.clear() }) {
                Text("New chat")
              }
          TextButton(onClick = onManageModels, enabled = !busy) { Text("Models") }
        }
        Text(
            if (selectedModel == ModelType.GGUF && ggufModel?.fileName == LFM2_5_3B_MODEL.fileName)
                "3B prompts · 450M images · On-device"
            else
                "${if (selectedModel == ModelType.GGUF) ggufModel?.displayName ?: "GGUF" else selectedModel?.displayName ?: "No model selected"} · On-device",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
              selected = !testing,
              onClick = { testing = false },
              label = { Text("Saved images") },
              enabled = !busy)
          FilterChip(
              selected = testing,
              onClick = { testing = true },
              label = { Text("GGUF test") },
              enabled = !busy)
        }
        if (testing) {
          ChatModelTest(viewModel, selectedModel, onManageModels)
          return@Column
        }
        Box {
          TextButton(onClick = { choosingDateOrder = true }, enabled = !busy) {
            Text(
                "Receipt dates: ${receiptDateOrder.label}",
                style = MaterialTheme.typography.bodySmall)
          }
          DropdownMenu(
              expanded = choosingDateOrder, onDismissRequest = { choosingDateOrder = false }) {
                ChatReceiptDateOrder.entries.forEach { order ->
                  DropdownMenuItem(
                      text = { Text(order.label) },
                      onClick = {
                        viewModel.setReceiptDateOrder(order)
                        choosingDateOrder = false
                      })
                }
              }
        }
        if (selectedModel != ModelType.GGUF ||
            ggufModel?.fileName != LFM2_5_3B_MODEL.fileName ||
            !viewModel.hasWorkflowModels()) {
          OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
              Text(
                  "LFM uses LFM2.5 VL 3B to write prompts and the faster LFM2.5 VL 450M to read images. Download both models and their matching projectors.",
                  style = MaterialTheme.typography.bodySmall)
              TextButton(onClick = onSetupModels, enabled = !busy) { Text("Set up LFM models") }
            }
          }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
              if (turns.isEmpty())
                  item {
                    Card(Modifier.fillMaxWidth()) {
                      Column(
                          Modifier.padding(20.dp),
                          verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Ask about your saved images",
                                style = MaterialTheme.typography.titleLarge)
                            Text(
                                "LFM2.5 VL 3B turns your question into image checks and a JSON extraction format. LFM2.5 VL 450M verifies and reads matching images. Saved descriptions narrow the search first.")
                            listOf(
                                    "How much did I spend last week?",
                                    "Find my hotel booking",
                                    "What did I save yesterday?")
                                .forEach { sample ->
                                  OutlinedButton(onClick = { question = sample }) { Text(sample) }
                                }
                            Text(
                                "Try last month, 5 October 2026, the first week of September, September 2026, or a date range. Weeks within a month mean 1–7, 8–14, and so on. Event dates in images take priority; the image taken date is used when no event date is shown. Ask for images taken or imported in a period to use that date instead.",
                                style = MaterialTheme.typography.bodySmall)
                          }
                    }
                  }
              itemsIndexed(turns) { _, turn ->
                Card(Modifier.fillMaxWidth()) {
                  Column(
                      Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(turn.question, style = MaterialTheme.typography.titleMedium)
                        if (turn.retrievalNote.isNotBlank())
                            Text(turn.retrievalNote, style = MaterialTheme.typography.bodySmall)
                        if (turn.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(turn.detail, style = MaterialTheme.typography.labelMedium)
                        if (turn.answer.isNotBlank()) Text(turn.answer)
                        ChatWorkflowDetails(turn.steps)
                        var expanded by remember { mutableStateOf(false) }
                        val shownSources = if (expanded) turn.sources else turn.sources.take(5)
                        shownSources.forEach { entry ->
                          OutlinedCard(
                              onClick = { onOpenSource(entry.id) },
                              modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                      AsyncImage(
                                          model = entry.imageUri,
                                          contentDescription = "Source image ${entry.id}",
                                          modifier = Modifier.size(64.dp),
                                          contentScale = ContentScale.Crop)
                                      Column(Modifier.weight(1f)) {
                                        Text(
                                            "Source [${entry.id}] · Open image",
                                            style = MaterialTheme.typography.labelLarge)
                                        Text(
                                            entry.summary
                                                .ifBlank { entry.note.ifBlank { entry.tags } }
                                                .take(250),
                                            style = MaterialTheme.typography.bodySmall)
                                      }
                                    }
                              }
                        }
                        if (turn.sources.size > 5)
                            TextButton(onClick = { expanded = !expanded }) {
                              Text(
                                  if (expanded) "Show fewer sources"
                                  else "Show all ${turn.sources.size} sources")
                            }
                      }
                }
              }
            }
        if (busy) TextButton(onClick = { viewModel.stop() }) { Text("Stop after current step") }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(
                  value = question,
                  onValueChange = { question = it },
                  modifier = Modifier.weight(1f),
                  placeholder = { Text("Ask a question") },
                  supportingText = {
                    if (question.isNotBlank()) Text("LFM will interpret each requested period.")
                  },
                  maxLines = 4)
              FilledIconButton(
                  enabled = question.isNotBlank() && !busy,
                  onClick = {
                    viewModel.ask(question.trim(), selectedModel)
                    question = ""
                  }) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send question")
                  }
            }
      }
}
