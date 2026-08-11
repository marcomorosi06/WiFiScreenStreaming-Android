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

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.SecureRandom

private const val VERSION: Byte = 1

private const val TYPE_HELLO = 0x20
private const val TYPE_DECLINE = 0x22

private const val MODE_SESSION: Byte = 0
private const val MODE_PAIRING: Byte = 1
private const val MODE_REFUSED: Byte = 2

private const val SERVER_ID = "wss-server"
private const val CLIENT_ID = "wss-client"

private const val MAX_HANDSHAKE_MESSAGE = 8192
private const val HANDSHAKE_TIMEOUT_MS = 120_000

class DeviceRequest(
    val publicKey: ByteArray,
    val name: String,
    val sas: String
) {
    val fingerprint: String get() = WssIdentity.fingerprintOf(publicKey)
}

class Session(
    val channel: SecureChannel,
    val peerPublicKey: ByteArray,
    val peerName: String,
    val sas: String,
    val paired: Boolean
)

class PlainStream(input: InputStream, output: OutputStream) {

    private val reader = DataInputStream(input)
    private val writer = DataOutputStream(output)
    private val transcript = Transcript()

    fun send(message: ByteArray) {
        if (message.isEmpty() || message.size > MAX_HANDSHAKE_MESSAGE) {
            throw WssProtocolException("invalid handshake message size: ${message.size}")
        }
        writer.writeInt(message.size)
        writer.write(message)
        writer.flush()
        transcript.add(message)
    }

    fun receive(): ByteArray {
        val size = try {
            reader.readInt()
        } catch (e: EOFException) {
            throw WssProtocolException("connection closed during the handshake")
        } catch (e: java.net.SocketTimeoutException) {
            throw WssProtocolException("the peer did not answer in time")
        } catch (e: java.io.IOException) {
            throw WssProtocolException("network error during the handshake: ${e.message}")
        }
        if (size <= 0 || size > MAX_HANDSHAKE_MESSAGE) {
            throw WssProtocolException("invalid handshake message size: $size")
        }
        val message = ByteArray(size)
        try {
            reader.readFully(message)
        } catch (e: EOFException) {
            throw WssProtocolException("truncated handshake message")
        } catch (e: java.net.SocketTimeoutException) {
            throw WssProtocolException("the peer stopped halfway through a message")
        } catch (e: java.io.IOException) {
            throw WssProtocolException("network error during the handshake: ${e.message}")
        }
        transcript.add(message)
        return message
    }

    fun snapshot(): ByteArray = transcript.snapshot()
}

private class Writer {
    private val buffer = ByteArrayOutputStream()
    private val out = DataOutputStream(buffer)

    fun u8(value: Int) = apply { out.writeByte(value) }
    fun bytes(value: ByteArray) = apply { out.write(value) }
    fun vec16(value: ByteArray) = apply {
        if (value.size > 0xFFFF) throw WssProtocolException("field too long")
        out.writeShort(value.size)
        out.write(value)
    }

    fun number(value: BigInteger) = vec16(value.toByteArray())
    fun text(value: String) = vec16(value.toByteArray(Charsets.UTF_8))
    fun build(): ByteArray = buffer.toByteArray()
}

private class Reader(message: ByteArray) {
    private val input = DataInputStream(ByteArrayInputStream(message))

    private fun <T> guarded(what: String, body: () -> T): T = try {
        body()
    } catch (e: WssProtocolException) {
        throw e
    } catch (e: Exception) {
        throw WssProtocolException("malformed message: $what")
    }

    fun u8(): Int = guarded("byte") { input.readUnsignedByte() }

    fun bytes(size: Int): ByteArray = guarded("$size byte") {
        val out = ByteArray(size)
        input.readFully(out)
        out
    }

    fun vec16(): ByteArray = guarded("length prefixed field") {
        val size = input.readUnsignedShort()
        if (size > MAX_HANDSHAKE_MESSAGE) throw WssProtocolException("field too long")
        val out = ByteArray(size)
        input.readFully(out)
        out
    }

