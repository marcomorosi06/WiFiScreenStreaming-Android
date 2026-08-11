package com.cuscus.wifiscreenstreaming

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

class WssProtocolException(message: String) : Exception(message)

object WssCrypto {

    const val LABEL = "WSS-INPUT-v1"

    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val PREFIX_SIZE = 4
    const val TAG_SIZE = 16
    const val MAX_PLAINTEXT = 65536
    const val MAX_FRAME = MAX_PLAINTEXT + TAG_SIZE
    const val MIN_FRAME = TAG_SIZE + 1
    const val MAX_COUNTER = 1L shl 48

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun randomDigits(count: Int): String {
        val out = StringBuilder(count)
        repeat(count) { out.append(random.nextInt(10)) }
        return out.toString()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun hkdfRaw(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray =
        hkdfRaw(ikm, salt, info.toByteArray(Charsets.UTF_8), length)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun wipe(secret: ByteArray) = secret.fill(0)

    fun isAllZero(value: ByteArray): Boolean {
        var acc = 0
        for (b in value) acc = acc or b.toInt()
        return acc == 0
    }
}

class Transcript {

    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        digest.update(WssCrypto.LABEL.toByteArray(Charsets.UTF_8))
    }

    fun add(message: ByteArray): Transcript {
        val header = ByteArray(4)
        header[0] = ((message.size ushr 24) and 0xFF).toByte()
        header[1] = ((message.size ushr 16) and 0xFF).toByte()
        header[2] = ((message.size ushr 8) and 0xFF).toByte()
        header[3] = (message.size and 0xFF).toByte()
        digest.update(header)
        digest.update(message)
        return this
    }

    fun snapshot(): ByteArray = (digest.clone() as MessageDigest).digest()
}

class SessionKeys(
    val clientToServerKey: ByteArray,
    val clientToServerPrefix: ByteArray,
    val serverToClientKey: ByteArray,
    val serverToClientPrefix: ByteArray,
    val sas: String
) {

    fun wipe() {
        WssCrypto.wipe(clientToServerKey)
        WssCrypto.wipe(serverToClientKey)
    }

    companion object {

        fun derive(sharedSecret: ByteArray, transcript: ByteArray): SessionKeys {
            require(sharedSecret.isNotEmpty()) { "empty shared secret" }
            require(!WssCrypto.isAllZero(sharedSecret)) { "degenerate shared secret" }

            val sasRaw = WssCrypto.hkdf(sharedSecret, transcript, "wss1 sas", 8)
            var value = 0L
            for (b in sasRaw) value = (value shl 8) or (b.toLong() and 0xFF)
            val digits = java.lang.Long.remainderUnsigned(value, 1_000_000L)
                .toString()
                .padStart(6, '0')

            return SessionKeys(
                clientToServerKey = WssCrypto.hkdf(sharedSecret, transcript, "wss1 c2s key", WssCrypto.KEY_SIZE),
                clientToServerPrefix = WssCrypto.hkdf(sharedSecret, transcript, "wss1 c2s nonce", WssCrypto.PREFIX_SIZE),
                serverToClientKey = WssCrypto.hkdf(sharedSecret, transcript, "wss1 s2c key", WssCrypto.KEY_SIZE),
                serverToClientPrefix = WssCrypto.hkdf(sharedSecret, transcript, "wss1 s2c nonce", WssCrypto.PREFIX_SIZE),
                sas = digits
            )
        }
    }
}

class SecureChannel(
    input: InputStream,
    output: OutputStream,
    private val sendKey: ByteArray,
    private val sendPrefix: ByteArray,
    private val receiveKey: ByteArray,
    private val receivePrefix: ByteArray
) {

    private val reader = DataInputStream(input)
    private val writer = DataOutputStream(output)

    private val sendLock = java.lang.Object()
    private val receiveLock = java.lang.Object()

    private var sendCounter = 0L
    private var receiveCounter = 0L

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray {
        val out = ByteArray(WssCrypto.NONCE_SIZE)
        System.arraycopy(prefix, 0, out, 0, WssCrypto.PREFIX_SIZE)
        for (i in 0 until 8) {
            out[WssCrypto.PREFIX_SIZE + i] = ((counter ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        return out
    }

    private fun aad(counter: Long, length: Int): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) out[i] = ((counter ushr (56 - 8 * i)) and 0xFF).toByte()
        out[8] = ((length ushr 24) and 0xFF).toByte()
        out[9] = ((length ushr 16) and 0xFF).toByte()
        out[10] = ((length ushr 8) and 0xFF).toByte()
        out[11] = (length and 0xFF).toByte()
        return out
    }

    fun send(plaintext: ByteArray) = synchronized(sendLock) {
        if (plaintext.isEmpty()) {
            throw WssProtocolException("empty message: every message has at least the type byte")
        }
        if (plaintext.size > WssCrypto.MAX_PLAINTEXT) {
            throw WssProtocolException("message too large: ${plaintext.size}")
        }
        if (sendCounter >= WssCrypto.MAX_COUNTER) {
            throw WssProtocolException("send counter exhausted")
        }

        val engine = ChaCha20Poly1305()
        engine.init(true, ParametersWithIV(KeyParameter(sendKey), nonce(sendPrefix, sendCounter)))

        val length = plaintext.size + WssCrypto.TAG_SIZE
        val header = aad(sendCounter, length)
        engine.processAADBytes(header, 0, header.size)

        val out = ByteArray(engine.getOutputSize(plaintext.size))
        var written = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
        written += engine.doFinal(out, written)

        if (written != length) throw WssProtocolException("unexpected ciphertext length: $written")

        writer.writeInt(length)
        writer.write(out, 0, written)
        writer.flush()

        sendCounter++
    }

    fun receive(): ByteArray {
        val length: Int
        val frame: ByteArray
        val counter: Long

        synchronized(receiveLock) {
            if (receiveCounter >= WssCrypto.MAX_COUNTER) {
                throw WssProtocolException("receive counter exhausted")
            }
            length = try {
                reader.readInt()
            } catch (e: EOFException) {
                throw WssProtocolException("connection closed by the peer")
            }
            if (length < WssCrypto.MIN_FRAME || length > WssCrypto.MAX_FRAME) {
                throw WssProtocolException("invalid frame length: $length")
            }
            frame = ByteArray(length)
            try {
                reader.readFully(frame)
            } catch (e: EOFException) {
                throw WssProtocolException("truncated frame: expected $length byte")
            }
            counter = receiveCounter
            receiveCounter++
        }

        val engine = ChaCha20Poly1305()
        engine.init(false, ParametersWithIV(KeyParameter(receiveKey), nonce(receivePrefix, counter)))

        val header = aad(counter, length)
        engine.processAADBytes(header, 0, header.size)

        val out = ByteArray(engine.getOutputSize(length))
        return try {
            var written = engine.processBytes(frame, 0, length, out, 0)
            written += engine.doFinal(out, written)
            out.copyOf(written)
        } catch (e: Exception) {
            throw WssProtocolException("authentication failed: frame tampered with, replayed or out of order")
        }
    }
}
