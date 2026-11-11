package com.deryk.skarmetoo.ai.agentic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.ai.WorkflowBackend

@Composable
fun ChatSettingsSection(viewModel: AgenticViewModel, acquire: suspend () -> Unit) {
  val state by viewModel.state.collectAsState()
  var expanded by remember { mutableStateOf(false) }
  Column {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  Surface(
                      shape = CircleShape,
                      color = MaterialTheme.colorScheme.secondaryContainer,
                      modifier = Modifier.size(34.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                          Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(20.dp))
                        }
                      }
                  Column {
                    Text(
                        "Chat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium)
                    Text(
                        "On-device image questions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
            Text("Gemma 4 E2B · ${state.indexed} indexed images")
            if (!state.modelReady) Text("Set up Gemma 4 E2B in Analysis Model to use Chat.")
            Box {
              OutlinedButton(onClick = { expanded = true }, enabled = !state.busy) {
                Text("Backend: ${state.backend.name.replace('_', '/')}")
              }
              DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                WorkflowBackend.entries.forEach { backend ->
                  DropdownMenuItem(
                      text = {
                        Text(
                            "LLM ${backend.name.substringBefore('_')} / Vision ${backend.name.substringAfter('_')}")
                      },
                      enabled = !state.busy,
                      onClick = {
                        viewModel.selectBackend(backend)
                        expanded = false
                      })
                }
              }
            }
            if (!state.embeddingReady) {
              Text("EmbeddingGemma 2 Text + Vision · 388 MB")
              Button(onClick = { viewModel.download() }, enabled = !state.busy) {
                Text("Download image search model")
              }
            }
            state.downloadProgress?.let {
              LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
              Text("${(it * 100).toInt()}% downloaded")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedButton(
                  onClick = { viewModel.index(acquire) },
                  enabled = state.embeddingReady && !state.busy) {
                    Text("Index library")
                  }
              if (state.busy) TextButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
            }
            if (state.busy) {
              Text(state.progress.message)
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (state.progress.message == "Index ready") {
              Text(state.progress.message)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
          }
        }
    Spacer(Modifier.height(16.dp))
  }
}