    fun number(): BigInteger = guarded("intero") { BigInteger(vec16()) }
    fun text(): String = String(vec16(), Charsets.UTF_8)
}

private fun writeRound1(payload: JPAKERound1Payload): ByteArray = Writer()
    .u8(0x10)
    .text(payload.participantId)
    .number(payload.gx1)
    .number(payload.gx2)
    .number(payload.knowledgeProofForX1[0])
    .number(payload.knowledgeProofForX1[1])
    .number(payload.knowledgeProofForX2[0])
    .number(payload.knowledgeProofForX2[1])
    .build()

private fun readRound1(message: ByteArray): JPAKERound1Payload {
    val reader = Reader(message)
    if (reader.u8() != 0x10) throw WssProtocolException("expected PAIR_ROUND1")
    val who = reader.text()
    val gx1 = reader.number()
    val gx2 = reader.number()
    val proof1 = arrayOf(reader.number(), reader.number())
    val proof2 = arrayOf(reader.number(), reader.number())
    return JPAKERound1Payload(who, gx1, gx2, proof1, proof2)
}

private fun writeRound2(payload: JPAKERound2Payload): ByteArray = Writer()
    .u8(0x11)
    .text(payload.participantId)
    .number(payload.a)
    .number(payload.knowledgeProofForX2s[0])
    .number(payload.knowledgeProofForX2s[1])
    .build()

private fun readRound2(message: ByteArray): JPAKERound2Payload {
    val reader = Reader(message)
    if (reader.u8() != 0x11) throw WssProtocolException("expected PAIR_ROUND2")
    val who = reader.text()
    val a = reader.number()
    val proof = arrayOf(reader.number(), reader.number())
    return JPAKERound2Payload(who, a, proof)
}

private fun writeRound3(payload: JPAKERound3Payload): ByteArray = Writer()
    .u8(0x12)
    .text(payload.participantId)
    .number(payload.macTag)
    .build()

private fun readRound3(message: ByteArray): JPAKERound3Payload {
    val reader = Reader(message)
    if (reader.u8() != 0x12) throw WssProtocolException("expected PAIR_ROUND3")
    return JPAKERound3Payload(reader.text(), reader.number())
}

private class Ephemeral {
    private val secret = X25519PrivateKeyParameters(SecureRandom())
    val publicKey: ByteArray = secret.generatePublicKey().encoded

    fun agree(peer: ByteArray): ByteArray {
        if (peer.size != 32) throw WssProtocolException("ephemeral key of wrong size")
        val shared = ByteArray(32)
        val agreement = X25519Agreement()
        agreement.init(secret)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peer, 0), shared, 0)
        } catch (e: Exception) {
            throw WssProtocolException("X25519 exchange refused: ${e.message}")
        }
        if (WssCrypto.isAllZero(shared)) {
            throw WssProtocolException("small order point in the X25519 exchange")
        }
        return shared
    }
}

object WssHandshake {

    private val GROUP = JPAKEPrimeOrderGroups.NIST_3072

