/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */
package com.cuscus.wifiscreenstreaming

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

object PhysicalInput {

    private val table: Map<Int, Int> = buildMap {
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, Vk.LETTER_A + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, Vk.DIGIT_0 + i)
        for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, Vk.F1 + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, Vk.NUMPAD_0 + i)

        put(KeyEvent.KEYCODE_ENTER, Vk.ENTER)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, Vk.ENTER)
        put(KeyEvent.KEYCODE_DEL, Vk.BACK_SPACE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, Vk.DELETE)
        put(KeyEvent.KEYCODE_TAB, Vk.TAB)
        put(KeyEvent.KEYCODE_SPACE, Vk.SPACE)
        put(KeyEvent.KEYCODE_ESCAPE, Vk.ESCAPE)
        put(KeyEvent.KEYCODE_INSERT, Vk.INSERT)

        put(KeyEvent.KEYCODE_DPAD_LEFT, Vk.LEFT)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, Vk.RIGHT)
        put(KeyEvent.KEYCODE_DPAD_UP, Vk.UP)
        put(KeyEvent.KEYCODE_DPAD_DOWN, Vk.DOWN)
        put(KeyEvent.KEYCODE_MOVE_HOME, Vk.HOME)
        put(KeyEvent.KEYCODE_MOVE_END, Vk.END)
        put(KeyEvent.KEYCODE_PAGE_UP, Vk.PAGE_UP)
        put(KeyEvent.KEYCODE_PAGE_DOWN, Vk.PAGE_DOWN)

        put(KeyEvent.KEYCODE_SHIFT_LEFT, Vk.SHIFT)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, Vk.SHIFT)
        put(KeyEvent.KEYCODE_CTRL_LEFT, Vk.CONTROL)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, Vk.CONTROL)
        put(KeyEvent.KEYCODE_ALT_LEFT, Vk.ALT)
        put(KeyEvent.KEYCODE_ALT_RIGHT, Vk.ALT_GRAPH)
        put(KeyEvent.KEYCODE_META_LEFT, Vk.WINDOWS)
        put(KeyEvent.KEYCODE_META_RIGHT, Vk.WINDOWS)
        put(KeyEvent.KEYCODE_MENU, Vk.CONTEXT_MENU)
        put(KeyEvent.KEYCODE_CAPS_LOCK, Vk.CAPS_LOCK)
        put(KeyEvent.KEYCODE_NUM_LOCK, Vk.NUM_LOCK)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, Vk.SCROLL_LOCK)
        put(KeyEvent.KEYCODE_BREAK, Vk.PAUSE)
        put(KeyEvent.KEYCODE_SYSRQ, Vk.PRINTSCREEN)

        put(KeyEvent.KEYCODE_COMMA, Vk.COMMA)
        put(KeyEvent.KEYCODE_PERIOD, Vk.PERIOD)
        put(KeyEvent.KEYCODE_SLASH, Vk.SLASH)
        put(KeyEvent.KEYCODE_BACKSLASH, Vk.BACK_SLASH)
        put(KeyEvent.KEYCODE_SEMICOLON, Vk.SEMICOLON)
        put(KeyEvent.KEYCODE_APOSTROPHE, Vk.QUOTE)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, Vk.OPEN_BRACKET)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, Vk.CLOSE_BRACKET)
        put(KeyEvent.KEYCODE_MINUS, Vk.MINUS)
        put(KeyEvent.KEYCODE_EQUALS, Vk.EQUALS)
        put(KeyEvent.KEYCODE_GRAVE, Vk.BACK_QUOTE)

        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, Vk.MULTIPLY)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, Vk.ADD)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, Vk.SUBTRACT)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, Vk.DECIMAL)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, Vk.DIVIDE)
    }

    fun keyCodeOf(androidKeyCode: Int): Int? = table[androidKeyCode]

    fun fromRealKeyboard(event: KeyEvent): Boolean {
        if (event.deviceId <= 0) return false
        val device = event.device ?: return false
        if (device.isVirtual) return false
        return device.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    fun fromMouse(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_STYLUS) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)

    fun buttonOf(androidButton: Int): Int? = when (androidButton) {
        MotionEvent.BUTTON_PRIMARY -> BUTTON_LEFT
        MotionEvent.BUTTON_SECONDARY -> BUTTON_RIGHT
        MotionEvent.BUTTON_TERTIARY -> BUTTON_MIDDLE
        else -> null
    }

    val buttons = listOf(
        MotionEvent.BUTTON_PRIMARY,
        MotionEvent.BUTTON_SECONDARY,
        MotionEvent.BUTTON_TERTIARY
    )
}
