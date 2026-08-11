package com.cuscus.wifiscreenstreaming

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class KeySink(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onText: ((String) -> Unit)? = null
    var onKey: ((KeyEvent) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) onText?.invoke(value)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true

            override fun finishComposingText(): Boolean = true

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 32)) { onBackspace?.invoke() }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (systemKey(event.keyCode)) return super.sendKeyEvent(event)
                onKey?.invoke(event)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onKey?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                onKey?.invoke(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                return true
            }
        }
    }

    private fun systemKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER -> true
        else -> false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (systemKey(keyCode)) return super.onKeyDown(keyCode, event)
        onKey?.invoke(event)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (systemKey(keyCode)) return super.onKeyUp(keyCode, event)
        onKey?.invoke(event)
        return true
    }
}