    fun serve(
        socket: java.net.Socket,
        identity: WssIdentity,
        devices: WssDevices,
        pairing: PairingWindow?,
        serverName: String,
        onSas: (String) -> Unit = {},
        onHandshakeDone: () -> Unit = {},
        approve: (DeviceRequest) -> Boolean
    ): Session? = guardSocket(socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val io = PlainStream(input, output)
        val ephemeral = Ephemeral()

        val hello = Reader(io.receive())
        val type = hello.u8()
        if (type == TYPE_DECLINE) return@guardSocket null
        if (type != TYPE_HELLO) throw WssProtocolException("expected HELLO from the client")
        if (hello.u8() != VERSION.toInt()) throw WssProtocolException("unsupported protocol version")
        val wantsPairing = hello.u8() == 1
        val clientIdentity = hello.bytes(WssIdentity.KEY_SIZE)
        val clientEphemeral = hello.bytes(32)

        val known = devices.find(clientIdentity)

        val mode: Byte = when {
            !wantsPairing && known != null -> MODE_SESSION
            wantsPairing && pairing != null && pairing.isOpen -> MODE_PAIRING
            else -> MODE_REFUSED
        }

        io.send(
            Writer().u8(0x21).u8(VERSION.toInt()).u8(mode.toInt())
                .bytes(identity.publicKey).bytes(ephemeral.publicKey).build()
        )

        if (mode == MODE_REFUSED) {
            throw WssProtocolException(
                if (wantsPairing) "pairing not open on the server"
                else "device not authorized"
            )
        }

        val agreed = ephemeral.agree(clientEphemeral)

        val secret = if (mode == MODE_PAIRING) {
            val pin = pairing!!.claim()
            val participant = JPAKEParticipant(SERVER_ID, pin.toCharArray(), GROUP)
            runJPake(io, participant, serverFirst = true, agreed = agreed)
        } else {
            agreed
        }

        val toSign = io.snapshot()
        val keys = SessionKeys.derive(secret, toSign)

        if (mode == MODE_PAIRING) onSas(keys.sas)

        val channel = SecureChannel(
            input, output,
            keys.serverToClientKey, keys.serverToClientPrefix,
            keys.clientToServerKey, keys.clientToServerPrefix
        )
        channel.send(Writer().u8(0x30).bytes(identity.sign(toSign)).text(serverName).build())

        val proof = Reader(channel.receive())
        if (proof.u8() != 0x31) throw WssProtocolException("expected AUTH from the client")
        val signature = proof.bytes(WssIdentity.SIGNATURE_SIZE)
        val clientName = proof.text()

        if (!WssIdentity.verify(clientIdentity, toSign, signature)) {
            throw WssProtocolException("invalid client signature")
        }

        onHandshakeDone()

        if (mode == MODE_PAIRING) {
            val request = DeviceRequest(clientIdentity, clientName, keys.sas)
            if (!approve(request)) throw WssProtocolException("pairing refused by the user")
            devices.remember(clientIdentity, clientName)
            pairing?.close()
        } else {
            devices.touch(clientIdentity)
        }

        Session(channel, clientIdentity, clientName, keys.sas, mode == MODE_PAIRING)
    }

    fun decline(socket: java.net.Socket) {
        val io = PlainStream(java.io.ByteArrayInputStream(ByteArray(0)), socket.getOutputStream())
        io.send(Writer().u8(TYPE_DECLINE).u8(VERSION.toInt()).build())
    }

