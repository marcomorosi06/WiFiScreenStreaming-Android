package com.cuscus.wifiscreenstreaming

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private const val PROTOCOL_VERSION = 2
private const val MAGIC_0 = 0x57.toByte()
private const val MAGIC_1 = 0x46.toByte()
private const val HEADER_SIZE = 10
private const val MTU = 1400

private const val MSG_HELLO = "HELLO_FROM_CLIENT"
private const val MSG_HELLO_ACK = "HELLO_ACK"
private const val MSG_INCOMPATIBLE = "WFAS_INCOMPATIBLE"
private const val MSG_AUTH_REQUIRED = "WFAS_AUTH_REQUIRED"
private const val MSG_UNAUTHORIZED = "WFAS_UNAUTHORIZED"
private const val MSG_BUSY = "WFAS_BUSY"
private const val MSG_PING = "PING"
private const val MSG_BYE = "BYE"
private const val MSG_CLIENT_BYE = "CLIENT_BYE"

private const val SILENCE_TIMEOUT_MS = 5000L
private const val HANDSHAKE_MS = 15000L
private const val HELLO_EVERY_MS = 500L

class WfasClient(
    private val host: String,
    private val port: Int,
    private val sampleRate: Int,
    private val channels: Int,
    private val onStatus: (String) -> Unit
) {

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var server: InetAddress? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread { run() }.also {
            it.isDaemon = true
            it.start()
        }
    }

    val isRunning: Boolean get() = running.get()

    fun stop() {
        if (!running.getAndSet(false)) return
        val sock = socket
        val target = server
        if (sock != null && target != null) {
            runCatching { send(sock, target, MSG_CLIENT_BYE) }
        }
        runCatching { sock?.close() }
        runCatching { thread?.join(300) }
    }

    private fun run() {
        var track: AudioTrack? = null
        try {
            val address = InetAddress.getByName(host)
            server = address
            val sock = DatagramSocket()
            sock.soTimeout = 1000
            socket = sock

            if (!handshake(sock, address)) return

            track = newTrack()
            track.play()
            onStatus("audio connected")

            val buffer = ByteArray(MTU)
            val packet = DatagramPacket(buffer, buffer.size)
            var lastHeard = System.currentTimeMillis()

            while (running.get()) {
                packet.setData(buffer, 0, buffer.size)
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastHeard > SILENCE_TIMEOUT_MS) {
                        onStatus("audio: timeout")
                        break
                    }
                    continue
                }

                lastHeard = System.currentTimeMillis()

                val length = packet.length
                if (isAudio(buffer, length)) {
                    val payload = length - HEADER_SIZE
                    if (payload > 0) track.write(buffer, HEADER_SIZE, payload)
                    continue
                }

                when (val text = String(buffer, 0, length, Charsets.US_ASCII)) {
                    MSG_PING -> {}
                    MSG_BYE -> {
                        onStatus("audio: the server closed")
                        running.set(false)
                    }
                    else -> if (text.startsWith(MSG_BYE)) {
                        onStatus("audio: the server closed")
                        running.set(false)
                    }
                }
            }

            runCatching { send(sock, address, MSG_CLIENT_BYE) }
        } catch (e: Exception) {
            if (running.get()) onStatus("audio: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            running.set(false)
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { socket?.close() }
        }
    }

    private fun handshake(sock: DatagramSocket, address: InetAddress): Boolean {
        val hello = "$MSG_HELLO;v=$PROTOCOL_VERSION"
        val buffer = ByteArray(MTU)
        val packet = DatagramPacket(buffer, buffer.size)
        val deadline = System.currentTimeMillis() + HANDSHAKE_MS
        var nextHello = 0L

        while (running.get() && System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            if (now >= nextHello) {
                send(sock, address, hello)
                nextHello = now + HELLO_EVERY_MS
            }

            packet.setData(buffer, 0, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }

            if (isAudio(buffer, packet.length)) continue

            val text = String(buffer, 0, packet.length, Charsets.US_ASCII)
            when {
                text.startsWith(MSG_HELLO_ACK) -> return true
                text.startsWith(MSG_AUTH_REQUIRED) -> {
                    onStatus("audio: the server wants a key, not supported here")
                    return false
                }
                text.startsWith(MSG_UNAUTHORIZED) -> {
                    onStatus("audio: unauthorized")
                    return false
                }
                text.startsWith(MSG_BUSY) -> {
                    onStatus("audio: server busy with another client")
                    return false
                }
                text.startsWith(MSG_INCOMPATIBLE) -> {
                    onStatus("audio: incompatible protocol version")
                    return false
                }
            }
        }
        if (running.get()) onStatus("audio: no reply from the WFAS server")
        return false
    }

    private fun send(sock: DatagramSocket, address: InetAddress, message: String) {
        val bytes = message.toByteArray(Charsets.US_ASCII)
        sock.send(DatagramPacket(bytes, bytes.size, address, port))
    }

    private fun isAudio(buffer: ByteArray, length: Int): Boolean =
        length >= HEADER_SIZE &&
            buffer[0] == MAGIC_0 &&
            buffer[1] == MAGIC_1 &&
            buffer[2].toInt() == PROTOCOL_VERSION

    private fun newTrack(): AudioTrack {
        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minimum = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val wanted = sampleRate * channels * 2 * 120 / 1000

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, wanted))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(AudioTrack.getMaxVolume()) }
    }
}
