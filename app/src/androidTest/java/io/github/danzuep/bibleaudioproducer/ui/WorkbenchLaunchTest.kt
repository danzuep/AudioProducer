package io.github.danzuep.bibleaudioproducer.ui

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import android.widget.NumberPicker
import android.widget.Spinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.data.RecordingPlanJson
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.data.MaculaVerseJson
import java.io.File
import android.widget.Button
import org.hamcrest.Matchers.allOf

@RunWith(AndroidJUnit4::class)
class WorkbenchLaunchTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "projects").deleteRecursively()
        directory = File(context.filesDir, "projects/project-001")
        check(context.getSharedPreferences("audio-project", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "projects").deleteRecursively()
        check(context.getSharedPreferences("audio-project", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test
    fun newInstallLoadsTheFullGreekPlan() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cards = context.assets.open("recording-plan.json").bufferedReader().use {
            RecordingPlanJson.parse(it.readText())
        }
        assertEquals(4917, cards.size)
        assertEquals(27, cards.map { it.book }.distinct().size)
        assertEquals("JHN", cards.first().book)
        val chapterReferences = context.assets.open("books/JHN.json").bufferedReader().use {
            MaculaVerseJson.parse(it.readText()).filter { it.chapter == 1 }
        }
        val forms = chapterReferences.flatMap { it.words }.filterNot { it.proper }
            .flatMap { listOf(it.lemma, it.surface).distinct() }.groupingBy { it }.eachCount()
        val chapterCards = cards.filter { it.lemma in forms }
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 27)
            onView(withId(R.id.audio_verse_close)).check(matches(isDisplayed()))
            onView(withId(R.id.audio_verse_close)).perform(click())
            onView(withId(R.id.audio_workbench_coverage))
                .check(matches(withText("0 of ${chapterCards.size} recorded")))
            onView(withId(R.id.audio_workbench_list)).check { view, _ ->
                val rows = ((view as RecyclerView).adapter as RecordingCardAdapter).currentList
                assertEquals(chapterCards.maxBy { forms.getValue(it.lemma) }.lemma, rows.first().card.lemma)
            }
            openSettings(scenario)
            onView(withId(R.id.audio_workbench_export)).check(matches(isEnabled()))
        }
    }

    @Test
    fun savedPlanOpensAndSearchShowsEmptyResults() {
        check(directory.mkdirs())
        File(directory, "recording-plan.json").writeText(
            """{"cards":[{"book":"JHN","lemma":"λόγος","gloss":"word","occurrences":1}]}"""
        )
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 1)
            onView(withId(R.id.audio_verse_close)).perform(click())
            onView(withId(R.id.audio_workbench_coverage)).check(matches(withText("0 of 1 recorded")))
            openSettings(scenario)
            onView(withId(R.id.audio_workbench_export)).check(matches(isEnabled()))
            openSettings(scenario)
            onView(withId(R.id.audio_workbench_search)).perform(replaceText("no match"))
            closeSoftKeyboard()
            onView(withId(R.id.audio_workbench_empty))
                .check(matches(withText(R.string.audio_workbench_no_results)))
        }
    }

    @Test
    fun savesAnOrderForABundledVerse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val verse = context.assets.open("books/JHN.json").bufferedReader().use {
            MaculaVerseJson.parse(it.readText()).first { item -> item.reference == "JHN 1:1" }
        }
        val skipped = verse.words.filterNot { it.proper }.first { candidate ->
            verse.words.count { it.surface == candidate.surface } == 1
        }
        var expectedSurfaceOrder: List<String>? = null
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 27)
            onView(withText(skipped.surface)).check(matches(isDisplayed()))
            onView(withText(skipped.surface)).perform(object : ViewAction {
                override fun getDescription() = "Drag the word to the opposite end of the verse"

                override fun getConstraints() = isAssignableFrom(TextView::class.java)

                override fun perform(uiController: UiController, view: View) {
                    var token = view
                    while (token !is FrameLayout || token.parent !is ViewGroup) {
                        token = token.parent as? View
                            ?: throw AssertionError("Greek word token was not found")
                    }
                    val flow = token.parent as ViewGroup
                    val sourceIndex = flow.indexOfChild(token)
                    val targetIndex = if (sourceIndex == 0) flow.childCount - 1 else 0
                    check(targetIndex != sourceIndex)
                    val expected = (0 until flow.childCount).map { index ->
                        val stack = (flow.getChildAt(index) as FrameLayout).getChildAt(0) as ViewGroup
                        (stack.getChildAt(1) as TextView).text.toString()
                    }.toMutableList()
                    val moved = expected.removeAt(sourceIndex)
                    expected.add(targetIndex, moved)
                    expectedSurfaceOrder = expected
                    val start = IntArray(2)
                    val end = IntArray(2)
                    token.getLocationOnScreen(start)
                    flow.getChildAt(targetIndex).getLocationOnScreen(end)
                    val downTime = SystemClock.uptimeMillis()
                    fun inject(action: Int, x: Int, y: Int) {
                        val event = MotionEvent.obtain(
                            downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0
                        )
                        try {
                            uiController.injectMotionEvent(event)
                        } finally {
                            event.recycle()
                        }
                    }
                    inject(
                        MotionEvent.ACTION_DOWN,
                        start[0] + token.width / 2,
                        start[1] + token.height / 2
                    )
                    uiController.loopMainThreadForAtLeast(
                        (ViewConfiguration.getLongPressTimeout() + ViewConfiguration.getTapTimeout() + 100).toLong()
                    )
                    inject(
                        MotionEvent.ACTION_MOVE,
                        end[0] + flow.getChildAt(targetIndex).width / 2,
                        end[1] + flow.getChildAt(targetIndex).height / 2
                    )
                    assertTrue("The held card did not follow the finger", token.translationX != 0f || token.translationY != 0f)
                    inject(
                        MotionEvent.ACTION_UP,
                        end[0] + flow.getChildAt(targetIndex).width / 2,
                        end[1] + flow.getChildAt(targetIndex).height / 2
                    )
                }
            })
            onView(withContentDescription(context.getString(R.string.audio_word_unspoken, skipped.surface)))
                .perform(click())
            onView(withText(R.string.audio_word_disable)).perform(click())
            onView(withId(R.id.audio_verse_close)).perform(click())
            val stateFile = File(directory, "audio-state.json")
            val saveDeadline = System.currentTimeMillis() + 15_000
            while (!stateFile.isFile && System.currentTimeMillis() < saveDeadline) Thread.sleep(50)
            assertTrue("Verse order was not saved", stateFile.isFile)
            val saved = AudioProjectRepository(directory).loadState()
            val savedOrder = saved.wordOrders["JHN 1:1"]
                ?: throw AssertionError("Reordered verse indices were not saved")
            assertEquals(expectedSurfaceOrder, savedOrder.map { verse.words[it].surface })
            assertTrue(skipped.unspokenKey in saved.unspoken)
            onView(withId(R.id.audio_choose_verse)).perform(click())
            onView(withText("Chapter 1")).perform(click())
            onView(withContentDescription(verse.words.joinToString(" ") { it.surface })).perform(click())
            onView(withId(R.id.audio_verse_more)).perform(click())
            onView(withText(R.string.audio_download_verse)).perform(click())
            onView(withText(R.string.audio_format_wav)).check(matches(isDisplayed()))
            onView(withText(R.string.audio_format_aac)).check(matches(isDisplayed()))
            if (Build.VERSION.SDK_INT >= 29) {
                onView(withText(R.string.audio_format_opus)).check(matches(isDisplayed()))
            }
            onView(withText(android.R.string.cancel)).perform(click())
            onView(withId(R.id.audio_verse_close)).perform(click())
        }
    }

    @Test
    fun interruptedTakeCanBeReviewedAfterRelaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AudioProjectRepository(directory)
        val current = repository.clipFile("λόγος")
        check(current.parentFile!!.mkdirs())
        current.writeBytes(byteArrayOf(1))

        fun prepareTake() {
            repository.recordingCandidateFile("λόγος").writeBytes(byteArrayOf(2))
            check(context.getSharedPreferences("audio-project", Context.MODE_PRIVATE).edit()
                .putString("pending_recording_lemma", "λόγος")
                .putString("pending_recording_project", "project-001").commit())
        }

        prepareTake()
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            onView(withText(R.string.audio_review_title)).check(matches(isDisplayed()))
            onView(withContentDescription(R.string.audio_review_keep)).perform(click())
            awaitBooks(scenario, 27)
            assertEquals(listOf(1), current.readBytes().map { it.toInt() })
        }

        prepareTake()
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            onView(withContentDescription(R.string.audio_review_use)).perform(click())
            awaitBooks(scenario, 27)
            val deadline = System.currentTimeMillis() + 15_000
            val preferences = context.getSharedPreferences("audio-project", Context.MODE_PRIVATE)
            while (preferences.contains("pending_recording_lemma") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("Take review was not completed", !preferences.contains("pending_recording_lemma"))
            assertEquals(listOf(2), current.readBytes().map { it.toInt() })
            assertTrue(!repository.recordingCandidateFile("λόγος").exists())
        }
    }

    @Test
    fun recordingPlaybackCanStopAndReturnsToPlayWhenComplete() {
        check(directory.mkdirs())
        File(directory, "recording-plan.json").writeText(
            """{"cards":[{"book":"JHN","lemma":"λόγος","gloss":"word","occurrences":1}]}"""
        )
        val clip = AudioProjectRepository(directory).clipFile("λόγος")
        check(clip.parentFile!!.mkdirs())
        clip.writeBytes(VerseAudio.encodeWav(FloatArray(16000) { 0.1f }))
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 1)
            onView(withId(R.id.audio_workbench_play)).perform(click())
            awaitPlayDescription(scenario, "Stop λόγος")
            onView(withId(R.id.audio_workbench_play)).perform(click())
            awaitPlayDescription(scenario, "Play λόγος")
            onView(withId(R.id.audio_workbench_play)).perform(click())
            awaitPlayDescription(scenario, "Stop λόγος")
            awaitPlayDescription(scenario, "Play λόγος")
        }

        @Test
        fun closingChapterStopsPlayback() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val surface = context.assets.open("books/JHN.json").bufferedReader().use {
                MaculaVerseJson.parse(it.readText()).first().words.first { word -> !word.proper }.surface
            }
            ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
                awaitBooks(scenario, 27)
                onView(withId(R.id.audio_verse_close)).perform(click())
                val clip = AudioProjectRepository(directory).clipFile(surface)
                check(clip.parentFile!!.mkdirs() || clip.parentFile!!.isDirectory)
                clip.writeBytes(VerseAudio.encodeWav(FloatArray(10 * VerseAudio.SAMPLE_RATE) { 0.1f }))
                fun openChapter() {
                    onView(withId(R.id.audio_choose_verse)).perform(click())
                    onView(withText("Chapter 1")).perform(click())
                }
                openChapter()
                onView(withContentDescription(R.string.audio_play_chapter)).perform(click())
                onView(withContentDescription(R.string.audio_stop_chapter)).check(matches(isDisplayed()))
                pressBack()
                openChapter()
                onView(withContentDescription(R.string.audio_play_chapter)).check(matches(isEnabled()))
            }
        }
    }

    @Test
    fun verseRecordingShowsReviewBeforeStoppingAndCanKeepTheOldTake() {
        val context = grantRecordingPermission()
        val repository = AudioProjectRepository(directory)
        val surface = context.assets.open("books/JHN.json").bufferedReader().use {
            val words = MaculaVerseJson.parse(it.readText()).first { verse -> verse.reference == "JHN 1:1" }.words
            words.first { word -> words.count { it.surface == word.surface } == 1 }.surface
        }
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 27)
            onView(withContentDescription(context.getString(R.string.audio_word_unspoken, surface))).perform(click())
            val current = repository.clipFile(surface)
            check(current.parentFile!!.mkdirs() || current.parentFile!!.isDirectory)
            val original = VerseAudio.encodeWav(FloatArray(16000) { 0.1f })
            current.writeBytes(original)
            onView(withContentDescription(context.getString(R.string.audio_word_record, surface))).perform(click())
            onView(withText(R.string.audio_review_new_waveform)).check(matches(isDisplayed()))
            onView(withText(R.string.audio_review_old_waveform)).check(matches(isDisplayed()))
            Thread.sleep(800)
            onView(withContentDescription(R.string.audio_review_stop_recording)).perform(click())
            onView(withContentDescription(R.string.audio_review_play_new)).check(matches(isEnabled()))
            onView(withContentDescription(R.string.audio_review_keep)).perform(click())
            assertArrayEquals(original, current.readBytes())
            assertTrue(!repository.recordingCandidateFile(surface).exists())
        }
    }

    private fun grantRecordingPermission(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}"
        )
        ParcelFileDescriptor.AutoCloseInputStream(result).bufferedReader().use { it.readText() }
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        )
        return context
    }

    @Test
    fun holdShowsWaveformSavesOnReleaseAndPreservesTakeOnCancel() {
        grantRecordingPermission()
        check(directory.mkdirs())
        File(directory, "recording-plan.json").writeText(
            """{"cards":[{"book":"JHN","lemma":"λόγος","gloss":"word","occurrences":1}]}"""
        )
        val repository = AudioProjectRepository(directory)
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            awaitBooks(scenario, 1)
            onView(withId(R.id.audio_verse_close)).perform(click())
            onView(withId(R.id.audio_workbench_record)).check(matches(isDisplayed()))
            fun touch(action: Int) = scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(R.id.audio_workbench_record)
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, action, button.width / 2f, button.height / 2f, 0)
                try {
                    button.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            touch(MotionEvent.ACTION_DOWN)
            val deadline = System.currentTimeMillis() + 10_000
            var showing = false
            while (!showing && System.currentTimeMillis() < deadline) {
                scenario.onActivity {
                    showing = it.findViewById<View>(R.id.audio_workbench_waveform)?.visibility == View.VISIBLE
                }
                if (!showing) Thread.sleep(25)
            }
            assertTrue("Recording waveform did not appear", showing)
            Thread.sleep(800)
            touch(MotionEvent.ACTION_UP)
            val clip = repository.clipFile("λόγος")
            assertTrue("Take was not saved on release", clip.isFile && clip.length() > 0)
            val original = clip.readBytes()
            touch(MotionEvent.ACTION_DOWN)
            Thread.sleep(800)
            touch(MotionEvent.ACTION_CANCEL)
            assertArrayEquals(original, clip.readBytes())
            assertTrue(!repository.recordingCandidateFile("λόγος").exists())
        }
    }

    private fun awaitPlayDescription(scenario: ActivityScenario<WorkbenchActivity>, description: String) {
        val deadline = System.currentTimeMillis() + 10_000
        var matches = false
        while (!matches && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                matches = activity.findViewById<Button>(R.id.audio_workbench_play)
                    ?.contentDescription?.toString() == description
            }
            if (!matches) Thread.sleep(25)
        }
        assertTrue("Playback control did not become $description", matches)
    }

    private fun openSettings(scenario: ActivityScenario<WorkbenchActivity>) {
        var opened = false
        scenario.onActivity { activity ->
            opened = activity.findViewById<Toolbar>(R.id.audio_workbench_toolbar).menu
                .performIdentifierAction(R.id.audio_menu_settings, 0)
        }
        assertTrue("Settings menu action was not available", opened)
    }

    private fun awaitBooks(scenario: ActivityScenario<WorkbenchActivity>, count: Int) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (!awaitBookCount(scenario, count, 500)) {
            var verseOpen = false
            scenario.onActivity { activity ->
                verseOpen = activity.findViewById<View>(R.id.audio_verse_close)?.isShown == true
            }
            if (verseOpen) onView(withId(R.id.audio_verse_close)).perform(click())
            if (!awaitBookCount(scenario, count, 500)) {
                onView(allOf(withText("Project001"), isDisplayed())).perform(click())
            }
        }
        assertTrue("Recording plan did not load", awaitBookCount(scenario, count, 15_000))
    }

    private fun awaitBookCount(
        scenario: ActivityScenario<WorkbenchActivity>,
        count: Int,
        timeoutMillis: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var loaded = false
        while (!loaded && System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                loaded = activity.findViewById<Spinner>(R.id.audio_workbench_books).adapter?.count == count &&
                    activity.findViewById<NumberPicker>(R.id.audio_workbench_chapter).maxValue > 0
            }
            if (!loaded) Thread.sleep(50)
        }
        return loaded
    }
}
