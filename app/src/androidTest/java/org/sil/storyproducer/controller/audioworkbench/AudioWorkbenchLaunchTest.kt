package org.sil.storyproducer.controller.audioworkbench

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.sil.storyproducer.R

@RunWith(AndroidJUnit4::class)
class AudioWorkbenchLaunchTest {
    @get:Rule
    val activity = ActivityScenarioRule(AudioWorkbenchActivity::class.java)

    @Test
    fun newInstallShowsImportAndDisablesExport() {
        onView(withId(R.id.audio_workbench_import)).check(matches(isDisplayed()))
        onView(withId(R.id.audio_workbench_empty)).check(matches(isDisplayed()))
        onView(withId(R.id.audio_workbench_export)).check(matches(not(isEnabled())))
    }
}
