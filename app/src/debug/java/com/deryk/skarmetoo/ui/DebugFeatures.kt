package com.deryk.skarmetoo.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.LFM2_5_3B_MODEL
import com.deryk.skarmetoo.ai.agentic.AgenticScreen
import com.deryk.skarmetoo.ai.agentic.AgenticViewModel
import com.deryk.skarmetoo.ai.agentic.ChatSettingsSection
import com.deryk.skarmetoo.chat.ChatScreen
import com.deryk.skarmetoo.chat.ChatWorkflowModels
import com.deryk.skarmetoo.chat.RagChatViewModel
import com.deryk.skarmetoo.data.ScreenshotLibrary
import com.deryk.skarmetoo.ui.components.ExpressiveNavigationItem
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel

@Composable
internal fun rememberDevelopmentFeatures(
    viewModel: ScreenshotViewModel,
    currentRoute: String?,
): DevelopmentFeatures {
  val agentic: AgenticViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
  val revision by ScreenshotLibrary.removalRevision.collectAsState()
  LaunchedEffect(revision) { if (revision > 0) agentic.onLibraryChanged() }
  val isWorkspace = currentRoute == Routes.CHAT || currentRoute == Routes.SETTINGS
  val downloaded by viewModel.isGemma4Downloaded.collectAsState()
  LaunchedEffect(currentRoute, downloaded) { if (isWorkspace) agentic.refreshAvailability() }
  val releaseModels by rememberUpdatedState({ viewModel.releaseExternalModels() })
  val state by agentic.state.collectAsState()
  LaunchedEffect(currentRoute, state.busy) {
    if (currentRoute == Routes.SETTINGS && !state.busy) agentic.leave(releaseModels)
  }
  DisposableEffect(isWorkspace) { onDispose { if (isWorkspace) agentic.leave(releaseModels) } }
  return remember(viewModel, agentic) {
    object : DevelopmentFeatures {
      override val routes = listOf(Routes.LFM, Routes.CHAT)

      @Composable
      override fun NavigationItem(currentRoute: String?, onClick: () -> Unit, modifier: Modifier) {
        ExpressiveNavigationItem(
            selected = currentRoute == Routes.CHAT,
            onClick = onClick,
            icon = Icons.Rounded.AutoAwesome,
            label = "Chat",
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = modifier,
        )
      }

      @Composable
      override fun Screen(
          route: String,
          logoRes: Int,
          onManageModels: () -> Unit,
          onOpenSource: (Long) -> Unit,
      ) {
        if (route == Routes.LFM) {
          val context = LocalContext.current
          val chat: RagChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
          ChatScreen(
              chat,
              selectedModel = viewModel.selectedModel.collectAsState().value,
              onSetupModels = {
                val manager = GgufLlmManager.getInstance(context)
                if (manager.isModelDownloaded(LFM2_5_3B_MODEL) &&
                    manager.isModelDownloaded(ChatWorkflowModels.vision)) {
                  viewModel.setGgufModelAsActive(LFM2_5_3B_MODEL)
                } else onManageModels()
              },
              onManageModels = onManageModels,
              onOpenSource = onOpenSource,
          )
        } else {
          AgenticScreen(
              agentic,
              acquire = { viewModel.acquireExternalModels() },
              onOpenSource = onOpenSource,
              logoRes = logoRes,
          )
        }
      }

      @Composable
      override fun SettingsSection() {
        ChatSettingsSection(agentic, acquire = { viewModel.acquireExternalModels() })
      }
    }
  }
}
