package io.github.danzuep.bibleaudioproducer.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.danzuep.bibleaudioproducer.audio.VerseBoundaries
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.data.MaculaVerseJson
import io.github.danzuep.bibleaudioproducer.workbench.VerseRecordingWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                val root = editor.view()
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
                val controls = ArrayList<View>()
                root.findViewsWithText(controls, "Move ${words[0].word.gloss} later", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                assertEquals(1, controls.size)
                controls.single().performClick()
                assertEquals(listOf(1, 0, 2), reordered)
                assertEquals(560, boundaries.start(1))
                editor.dispose()
            }
        }
    }
}
