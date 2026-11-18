package io.github.danzuep.bibleaudioproducer.ui

import android.view.MotionEvent
import android.view.View

internal fun View.setRecordingGesture(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onAccessibleClick: () -> Unit
) {
    var suppressClick = false
    setOnClickListener {
        if (!suppressClick) onAccessibleClick()
    }
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.isPressed = true
                onPress()
                true
            }
            MotionEvent.ACTION_UP -> {
                view.isPressed = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
                val distance = kotlin.math.hypot(
                    event.x - view.width / 2f, event.y - view.height / 2f
                )
                if (distance > 2 * maxOf(view.width, view.height)) onCancel() else onRelease()
                suppressClick = true
                view.performClick()
                suppressClick = false
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
                onCancel()
                true
            }
            else -> true
        }
    }
}
