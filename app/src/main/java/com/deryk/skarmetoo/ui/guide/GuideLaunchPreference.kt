package com.deryk.skarmetoo.ui.guide

import android.content.SharedPreferences

/** The automatic introduction is once per installation, independent of app version updates. */
internal class GuideLaunchPreference(private val preferences: SharedPreferences) {
  val hasStarted: Boolean
    get() = preferences.getBoolean("guided_setup_started", false)

  fun markStarted() {
    preferences.edit().putBoolean("guided_setup_started", true).apply()
  }
}