    fun connect(
        socket: java.net.Socket,
        identity: WssIdentity,
        trustedServerKey: ByteArray?,
        pin: String?,
        clientName: String,
        stream: java.io.InputStream? = null,
        confirmSas: (String) -> Boolean
    ): Session = guardSocket(socket) {
        val input = stream ?: socket.getInputStream()
        val output = socket.getOutputStream()
        val io = PlainStream(input, output)
        val ephemeral = Ephemeral()
        val pairing = trustedServerKey == null

        if (pairing && pin.isNullOrBlank()) {
            throw WssProtocolException("first connection: the PIN shown on the PC is required")
        }

        io.send(
            Writer().u8(TYPE_HELLO).u8(VERSION.toInt()).u8(if (pairing) 1 else 0)
                .bytes(identity.publicKey).bytes(ephemeral.publicKey).build()
        )

        val hello = Reader(io.receive())
        if (hello.u8() != 0x21) throw WssProtocolException("expected HELLO from the server")
        if (hello.u8() != VERSION.toInt()) throw WssProtocolException("unsupported protocol version")
        val mode = hello.u8().toByte()
        val serverIdentity = hello.bytes(WssIdentity.KEY_SIZE)
        val serverEphemeral = hello.bytes(32)

        when {
            mode == MODE_REFUSED ->
                throw WssProtocolException("the PC refused: unknown device or pairing closed")
            mode == MODE_PAIRING && !pairing ->
                throw WssProtocolException("the PC asks to pair again: possible impersonation attempt")
            mode == MODE_SESSION && pairing ->
                throw WssProtocolException("the PC claims to know us, but we do not have its identity")
        }

        if (!pairing && !trustedServerKey!!.contentEquals(serverIdentity)) {
            throw WssProtocolException(
                "the PC identity changed: someone may be impersonating it"
            )
        }

        val agreed = ephemeral.agree(serverEphemeral)

        val secret = if (mode == MODE_PAIRING) {
            val participant = JPAKEParticipant(CLIENT_ID, pin!!.filter { it.isDigit() }.toCharArray(), GROUP)
            runJPake(io, participant, serverFirst = false, agreed = agreed)
        } else {
            agreed
        }

        val toSign = io.snapshot()
        val keys = SessionKeys.derive(secret, toSign)
        val channel = SecureChannel(
            input, output,
            keys.clientToServerKey, keys.clientToServerPrefix,
            keys.serverToClientKey, keys.serverToClientPrefix
        )

        val proof = Reader(channel.receive())
        if (proof.u8() != 0x30) throw WssProtocolException("expected AUTH from the server")
        val signature = proof.bytes(WssIdentity.SIGNATURE_SIZE)
        val serverName = proof.text()

        if (!WssIdentity.verify(serverIdentity, toSign, signature)) {
            throw WssProtocolException("invalid PC signature")
        }

        if (mode == MODE_PAIRING && !confirmSas(keys.sas)) {
            throw WssProtocolException("verification code refused by the user")
        }

        channel.send(Writer().u8(0x31).bytes(identity.sign(toSign)).text(clientName).build())

        Session(channel, serverIdentity, serverName, keys.sas, mode == MODE_PAIRING)
    }

    private inline fun <T> guardSocket(socket: java.net.Socket, body: () -> T): T {
        runCatching { socket.soTimeout = HANDSHAKE_TIMEOUT_MS }
        try {
            val result = body()
            runCatching { socket.soTimeout = 0 }
            return result
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun runJPake(
        io: PlainStream,
        participant: JPAKEParticipant,
        serverFirst: Boolean,
        agreed: ByteArray
    ): ByteArray {
        val mine1 = writeRound1(participant.createRound1PayloadToSend())
        val theirs1: ByteArray
        if (serverFirst) {
            io.send(mine1)
            theirs1 = io.receive()
        } else {
            theirs1 = io.receive()
            io.send(mine1)
        }
        try {
            participant.validateRound1PayloadReceived(readRound1(theirs1))
        } catch (e: WssProtocolException) {
            throw e
        } catch (e: Exception) {
            throw WssProtocolException("first J-PAKE round refused: ${e.message}")
        }

        val mine2 = writeRound2(participant.createRound2PayloadToSend())
        val theirs2: ByteArray
        if (serverFirst) {
            io.send(mine2)
            theirs2 = io.receive()
        } else {
            theirs2 = io.receive()
            io.send(mine2)
        }
        try {
            participant.validateRound2PayloadReceived(readRound2(theirs2))
        } catch (e: WssProtocolException) {
            throw e
        } catch (e: Exception) {
            throw WssProtocolException("second J-PAKE round refused: ${e.message}")
        }

        val material = participant.calculateKeyingMaterial()

        val mine3 = writeRound3(participant.createRound3PayloadToSend(material))
        val theirs3: ByteArray
        if (serverFirst) {
            io.send(mine3)
            theirs3 = io.receive()
        } else {
            theirs3 = io.receive()
            io.send(mine3)
        }
        try {
            participant.validateRound3PayloadReceived(readRound3(theirs3), material)
        } catch (e: Exception) {
            throw WssProtocolException("wrong PIN, or someone is in the middle")
        }

        val pakeBytes = material.toByteArray()
        return WssCrypto.sha256("wss1 pair".toByteArray(), pakeBytes, agreed)
    }
}
