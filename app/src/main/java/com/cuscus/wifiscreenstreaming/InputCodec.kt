package com.cuscus.wifiscreenstreaming

const val INPUT_MOUSE_ABS = 0x01
const val INPUT_MOUSE_REL = 0x02
const val INPUT_MOUSE_DOWN = 0x03
const val INPUT_MOUSE_UP = 0x04
const val INPUT_SCROLL = 0x05
const val INPUT_KEY_DOWN = 0x06
const val INPUT_KEY_UP = 0x07
const val INPUT_TEXT = 0x08
const val INPUT_VIDEO = 0x09
const val INPUT_PING = 0x7F

const val BUTTON_LEFT = 1
const val BUTTON_MIDDLE = 2
const val BUTTON_RIGHT = 3

const val MAX_TEXT_BYTES = 4096

object Vk {
    const val BACK_SPACE = 8
    const val TAB = 9
    const val ENTER = 10
    const val ESCAPE = 27
    const val PAGE_UP = 33
    const val PAGE_DOWN = 34
    const val END = 35
    const val HOME = 36
    const val LEFT = 37
    const val UP = 38
    const val RIGHT = 39
    const val DOWN = 40
    const val DELETE = 127
    const val INSERT = 155
    const val SHIFT = 16
    const val CONTROL = 17
    const val ALT = 18
    const val ALT_GRAPH = 65406
    const val WINDOWS = 524
    const val CONTEXT_MENU = 525
    const val CAPS_LOCK = 20
    const val NUM_LOCK = 144
    const val SCROLL_LOCK = 145
    const val PAUSE = 19
    const val PRINTSCREEN = 154
    const val SPACE = 32

    const val DIGIT_0 = 48
    const val LETTER_A = 65
    const val F1 = 112
    const val NUMPAD_0 = 96

    const val MULTIPLY = 106
    const val ADD = 107
    const val SUBTRACT = 109
    const val DECIMAL = 110
    const val DIVIDE = 111

    const val COMMA = 44
    const val MINUS = 45
    const val PERIOD = 46
    const val SLASH = 47
    const val SEMICOLON = 59
    const val EQUALS = 61
    const val OPEN_BRACKET = 91
    const val BACK_SLASH = 92
    const val CLOSE_BRACKET = 93
    const val BACK_QUOTE = 192
    const val QUOTE = 222
}

sealed class InputMessage {

    data class Video(val wanted: Boolean) : InputMessage()
    data class MoveAbsolute(val x: Int, val y: Int) : InputMessage()
    data class MoveRelative(val dx: Int, val dy: Int) : InputMessage()
    data class ButtonDown(val button: Int) : InputMessage()
    data class ButtonUp(val button: Int) : InputMessage()
    data class Scroll(val amount: Int) : InputMessage()
    data class KeyDown(val keyCode: Int) : InputMessage()
    data class KeyUp(val keyCode: Int) : InputMessage()
    data class Text(val value: String) : InputMessage()
    object Ping : InputMessage()

    fun encode(): ByteArray = when (this) {
        is MoveAbsolute -> byteArrayOf(
            INPUT_MOUSE_ABS.toByte(),
            (x shr 8).toByte(), x.toByte(),
            (y shr 8).toByte(), y.toByte()
        )
        is MoveRelative -> byteArrayOf(
            INPUT_MOUSE_REL.toByte(),
            (dx shr 8).toByte(), dx.toByte(),
            (dy shr 8).toByte(), dy.toByte()
        )
        is Video -> byteArrayOf(INPUT_VIDEO.toByte(), if (wanted) 1 else 0)
        is ButtonDown -> byteArrayOf(INPUT_MOUSE_DOWN.toByte(), button.toByte())
        is ButtonUp -> byteArrayOf(INPUT_MOUSE_UP.toByte(), button.toByte())
        is Scroll -> byteArrayOf(INPUT_SCROLL.toByte(), (amount shr 8).toByte(), amount.toByte())
        is KeyDown -> byteArrayOf(INPUT_KEY_DOWN.toByte(), (keyCode shr 8).toByte(), keyCode.toByte())
        is KeyUp -> byteArrayOf(INPUT_KEY_UP.toByte(), (keyCode shr 8).toByte(), keyCode.toByte())
        is Text -> {
            val raw = value.toByteArray(Charsets.UTF_8)
            if (raw.size > MAX_TEXT_BYTES) throw WssProtocolException("text too long: ${raw.size}")
            ByteArray(3 + raw.size).also {
                it[0] = INPUT_TEXT.toByte()
                it[1] = (raw.size shr 8).toByte()
                it[2] = raw.size.toByte()
                System.arraycopy(raw, 0, it, 3, raw.size)
            }
        }
        Ping -> byteArrayOf(INPUT_PING.toByte())
    }

    companion object {

        private fun u16(data: ByteArray, at: Int): Int =
            ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

        private fun i16(data: ByteArray, at: Int): Int = u16(data, at).toShort().toInt()

        fun decode(data: ByteArray): InputMessage? {
            if (data.isEmpty()) throw WssProtocolException("empty input message")

            fun need(size: Int) {
                if (data.size < size) throw WssProtocolException("truncated input message: ${data.size}")
            }

            return when (data[0].toInt() and 0xFF) {
                INPUT_MOUSE_ABS -> {
                    need(5)
                    val x = u16(data, 1)
                    val y = u16(data, 3)
                    if (x > 1000 || y > 1000) throw WssProtocolException("coordinates out of range: $x $y")
                    MoveAbsolute(x, y)
                }
                INPUT_MOUSE_REL -> {
                    need(5)
                    MoveRelative(i16(data, 1), i16(data, 3))
                }
                INPUT_MOUSE_DOWN -> {
                    need(2)
                    ButtonDown(validButton(data[1].toInt() and 0xFF))
                }
                INPUT_MOUSE_UP -> {
                    need(2)
                    ButtonUp(validButton(data[1].toInt() and 0xFF))
                }
                INPUT_SCROLL -> {
                    need(3)
                    Scroll(i16(data, 1).coerceIn(-64, 64))
                }
                INPUT_KEY_DOWN -> {
                    need(3)
                    KeyDown(u16(data, 1))
                }
                INPUT_KEY_UP -> {
                    need(3)
                    KeyUp(u16(data, 1))
                }
                INPUT_TEXT -> {
                    need(3)
                    val size = u16(data, 1)
                    if (size > MAX_TEXT_BYTES) throw WssProtocolException("text too long: $size")
                    need(3 + size)
                    Text(String(data, 3, size, Charsets.UTF_8))
                }
                INPUT_VIDEO -> {
                    need(2)
                    Video(data[1].toInt() != 0)
                }
                INPUT_PING -> Ping
                else -> null
            }
        }

        private fun validButton(value: Int): Int =
            if (value in BUTTON_LEFT..BUTTON_RIGHT) value
            else throw WssProtocolException("invalid button: $value")
    }
}
