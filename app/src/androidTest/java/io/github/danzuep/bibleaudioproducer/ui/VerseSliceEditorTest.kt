package io.github.danzuep.bibleaudioproducer.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.VerseBoundaries
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.data.MaculaVerseJson
import io.github.danzuep.bibleaudioproducer.workbench.VerseRecordingWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VerseSliceEditorTest {
    @Test
    fun waveformAndTypedBoundariesStaySharedWhenCardsReorder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val words = context.assets.open("books/JHN.json").bufferedReader().use {
            MaculaVerseJson.parse(it.readText()).first().words.filter { !it.disabled(emptySet()) }.take(3)
        }.mapIndexed { index, word -> VerseRecordingWord(word, index) }
        assertEquals(3, words.size)
        var reordered = emptyList<Int>()
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val editor = VerseTakeEditor(
                    activity, AudioProjectRepository(File(context.cacheDir, "slice-editor-test")),
                    words, { true }, { reordered = it }, {}, {}
                )
                editor.view()
                fun field(name: String) = VerseTakeEditor::class.java.getDeclaredField(name).apply { isAccessible = true }
                fun invoke(name: String) = VerseTakeEditor::class.java.getDeclaredMethod(name)
                    .apply { isAccessible = true }.invoke(editor)
                val samples = FloatArray(1600) { if (it % 2 == 0) 0.1f else -0.1f }
                val boundaries = VerseBoundaries(listOf(0, 400, 900, 1600))
                field("samples").set(editor, samples)
                field("boundaries").set(editor, boundaries)
                invoke("showSelection")
                invoke("refresh")
                val waveform = field("waveform").get(editor) as VerseSliceWaveform
                waveform.layout(0, 0, 400, 120)
                fun touch(action: Int, x: Float) {
                    val event = MotionEvent.obtain(0, 0, action, x, 60f, 0)
                    waveform.dispatchTouchEvent(event)
                    event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN, 100f)
                touch(MotionEvent.ACTION_MOVE, 120f)
                touch(MotionEvent.ACTION_UP, 120f)
                assertEquals(boundaries.end(0), boundaries.start(1))
                assertTrue(boundaries.end(0) in 470..490)
                (field("end").get(editor) as EditText).setText("0.035000")
                assertEquals(true, invoke("rememberSelection"))
                assertEquals(560, boundaries.end(0))
                assertEquals(560, boundaries.start(1))
                VerseTakeEditor::class.java.getDeclaredMethod("moveCard", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(editor, 0, 1)
                assertEquals(listOf(1, 0, 2), reordered)
                assertEquals(560, boundaries.start(1))
                editor.dispose()
            }
        }
    }

    @Test
    fun recordingOrderIsFrozenWhileNavigationAndGuideFollowTheirOwnOrders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val verseWords = context.assets.open("books/JHN.json").bufferedReader().use {
            MaculaVerseJson.parse(it.readText()).first().words.filter { !it.disabled(emptySet()) }.take(3)
        }
        val words = verseWords.mapIndexed { index, word ->
            VerseRecordingWord(word.copy(gloss = "english$index", mandarin = if (index == 1) "" else "chinese$index"), index * 2)
        }
        var reordered = emptyList<Int>()
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val editor = VerseTakeEditor(
                    activity, AudioProjectRepository(File(context.cacheDir, "slice-order-test")),
                    words, { true }, { reordered = it }, {}, {}
                )
                editor.view()
                fun field(name: String) = VerseTakeEditor::class.java.getDeclaredField(name).apply { isAccessible = true }
                fun invoke(name: String) = VerseTakeEditor::class.java.getDeclaredMethod(name)
                    .apply { isAccessible = true }.invoke(editor)
                fun move(from: Int, to: Int) =
                    VerseTakeEditor::class.java.getDeclaredMethod("moveCard", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(editor, from, to)
                val guide = field("tokens").get(editor) as ChipGroup
                fun guideText() = (0 until guide.childCount).joinToString(" · ") {
                    (guide.getChildAt(it) as TextView).text.toString()
                }
                move(0, 2)
                assertEquals(listOf(2, 4, 0), reordered)
                assertEquals("english1 · english2 · english0", guideText())
                VerseTakeEditor::class.java.getDeclaredMethod("selectCard", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(editor, 0)
                invoke("freezeAudioOrder")
                val boundaries = VerseBoundaries(listOf(0, 400, 900, 1600))
                field("samples").set(editor, FloatArray(1600))
                field("boundaries").set(editor, boundaries)
                invoke("showSelection")
                invoke("refresh")
                move(0, 2)
                assertEquals(listOf(4, 0, 2), reordered)
                assertEquals(listOf(0, 400, 900, 1600), boundaries.snapshot())
                assertEquals("english1 · english2 · english0", guideText())
                assertEquals("0.000000", (field("start").get(editor) as EditText).text.toString())
                assertEquals("0.025000", (field("end").get(editor) as EditText).text.toString())
                field("guideMode").setInt(editor, 1)
                invoke("updateGuide")
                assertEquals("[translation needed] · chinese2 · chinese0", guideText())
                val custom = field("customGuide").get(editor) as EditText
                field("guideMode").setInt(editor, 4)
                invoke("updateGuide")
                custom.setText("My own script")
                move(2, 0)
                field("guideMode").setInt(editor, 0)
                invoke("updateGuide")
                field("guideMode").setInt(editor, 4)
                invoke("updateGuide")
                assertEquals("My own script", custom.text.toString())
                @Suppress("UNCHECKED_CAST")
                val reviewed = field("reviewed").get(editor) as MutableSet<Int>
                reviewed.addAll(listOf(0, 1))
                (field("end").get(editor) as EditText).setText("0.031250")
                guide.getChildAt(1).performClick()
                assertEquals(1, field("index").getInt(editor))
                assertEquals(emptySet<Int>(), reviewed)
                assertEquals(500, boundaries.end(0))
                assertEquals("0.031250", (field("start").get(editor) as EditText).text.toString())
                invoke("advance")
                assertEquals(2, field("index").getInt(editor))
                assertEquals(setOf(1), reviewed)
                editor.dispose()
            }
        }
    }

    @Test
    fun duplicateSurfacesRequireReplacementAndKeepSeparateTakeSlices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val word = context.assets.open("books/JHN.json").bufferedReader().use {
            MaculaVerseJson.parse(it.readText()).first().words.first { !it.disabled(emptySet()) }
        }
        val directory = File(context.cacheDir, "slice-duplicate-test")
        directory.deleteRecursively()
        val repository = AudioProjectRepository(directory)
        val clip = repository.clipFile(word.surface)
        check(clip.parentFile!!.mkdirs() || clip.parentFile!!.isDirectory)
        val original = VerseAudio.encodeWav(FloatArray(800))
        val samples = FloatArray(1600) { if (it < 800) 0.1f else 0.5f }
        clip.writeBytes(original)
        lateinit var editor: VerseTakeEditor
        fun field(name: String) = VerseTakeEditor::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String) = VerseTakeEditor::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(editor)
        fun reviewedCount(): Int {
            @Suppress("UNCHECKED_CAST")
            return (field("reviewed").get(editor) as Set<Int>).size
        }
        try {
            ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    editor = VerseTakeEditor(
                        activity, repository, listOf(VerseRecordingWord(word, 0), VerseRecordingWord(word, 2)),
                        { true }, {}, {}, {}
                    )
                    editor.view()
                    field("samples").set(editor, samples)
                    field("boundaries").set(editor, VerseBoundaries(listOf(0, 800, 1600)))
                    invoke("showSelection")
                    invoke("refresh")
                    VerseTakeEditor::class.java.getDeclaredMethod("selectCard", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(editor, 1)
                    assertEquals(0, reviewedCount())
                    (field("next").get(editor) as Button).performClick()
                }
                fun waitForActivityFocus() {
                    val deadline = System.currentTimeMillis() + 10_000
                    var focused = false
                    while (!focused && System.currentTimeMillis() < deadline) {
                        scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                        if (!focused) Thread.sleep(50)
                    }
                    assertTrue("Activity did not regain focus after replacement dialog", focused)
                }
                onView(withText(android.R.string.cancel)).perform(click())
                waitForActivityFocus()
                scenario.onActivity {
                    assertEquals(0, reviewedCount())
                    assertArrayEquals(original, clip.readBytes())
                    (field("next").get(editor) as Button).performClick()
                }
                onView(withText("Replace")).inRoot(isDialog()).perform(click())
                fun waitForReviewed(count: Int) {
                    val deadline = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < deadline) {
                        var actual = 0
                        scenario.onActivity { actual = reviewedCount() }
                        if (actual == count) return
                        Thread.sleep(50)
                    }
                    throw AssertionError("Expected $count reviewed clips")
                }
                waitForReviewed(1)
                assertArrayEquals(VerseAudio.encodeWav(VerseAudio.slice(samples, 800, 1600)), clip.readBytes())
                waitForActivityFocus()
                scenario.onActivity {
                    VerseTakeEditor::class.java.getDeclaredMethod("moveCard", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(editor, 1, 0)
                    assertEquals("0.050000", (field("start").get(editor) as EditText).text.toString())
                    VerseTakeEditor::class.java.getDeclaredMethod("selectCard", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(editor, 1)
                    assertEquals(1, reviewedCount())
                    (field("next").get(editor) as Button).performClick()
                }
                onView(withText("Replace")).inRoot(isDialog()).perform(click())
                waitForReviewed(2)
                assertArrayEquals(VerseAudio.encodeWav(VerseAudio.slice(samples, 0, 800)), clip.readBytes())
                scenario.onActivity { editor.dispose() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun chipsHelpAndNudgesKeepSharedBoundariesAndStickyControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val words = context.assets.open("books/JHN.json").bufferedReader().use {
            MaculaVerseJson.parse(it.readText()).first().words.filter { !it.disabled(emptySet()) }.take(3)
        }.mapIndexed { index, word -> VerseRecordingWord(word, index) }
        ActivityScenario.launch(WorkbenchActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val editor = VerseTakeEditor(
                    activity, AudioProjectRepository(File(context.cacheDir, "slice-nudge-test")),
                    words, { true }, {}, {}, {}
                )
                val root = editor.view()
                fun field(name: String) = VerseTakeEditor::class.java.getDeclaredField(name).apply { isAccessible = true }
                fun invoke(name: String) = VerseTakeEditor::class.java.getDeclaredMethod(name)
                    .apply { isAccessible = true }.invoke(editor)
                val boundaries = VerseBoundaries(listOf(0, 800, 1600, 2400))
                field("samples").set(editor, FloatArray(2400))
                field("boundaries").set(editor, boundaries)
                invoke("showSelection")
                invoke("refresh")
                @Suppress("UNCHECKED_CAST")
                val reviewed = field("reviewed").get(editor) as MutableSet<Int>
                reviewed.addAll(listOf(0, 1, 2))
                root.findViewById<View>(R.id.clip_nudge_forward).performClick()
                assertEquals(960, boundaries.end(0))
                assertEquals(960, boundaries.start(1))
                assertEquals(setOf(2), reviewed)
                val chips = root.findViewById<ChipGroup>(R.id.clip_tokens)
                chips.getChildAt(2).performClick()
                assertEquals(2, field("index").getInt(editor))
                root.findViewById<View>(R.id.clip_nudge_back).performClick()
                assertEquals(1440, boundaries.start(2))
                assertEquals(1440, boundaries.end(1))
                assertTrue(reviewed.isEmpty())
                val help = root.findViewById<View>(R.id.clip_help)
                assertEquals(View.GONE, help.visibility)
                val toolbar = root.findViewById<MaterialToolbar>(R.id.clip_toolbar)
                toolbar.menu.performIdentifierAction(R.id.clip_help_action, 0)
                assertEquals(View.VISIBLE, help.visibility)
                val bottom = root.findViewById<View>(R.id.clip_bottom)
                assertTrue(bottom.parent is ConstraintLayout)
                assertEquals(root, bottom.parent)
                editor.dispose()
            }
        }
    }
}
