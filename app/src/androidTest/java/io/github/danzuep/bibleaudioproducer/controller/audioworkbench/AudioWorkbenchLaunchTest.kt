package io.github.danzuep.bibleaudioproducer.controller.audioworkbench

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.Spinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.danzuep.bibleaudioproducer.R
import java.io.File
import android.widget.Button
import org.hamcrest.Matchers.startsWith

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
    fun newInstallLoadsTheFullGreekPlan() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cards = context.assets.open("recording-plan.json").bufferedReader().use {
            AudioWorkbenchPlan.parse(it.readText())
        }
        assertEquals(4917, cards.size)
        assertEquals(27, cards.map { it.book }.distinct().size)
        assertEquals("JHN", cards.first().book)
        ActivityScenario.launch(AudioWorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 27)
            onView(withId(R.id.audio_step_books)).perform(click())
            onView(withId(R.id.audio_workbench_coverage)).check(matches(withText("0 of 952 recorded")))
            onView(withId(R.id.audio_workbench_export)).check(matches(isEnabled()))
        }
    }

    @Test
    fun savedPlanOpensAndSearchShowsEmptyResults() {
        check(directory.mkdirs())
        File(directory, "recording-plan.json").writeText(
            """{"cards":[{"book":"JHN","lemma":"λογος","gloss":"word","occurrences":1}]}"""
        )
        ActivityScenario.launch(AudioWorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 1)
            onView(withId(R.id.audio_step_books)).perform(click())
            onView(withId(R.id.audio_workbench_coverage)).check(matches(withText("0 of 1 recorded")))
            onView(withId(R.id.audio_workbench_export)).check(matches(isEnabled()))
            onView(withId(R.id.audio_workbench_search)).perform(replaceText("no match"))
            closeSoftKeyboard()
            onView(withId(R.id.audio_workbench_empty))
                .check(matches(withText(R.string.audio_workbench_no_results)))
        }
    }

    @Test
    fun savesAnOrderForABundledVerse() {
        ActivityScenario.launch(AudioWorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 27)
            onView(withId(R.id.audio_step_books)).perform(click())
            val deadline = System.currentTimeMillis() + 15_000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                scenario.onActivity { ready = it.findViewById<Button>(R.id.audio_listen).isEnabled }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Verse data did not load", ready)
            onView(withId(R.id.audio_listen)).perform(click())
            onView(withText("Chapter 1")).perform(click())
            onView(withText(startsWith("JHN 1:1 ·"))).perform(click())
            onView(withText(R.string.audio_arrange_verse)).perform(click())
            onView(withText(R.string.audio_save_order)).perform(click())
            val stateFile = File(directory, "audio-state.json")
            val saveDeadline = System.currentTimeMillis() + 15_000
            while (!stateFile.isFile && System.currentTimeMillis() < saveDeadline) Thread.sleep(50)
            assertTrue("Verse order was not saved", stateFile.isFile)
            assertTrue(AudioWorkbenchRepository(directory).loadState().seedOrders.containsKey("JHN 1:1"))
            onView(withId(R.id.audio_listen)).perform(click())
            onView(withText("Chapter 1")).perform(click())
            onView(withText(startsWith("JHN 1:1 ·"))).perform(click())
            onView(withText(R.string.audio_record_verse_words)).perform(click())
            onView(withText(android.R.string.ok)).perform(click())
        }
    }

    private fun awaitBooks(scenario: ActivityScenario<AudioWorkbenchActivity>, count: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        var loaded = false
        while (!loaded && System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                loaded = activity.findViewById<Spinner>(R.id.audio_workbench_books).adapter?.count == count
            }
            if (!loaded) Thread.sleep(50)
        }
        assertTrue("Recording plan did not load", loaded)
    }
}
