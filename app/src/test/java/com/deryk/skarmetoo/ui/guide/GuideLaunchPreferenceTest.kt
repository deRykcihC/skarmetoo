package com.deryk.skarmetoo.ui.guide

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
class GuideLaunchPreferenceTest {
  private lateinit var preferences: SharedPreferences

  @Before
  fun setup() {
    preferences =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("guide-launch-test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
  }

  @Test
  fun newInstallNeedsAutomaticSetup() {
    assertFalse(GuideLaunchPreference(preferences).hasStarted)
  }

  @Test
  fun startingSetupSurvivesRecreationAndAppVersionUpdates() {
    GuideLaunchPreference(preferences).markStarted()
    preferences.edit().putLong("last_seen_onboarding_version_code", 1L).commit()
    assertTrue(GuideLaunchPreference(preferences).hasStarted)
  }

  @Test
  fun replayKeepsAutomaticSetupDismissed() {
    val launch = GuideLaunchPreference(preferences)
    launch.markStarted()
    launch.markStarted()
    assertTrue(GuideLaunchPreference(preferences).hasStarted)
  }
}
