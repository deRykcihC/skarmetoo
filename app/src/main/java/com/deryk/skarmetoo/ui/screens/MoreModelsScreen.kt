package com.deryk.skarmetoo.ui.screens

import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.GgufModelInfo
import com.deryk.skarmetoo.ai.ImportedGgufModelStore
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.viewmodel.ModelType
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun MoreModelsScreen(
    viewModel: ScreenshotViewModel,
    onBack: () -> Unit,
    onActivateModel: (GgufModelInfo) -> Unit = {},
) {
  val context = LocalContext.current
  val ggufManager = remember(context) { GgufLlmManager.getInstance(context) }
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val scope = rememberCoroutineScope()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val activeGgufModel by ggufManager.activeModelInfo.collectAsState()
  val isGemma3nDownloaded by viewModel.isGemma3nDownloaded.collectAsState()
  val isGemma4Downloaded by viewModel.isGemma4Downloaded.collectAsState()
  var importedModelFile by remember { mutableStateOf(ImportedGgufModelStore.getModelFile(context)) }
  var importedMmprojFile by remember {
    mutableStateOf(ImportedGgufModelStore.getMmprojFile(context))
  }
  var isImportingModel by remember { mutableStateOf(false) }
  var isImportingMmproj by remember { mutableStateOf(false) }
  var downloadedGgufModels by remember { mutableStateOf(ggufManager.getDownloadedModels()) }

  fun refreshAvailableModels() {
    importedModelFile = ImportedGgufModelStore.getModelFile(context)
    importedMmprojFile = ImportedGgufModelStore.getMmprojFile(context)
    downloadedGgufModels = ggufManager.getDownloadedModels()
  }

  fun showDeleteResult(success: Boolean) {
    Toast.makeText(
            context,
            context.getString(
                if (success) R.string.model_deleted else R.string.model_delete_failed),
            Toast.LENGTH_SHORT)
        .show()
  }

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
        refreshAvailableModels()
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

  val importPaneContent: @Composable ColumnScope.() -> Unit = {
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
  }

  val availableModelsPaneContent: @Composable ColumnScope.() -> Unit = {
    Text(
        text = stringResource(R.string.available_models),
        modifier = Modifier.padding(top = if (isLandscape) 0.dp else 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
    )

    if (isGemma3nDownloaded) {
      AvailableModelCard(
          title = stringResource(R.string.model_gemma3n),
          description = stringResource(R.string.model_gemma3n_desc),
          selected = selectedModel == ModelType.GEMMA_3N,
          onClick = {
            viewModel.setSelectedModel(ModelType.GEMMA_3N)
            onBack()
          },
          onDelete = {
            scope.launch { showDeleteResult(viewModel.deleteDownloadedModel(ModelType.GEMMA_3N)) }
          },
      )
    }

    if (isGemma4Downloaded) {
      AvailableModelCard(
          title = stringResource(R.string.model_gemma4),
          description = stringResource(R.string.model_gemma4_desc),
          selected = selectedModel == ModelType.GEMMA_4,
          onClick = {
            viewModel.setSelectedModel(ModelType.GEMMA_4)
            onBack()
          },
          onDelete = {
            scope.launch { showDeleteResult(viewModel.deleteDownloadedModel(ModelType.GEMMA_4)) }
          },
      )
    }

    downloadedGgufModels.forEach { model ->
      val isLfmModel = model.fileName == com.deryk.skarmetoo.ai.LFM2_5_MODEL.fileName
      AvailableModelCard(
          title = if (isLfmModel) stringResource(R.string.model_lfm_title) else model.displayName,
          description =
              if (isLfmModel) stringResource(R.string.model_lfm_desc) else model.description,
          selected = selectedModel == ModelType.GGUF && activeGgufModel?.fileName == model.fileName,
          onClick = { onActivateModel(model) },
          onDelete = {
            scope.launch {
              val success = viewModel.deleteDownloadedGgufModel(model)
              refreshAvailableModels()
              showDeleteResult(success)
            }
          },
      )
    }

    val incompleteImportedModel =
        importedModelFile?.takeIf { modelFile ->
          downloadedGgufModels.none {
            File(context.filesDir, it.fileName).absolutePath == modelFile.absolutePath
          }
        }
    if (incompleteImportedModel != null) {
      AvailableModelCard(
          title = incompleteImportedModel.nameWithoutExtension,
          description = stringResource(R.string.import_mmproj_to_enable_vision),
          enabled = false,
          onClick = {},
          onDelete = {
            scope.launch {
              val success = ImportedGgufModelStore.deleteImportedFiles(context)
              refreshAvailableModels()
              showDeleteResult(success)
            }
          },
      )
    }

    if (!isGemma3nDownloaded &&
        !isGemma4Downloaded &&
        downloadedGgufModels.isEmpty() &&
        incompleteImportedModel == null) {
      Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
      ) {
        Text(
            text = stringResource(R.string.available_models_empty_desc),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
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

    if (isLandscape) {
      Row(
          modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = importPaneContent,
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = availableModelsPaneContent,
        )
      }
    } else {
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        importPaneContent()
        availableModelsPaneContent()
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
private fun AvailableModelCard(
    title: String,
    description: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
  OutlinedCard(
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      colors =
          CardDefaults.outlinedCardColors(
              containerColor =
                  if (selected) MaterialTheme.colorScheme.secondaryContainer
                  else MaterialTheme.colorScheme.surface,
          ),
      border =
          if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
          else CardDefaults.outlinedCardBorder(),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      IconButton(
          onClick = hapticOnClick(onDelete),
          modifier = Modifier.size(36.dp),
      ) {
        Icon(
            imageVector = Icons.Rounded.DeleteOutline,
            contentDescription = stringResource(R.string.delete_model),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}
