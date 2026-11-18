package io.github.danzuep.bibleaudioproducer.controller.audioworkbench

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.danzuep.bibleaudioproducer.R
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioWorkbenchLaunchTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "audio-workbench")
        directory.deleteRecursively()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun newInstallShowsImportAndDisablesExport() {
        ActivityScenario.launch(AudioWorkbenchActivity::class.java).use {
            onView(withId(R.id.audio_workbench_import)).check(matches(isDisplayed()))
            onView(withId(R.id.audio_workbench_empty)).check(matches(isDisplayed()))
            onView(withId(R.id.audio_workbench_export)).check(matches(not(isEnabled())))
        }
    }

    @Test
    fun savedPlanOpensAndSearchShowsEmptyResults() {
        check(directory.mkdirs())
        File(directory, "recording-plan.json").writeText(
            """{"cards":[{"book":"JHN","lemma":"λογος","gloss":"word","occurrences":1}]}"""
        )
        ActivityScenario.launch(AudioWorkbenchActivity::class.java).use {
            onView(withId(R.id.audio_workbench_coverage)).check(matches(withText("0 of 1 recorded")))
            onView(withId(R.id.audio_workbench_export)).check(matches(isEnabled()))
            onView(withId(R.id.audio_workbench_search)).perform(replaceText("no match"))
            closeSoftKeyboard()
            onView(withId(R.id.audio_workbench_empty))
                .check(matches(withText(R.string.audio_workbench_no_results)))
        }
    }
}
