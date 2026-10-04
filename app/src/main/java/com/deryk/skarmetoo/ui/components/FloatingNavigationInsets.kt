package com.deryk.skarmetoo.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/** Scrollable content draws behind the floating bar; controls and scroll endings clear it. */
val LocalFloatingNavigationBottomInset = compositionLocalOf { 0.dp }
val LocalFloatingNavigationEndInset = compositionLocalOf { 0.dp }
