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

import android.view.MotionEvent
import android.view.View
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PointerMode { ABSOLUTE, TRACKPAD }

class InputSender(
    private val channel: SecureChannel,
    private val onError: (String) -> Unit
) {

    private val queue = ArrayBlockingQueue<InputMessage>(256)
    private val running = AtomicBoolean(true)
    private var dropped = 0L
    private var sent = 0L

    @Volatile
    var lastError: String? = null
        private set

    var mode = PointerMode.ABSOLUTE
    var trackpadSpeed = 1.6f

    init {
        Thread {
            try {
                while (running.get()) {
                    val message = queue.poll(500, TimeUnit.MILLISECONDS)
                        ?: (if (holding) InputMessage.Ping else null)
                        ?: continue
                    channel.send(message.encode())
                    sent++
                    if (sent == 1L) onError("input: first message sent, the PC should react")
                }
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                if (running.get()) onError("input: channel down after $sent sent: $lastError")
            } finally {
                running.set(false)
            }
        }.also {
            it.isDaemon = true
            it.name = "wss-input"
            it.start()
        }
    }

    val isRunning: Boolean get() = running.get()

    fun stop() {
        running.set(false)
    }

    private fun submit(message: InputMessage) {
        if (!running.get()) return
        if (queue.offer(message)) return

        if (message is InputMessage.MoveAbsolute || message is InputMessage.MoveRelative) {
            dropped++
            return
        }

        val kept = ArrayList<InputMessage>(queue.size)
        queue.drainTo(kept)
        kept.forEach {
            val throwaway = it is InputMessage.MoveAbsolute || it is InputMessage.MoveRelative
            if (throwaway) dropped++ else queue.offer(it)
        }
        if (!queue.offer(message)) {
            queue.poll()
            if (!queue.offer(message)) dropped++
        }
    }

    fun wantsVideo(wanted: Boolean) = submit(InputMessage.Video(wanted))

    fun key(code: Int) {
        submit(InputMessage.KeyDown(code))
        submit(InputMessage.KeyUp(code))
    }

    fun keyDown(code: Int) = submit(InputMessage.KeyDown(code))

    fun keyUp(code: Int) = submit(InputMessage.KeyUp(code))

    fun text(value: String) {
        if (value.isNotEmpty()) submit(InputMessage.Text(value))
    }

    private var downAt = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pressing = false
    private var longPressed = false
    private var scrolling = false
    private var scrollRemainder = 0f
    private var pointerDown = false
    private var gesture = 0

    private val tapSlop = 12f
    private val longPressMs = 450L
    private val scrollStep = 48f

    private var mouseButtons = 0
    private var mouseX = 0f
    private var mouseY = 0f
    private var mouseSeen = false

    fun onTouch(view: View, event: MotionEvent): Boolean {
        if (!running.get()) return false
        val width = view.width.takeIf { it > 0 } ?: return false
        val height = view.height.takeIf { it > 0 } ?: return false

        if (PhysicalInput.fromMouse(event)) return onMouse(event, width, height)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseHeld()
                gesture++
                val id = gesture
                pointerDown = true
                downAt = System.currentTimeMillis()
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                scrolling = false
                scrollRemainder = 0f

                if (mode == PointerMode.ABSOLUTE) {
                    moveAbsolute(event.x, event.y, width, height)
                }
                view.postDelayed({ maybeLongPress(id) }, longPressMs)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    releaseHeld()
                    scrolling = true
                    lastY = event.getY(0)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount >= 2) {
                    val current = event.getY(0)
                    scrollRemainder += (lastY - current)
                    lastY = current
                    while (abs(scrollRemainder) >= scrollStep) {
                        val direction = if (scrollRemainder > 0) 1 else -1
                        submit(InputMessage.Scroll(direction))
                        scrollRemainder -= direction * scrollStep
                    }
                    return true
                }

                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(event.x - startX) > tapSlop || abs(event.y - startY) > tapSlop) moved = true

                when (mode) {
                    PointerMode.ABSOLUTE -> {
                        if (moved && !pressing && !longPressed) press(BUTTON_LEFT)
                        moveAbsolute(event.x, event.y, width, height)
                    }
                    PointerMode.TRACKPAD -> {
                        submit(
                            InputMessage.MoveRelative(
                                (dx * trackpadSpeed).roundToInt().coerceIn(-2000, 2000),
                                (dy * trackpadSpeed).roundToInt().coerceIn(-2000, 2000)
                            )
                        )
                    }
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP -> {
                pointerDown = false
                gesture++
                val quick = System.currentTimeMillis() - downAt < longPressMs
                val wasHolding = pressing || longPressed
                releaseHeld()

                if (!scrolling && !wasHolding && quick && !moved) {
                    if (mode == PointerMode.ABSOLUTE) moveAbsolute(event.x, event.y, width, height)
                    submit(InputMessage.ButtonDown(BUTTON_LEFT))
                    submit(InputMessage.ButtonUp(BUTTON_LEFT))
                }
                scrolling = false
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                gesture++
                releaseHeld()
                scrolling = false
            }
        }
        return true
    }

    fun onGeneric(view: View, event: MotionEvent): Boolean {
        if (!running.get()) return false
        val width = view.width.takeIf { it > 0 } ?: return false
        val height = view.height.takeIf { it > 0 } ?: return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val notches = (-vertical).roundToInt()
                if (notches != 0) submit(InputMessage.Scroll(notches.coerceIn(-16, 16)))
                true
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                trackMouse(event, width, height)
                true
            }
            else -> false
        }
    }

    private fun onMouse(event: MotionEvent, width: Int, height: Int): Boolean {
        trackMouse(event, width, height)

        val state = event.buttonState
        PhysicalInput.buttons.forEach { flag ->
            val wasDown = mouseButtons and flag != 0
            val isDown = state and flag != 0
            if (wasDown == isDown) return@forEach
            val button = PhysicalInput.buttonOf(flag) ?: return@forEach
            submit(if (isDown) InputMessage.ButtonDown(button) else InputMessage.ButtonUp(button))
        }
        mouseButtons = state

        if (event.actionMasked == MotionEvent.ACTION_CANCEL) releaseMouseButtons()
        return true
    }

    private fun trackMouse(event: MotionEvent, width: Int, height: Int) {
        if (mode == PointerMode.TRACKPAD && mouseSeen) {
            val dx = event.x - mouseX
            val dy = event.y - mouseY
            if (dx != 0f || dy != 0f) {
                submit(
                    InputMessage.MoveRelative(
                        (dx * trackpadSpeed).roundToInt().coerceIn(-2000, 2000),
                        (dy * trackpadSpeed).roundToInt().coerceIn(-2000, 2000)
                    )
                )
            }
        } else {
            moveAbsolute(event.x, event.y, width, height)
        }
        mouseX = event.x
        mouseY = event.y
        mouseSeen = true
    }

    private fun releaseMouseButtons() {
        PhysicalInput.buttons.forEach { flag ->
            if (mouseButtons and flag != 0) {
                PhysicalInput.buttonOf(flag)?.let { submit(InputMessage.ButtonUp(it)) }
            }
        }
        mouseButtons = 0
    }

    private fun maybeLongPress(id: Int) {
        if (!running.get() || id != gesture || !pointerDown) return
        if (moved || pressing || scrolling || longPressed) return
        longPressed = true
        submit(InputMessage.ButtonDown(BUTTON_RIGHT))
    }

    private fun press(button: Int) {
        pressing = true
        submit(InputMessage.ButtonDown(button))
    }

    fun releaseHeld() {
        releaseMouseButtons()
        if (pressing) {
            pressing = false
            submit(InputMessage.ButtonUp(BUTTON_LEFT))
        }
        if (longPressed) {
            longPressed = false
            submit(InputMessage.ButtonUp(BUTTON_RIGHT))
        }
    }

    val holding: Boolean get() = pressing || longPressed || mouseButtons != 0

    private fun moveAbsolute(x: Float, y: Float, width: Int, height: Int) {
        val px = (x / width * 1000f).roundToInt().coerceIn(0, 1000)
        val py = (y / height * 1000f).roundToInt().coerceIn(0, 1000)
        submit(InputMessage.MoveAbsolute(px, py))
    }
}
