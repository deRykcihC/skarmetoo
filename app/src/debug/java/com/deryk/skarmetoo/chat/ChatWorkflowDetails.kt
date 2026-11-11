package com.deryk.skarmetoo.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatWorkflowDetails(steps: List<ChatWorkflowStep>) {
  if (steps.isEmpty()) return
  var expanded by remember { mutableStateOf(false) }
  TextButton(onClick = { expanded = !expanded }) {
    Text(if (expanded) "Hide prompts and responses" else "Show prompts and responses")
  }
  if (!expanded) return
  steps.forEachIndexed { index, step ->
    OutlinedCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${index + 1}. ${step.stage.label}" + (step.sourceId?.let { " · Source [$it]" } ?: ""),
            style = MaterialTheme.typography.titleSmall)
        if (step.model.isNotBlank()) Text(step.model, style = MaterialTheme.typography.labelSmall)
        if (step.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        else
            Text(
                if (step.cached) "Saved result" else "${step.elapsedMs / 1000.0}s",
                style = MaterialTheme.typography.labelSmall)
        if (step.systemPrompt.isNotBlank()) {
          Text("System instruction", style = MaterialTheme.typography.labelLarge)
          SelectionContainer { Text(step.systemPrompt, style = MaterialTheme.typography.bodySmall) }
        }
        Text("Prompt", style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(step.prompt, style = MaterialTheme.typography.bodySmall) }
        if (step.responseSchema.isNotBlank()) {
          Text("Required JSON format", style = MaterialTheme.typography.labelLarge)
          SelectionContainer {
            Text(step.responseSchema, style = MaterialTheme.typography.bodySmall)
          }
        }
        Text("LFM response", style = MaterialTheme.typography.labelLarge)
        SelectionContainer {
          Text(
              step.response.ifBlank { if (step.working) "Waiting for LFM…" else "No response" },
              style = MaterialTheme.typography.bodySmall)
        }
        if (step.dateEvidence.isNotBlank()) {
          Text("Printed event date", style = MaterialTheme.typography.labelLarge)
          SelectionContainer { Text(step.dateEvidence, style = MaterialTheme.typography.bodySmall) }
        }
        if (step.amountEvidence.isNotBlank()) {
          Text("Printed paid amount", style = MaterialTheme.typography.labelLarge)
          SelectionContainer {
            Text(step.amountEvidence, style = MaterialTheme.typography.bodySmall)
          }
        }
        if (step.error.isNotBlank())
            Text(
                step.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}
