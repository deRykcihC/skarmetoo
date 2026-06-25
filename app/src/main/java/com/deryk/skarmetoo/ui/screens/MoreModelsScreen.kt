package com.deryk.skarmetoo.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ai.GgufModelInfo
import com.deryk.skarmetoo.ai.ImportedGgufModelStore
import com.deryk.skarmetoo.ui.components.hapticOnClick
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun MoreModelsScreen(
    onBack: () -> Unit,
    onActivateModel: (GgufModelInfo) -> Unit = {},
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var importedModelFile by remember { mutableStateOf(ImportedGgufModelStore.getModelFile(context)) }
  var importedMmprojFile by remember {
    mutableStateOf(ImportedGgufModelStore.getMmprojFile(context))
  }
  var isImportingModel by remember { mutableStateOf(false) }
  var isImportingMmproj by remember { mutableStateOf(false) }

  fun importSelectedFile(uri: Uri, role: ImportedGgufModelStore.FileRole) {
    scope.launch {
      if (role == ImportedGgufModelStore.FileRole.MODEL) isImportingModel = true
      else isImportingMmproj = true
      try {
        val importedFile = ImportedGgufModelStore.importFile(context, uri, role)
        if (role == ImportedGgufModelStore.FileRole.MODEL) {
          importedModelFile = importedFile
        } else {
          importedMmprojFile = importedFile
        }
        Toast.makeText(
                context,
                context.getString(R.string.gguf_import_success, importedFile.name),
                Toast.LENGTH_SHORT)
            .show()
      } catch (e: Exception) {
        Toast.makeText(
                context,
                context.getString(
                    R.string.gguf_import_failed,
                    e.message ?: context.getString(R.string.unknown_error)),
                Toast.LENGTH_LONG)
            .show()
      } finally {
        if (role == ImportedGgufModelStore.FileRole.MODEL) isImportingModel = false
        else isImportingMmproj = false
      }
    }
  }

  val modelPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importSelectedFile(it, ImportedGgufModelStore.FileRole.MODEL) }
      }
  val mmprojPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importSelectedFile(it, ImportedGgufModelStore.FileRole.MMPROJ) }
      }
  val pickerMimeTypes = remember {
    arrayOf("application/octet-stream", "application/x-gguf", "*/*")
  }

  Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = hapticOnClick(onBack)) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.back),
        )
      }
      Spacer(modifier = Modifier.width(4.dp))
      Text(
          text = stringResource(R.string.more_models),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
      )
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = stringResource(R.string.import_section_title),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontWeight = FontWeight.Medium,
      )

      ImportFileSection(
          title = stringResource(R.string.import_gguf_model),
          description =
              when {
                isImportingModel -> stringResource(R.string.importing_model)
                importedModelFile != null -> importedModelFile!!.name
                else -> stringResource(R.string.import_gguf_model_desc)
              },
          fileType = ".gguf",
          enabled = !isImportingModel && !isImportingMmproj,
          onClick = { modelPicker.launch(pickerMimeTypes) },
      )

      ImportFileSection(
          title = stringResource(R.string.import_mmproj_model),
          description =
              when {
                isImportingMmproj -> stringResource(R.string.importing_model)
                importedMmprojFile != null -> importedMmprojFile!!.name
                else -> stringResource(R.string.import_mmproj_model_desc)
              },
          fileType = "mmproj .gguf",
          enabled = !isImportingModel && !isImportingMmproj,
          onClick = { mmprojPicker.launch(pickerMimeTypes) },
      )

      ImportGuideCard()

      Text(
          text = stringResource(R.string.loaded_models),
          modifier = Modifier.padding(top = 8.dp),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontWeight = FontWeight.Medium,
      )

      if (importedModelFile != null) {
        ImportedModelCard(
            modelFile = importedModelFile!!,
            mmprojFile = importedMmprojFile,
            onClick = { ImportedGgufModelStore.getModelInfo(context)?.let(onActivateModel) },
        )
      } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
          Text(
              text = stringResource(R.string.more_models_empty_desc),
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun ImportGuideCard() {
  Surface(
      modifier =
          Modifier.fillMaxWidth()
              .border(
                  width = 1.dp,
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                  shape = MaterialTheme.shapes.large,
              ),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
          shape = MaterialTheme.shapes.extraLarge,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
          modifier = Modifier.size(24.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
              text = "!",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold,
          )
        }
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = stringResource(R.string.import_guide_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.import_guide_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ImportFileSection(
    title: String,
    description: String,
    fileType: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
  OutlinedCard(
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceContainerHighest,
      ) {
        Text(
            text = fileType,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun ImportedModelCard(
    modelFile: File,
    mmprojFile: File?,
    onClick: () -> Unit,
) {
  val isReady = mmprojFile != null
  OutlinedCard(
      onClick = onClick,
      enabled = isReady,
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = modelFile.nameWithoutExtension,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = mmprojFile?.name ?: stringResource(R.string.import_mmproj_to_enable_vision),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Surface(
          shape = MaterialTheme.shapes.small,
          color =
              if (isReady) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceContainerHighest,
      ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          if (isReady) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
          }
          Text(
              text =
                  if (isReady) stringResource(R.string.ready)
                  else stringResource(R.string.projector_required),
              style = MaterialTheme.typography.labelSmall,
              color =
                  if (isReady) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}
