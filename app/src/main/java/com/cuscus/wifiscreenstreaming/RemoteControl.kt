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

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import com.cuscus.wifiscreenstreaming.ui.HomeState
import com.cuscus.wifiscreenstreaming.ui.PinAsk
import com.cuscus.wifiscreenstreaming.ui.Pointer
import com.cuscus.wifiscreenstreaming.ui.SasAsk
import com.cuscus.wifiscreenstreaming.ui.TrustAsk

class RemoteControl(
    private val activity: Activity,
    private val trust: ClientTrust,
    private val state: HomeState,
    private val status: (String) -> Unit
) {

    private var sink: KeySink? = null

    @Volatile
    private var sender: InputSender? = null

    @Volatile
    var expected = false
        private set

    fun useSink(view: KeySink) {
        sink = view
        view.onText = { value -> guard { sender?.text(value) } }
        view.onBackspace = { guard { sender?.key(Vk.BACK_SPACE) } }
        view.onKey = { event -> guard { onSoftKey(event) } }
    }

    val active: Boolean get() = sender?.isRunning == true

    fun escape() {
        sender?.key(Vk.ESCAPE)
    }

    fun askForVideo(wanted: Boolean) {
        sender?.wantsVideo(wanted)
    }

    fun attach(session: Session, host: String) {
        detach()
        trust.remember(host, session.peerPublicKey, session.peerName)
        val created = InputSender(session.channel) { message ->
            activity.runOnUiThread { status(message) }
        }
        created.mode = if (prefs().getBoolean("trackpad", false)) {
            PointerMode.TRACKPAD
        } else {
            PointerMode.ABSOLUTE
        }
        sender = created
        expected = true
        activity.runOnUiThread {
            state.inputLive = true
            state.pointer = if (created.mode == PointerMode.TRACKPAD) Pointer.Trackpad else Pointer.Tap
        }
    }

    fun detach() {
        sender?.stop()
        sender = null
        expected = false
        activity.runOnUiThread {
            state.inputLive = false
            hideKeyboard()
        }
    }

    fun explainDead() {
        val current = sender
        status(
            when {
                current == null -> "remote control never started: reconnect with Mouse and keyboard ticked"
                else -> "remote control stopped: ${current.lastError ?: "the PC closed the channel"}"
            }
        )
    }

    fun onTouch(view: View, event: android.view.MotionEvent): Boolean =
        sender?.onTouch(view, event) ?: false

    fun onGeneric(view: View, event: android.view.MotionEvent): Boolean =
        sender?.onGeneric(view, event) ?: false

    fun onPhysicalKey(event: KeyEvent): Boolean {
        val current = sender ?: return false
        if (!PhysicalInput.fromRealKeyboard(event)) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER -> return false
        }

        val mapped = PhysicalInput.keyCodeOf(event.keyCode)
        if (mapped != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> current.keyDown(mapped)
                KeyEvent.ACTION_UP -> current.keyUp(mapped)
                else -> return false
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return event.action == KeyEvent.ACTION_UP
        val typed = printable(event) ?: return false
        current.text(typed)
        return true
    }

    private fun printable(event: KeyEvent): String? {
        val raw = event.unicodeChar
        if (raw == 0) return null

        val code = if (raw and android.view.KeyCharacterMap.COMBINING_ACCENT != 0) {
            raw and android.view.KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            raw
        }

        if (code <= 0 || !Character.isValidCodePoint(code)) return null
        if (Character.getType(code) == Character.CONTROL.toInt()) return null
        return runCatching { String(Character.toChars(code)) }.getOrNull()
    }

    fun askPin(): String? {
        val ask = PinAsk()
        activity.runOnUiThread { state.pinAsk = ask }
        return ask.await(180)
    }

    fun confirmSas(sas: String): Boolean {
        val ask = SasAsk(sas)
        activity.runOnUiThread { state.sasAsk = ask }
        return ask.await(120)
    }

    fun offerForget(host: String, reason: String?) {
        if (trust.find(host) == null) return
        activity.runOnUiThread { state.trustAsk = TrustAsk(host, reason) }
    }

    fun forget(host: String) {
        trust.forget(host)
        status("$host forgotten: reconnect to pair again")
    }

    private fun prefs() =
        activity.getSharedPreferences("wss", Activity.MODE_PRIVATE)

    fun togglePointer() {
        val current = sender ?: return
        current.mode = if (current.mode == PointerMode.ABSOLUTE) PointerMode.TRACKPAD else PointerMode.ABSOLUTE
        state.pointer = if (current.mode == PointerMode.TRACKPAD) Pointer.Trackpad else Pointer.Tap
        prefs().edit().putBoolean("trackpad", current.mode == PointerMode.TRACKPAD).apply()
        status(
            if (current.mode == PointerMode.ABSOLUTE) "pointer: tap where you want to go"
            else "pointer: drag like a touchpad"
        )
    }

    fun toggleKeyboard() {
        if (sink?.hasFocus() == true) hideKeyboard() else showKeyboard()
    }

    private fun showKeyboard() {
        val view = sink ?: return
        view.isFocusableInTouchMode = true
        view.requestFocus()
        val manager = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        state.keyboardOpen = true
        state.barAtTop = true
        view.postDelayed({ (activity as? MainActivity)?.goFullScreen() }, 250)
    }

    private fun hideKeyboard() {
        val view = sink
        val manager = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        if (view != null) {
            manager.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        state.keyboardOpen = false
        state.barAtTop = false
        (activity as? MainActivity)?.goFullScreen()
    }

    private inline fun guard(body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            status("key ignored: ${e.javaClass.simpleName}")
        }
    }

    private fun onSoftKey(event: KeyEvent) {
        val current = sender ?: return
        val mapped = PhysicalInput.keyCodeOf(event.keyCode)

        if (mapped != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> current.keyDown(mapped)
                KeyEvent.ACTION_UP -> current.keyUp(mapped)
            }
            return
        }

        if (event.action != KeyEvent.ACTION_DOWN) return
        printable(event)?.let { current.text(it) }
    }
}
