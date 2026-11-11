package com.deryk.skarmetoo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Variant integration points. The release implementation supplies no development features. */
internal interface DevelopmentFeatures {
  val routes: List<String>

  @Composable fun NavigationItem(currentRoute: String?, onClick: () -> Unit, modifier: Modifier)

  @Composable
  fun Screen(
      route: String,
      logoRes: Int,
      onManageModels: () -> Unit,
      onOpenSource: (Long) -> Unit,
  )

  @Composable fun SettingsSection()
}
