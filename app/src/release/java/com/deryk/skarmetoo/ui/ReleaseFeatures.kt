package com.deryk.skarmetoo.ui

import androidx.compose.runtime.Composable
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel

/** Chat implementation and dependencies are in src/debug and never compiled into release. */
@Composable
internal fun rememberDevelopmentFeatures(
    viewModel: ScreenshotViewModel,
    currentRoute: String?,
): DevelopmentFeatures? = null
