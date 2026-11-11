package com.deryk.skarmetoo.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.viewmodel.ModelType

data class ChatModelTestResult(
    val model: String = "",
    val output: String = "",
    val error: String = "",
    val working: Boolean = false,
    val elapsedMs: Long = 0
)

object ChatModelProbes {
  const val text = "Reply with exactly: GGUF text generation works"
  const val vision =
      "Describe the attached image. Read any visible text and identify the main objects. Do not invent details you cannot see."
  const val decision =
      "Inspect the attached image. Is it evidence of a completed purchase? Return only JSON with keys decision (one of completed_purchase, not_purchase, uncertain) and evidence (a short quote from the image). Do not invent an amount or claim to return model probabilities."
  const val limitation =
      "These tests use generated text. Clef's native JEV / System One decision probabilities are not exposed by the app's existing GGUF bridge. A JSON answer does not verify JEV support."
}

@Composable
fun ChatModelTest(
    viewModel: RagChatViewModel,
    selectedModel: ModelType?,
    onManageModels: () -> Unit
) {
  val model by viewModel.ggufModel.collectAsState()
  val state by viewModel.ggufState.collectAsState()
  val result by viewModel.testResult.collectAsState()
  val busy by viewModel.busy.collectAsState()
  var prompt by rememberSaveable { mutableStateOf(ChatModelProbes.vision) }
  var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
  val picker =
      rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        imageUri = uri?.toString() ?: imageUri
      }
  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GGUF compatibility test", style = MaterialTheme.typography.titleLarge)
            Text(model?.displayName ?: "No GGUF model loaded")
            Text(
                when (val current = state) {
                  is GgufLlmManager.LlmState.Error -> "Load error: ${current.message}"
                  GgufLlmManager.LlmState.Loading -> "Loading model…"
                  GgufLlmManager.LlmState.Ready -> "Ready"
                  GgufLlmManager.LlmState.Generating -> "Generating…"
                  else -> "Import and activate a model to begin"
                },
                style = MaterialTheme.typography.bodySmall)
            model?.let {
              Text(
                  "Model: ${it.fileName}\nProjector: ${it.mmprojFile.ifBlank { "None · text only" }}",
                  style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Use Manage models to activate a GGUF and its matching projector. LFM2.5 VL 450M and 3B support both text and image tests; other vision models require an attached image.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onManageModels, enabled = !busy) { Text("Manage models") }
          }
        }
        Text(ChatModelProbes.limitation, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
              selected = prompt == ChatModelProbes.text,
              onClick = { prompt = ChatModelProbes.text },
              label = { Text("Text") })
          FilterChip(
              selected = prompt == ChatModelProbes.vision,
              onClick = { prompt = ChatModelProbes.vision },
              label = { Text("Vision") })
          FilterChip(
              selected = prompt == ChatModelProbes.decision,
              onClick = { prompt = ChatModelProbes.decision },
              label = { Text("Decision JSON") })
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Test prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 7,
            enabled = !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
            Text("Attach image")
          }
          if (imageUri != null)
              TextButton(onClick = { imageUri = null }, enabled = !busy) { Text("Remove image") }
        }
        imageUri?.let {
          AsyncImage(
              model = it,
              contentDescription = "Attached test image",
              modifier = Modifier.fillMaxWidth().height(160.dp))
        }
        Button(
            enabled = !busy && prompt.isNotBlank(),
            onClick = { viewModel.testGguf(prompt, imageUri, selectedModel) },
            modifier = Modifier.fillMaxWidth()) {
              Text("Run GGUF test")
            }
        result?.let { test ->
          Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Raw model result", style = MaterialTheme.typography.titleMedium)
              if (test.working) LinearProgressIndicator(Modifier.fillMaxWidth())
              Text(
                  if (test.working) "${test.model} · Running…"
                  else
                      "${test.model} · ${test.elapsedMs / 1000.0}s · ${if (test.error.isBlank()) "Generated output" else "Failed"}",
                  style = MaterialTheme.typography.bodySmall)
              if (test.error.isNotBlank()) Text(test.error, color = MaterialTheme.colorScheme.error)
              SelectionContainer {
                Text(
                    test.output.ifBlank {
                      if (test.working) "Waiting for output…" else "No output"
                    })
              }
            }
          }
        }
        Spacer(Modifier.height(12.dp))
      }
}
