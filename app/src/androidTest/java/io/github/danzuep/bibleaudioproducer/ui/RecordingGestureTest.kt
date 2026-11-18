package io.github.danzuep.bibleaudioproducer.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingGestureTest {
    @Test
    fun releaseSavesAndCancellationDoesNotToggle() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val events = mutableListOf<String>()
            val button = Button(ApplicationProvider.getApplicationContext<Context>())
            button.layout(0, 0, 100, 100)
            button.setRecordingGesture(
                { events.add("press") }, { events.add("save") },
                { events.add("cancel") }, { events.add("toggle") }
            )
            fun touch(action: Int, x: Float, y: Float) {
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, action, x, y, 0)
                try {
                    button.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            touch(MotionEvent.ACTION_DOWN, 50f, 50f)
            touch(MotionEvent.ACTION_UP, 50f, 50f)
            touch(MotionEvent.ACTION_DOWN, 50f, 50f)
            touch(MotionEvent.ACTION_UP, 300f, 300f)
            touch(MotionEvent.ACTION_DOWN, 50f, 50f)
            touch(MotionEvent.ACTION_CANCEL, 50f, 50f)
            button.performClick()
            assertEquals(
                listOf("press", "save", "press", "cancel", "press", "cancel", "toggle"),
                events
            )
        }
    }
}
