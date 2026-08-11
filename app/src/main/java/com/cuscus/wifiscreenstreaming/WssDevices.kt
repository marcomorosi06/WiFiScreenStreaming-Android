package com.cuscus.wifiscreenstreaming

import java.io.File
import java.util.Base64

data class Device(
    val publicKey: ByteArray,
    val name: String,
    val addedAt: Long,
    val lastSeen: Long
) {

    val fingerprint: String get() = WssIdentity.fingerprintOf(publicKey)

    override fun equals(other: Any?): Boolean =
        other is Device && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = publicKey.contentHashCode()

    override fun toString(): String = "$name  $fingerprint"
}

class WssDevices(private val file: File) {

    private val lock = java.lang.Object()
    private val entries = LinkedHashMap<String, Device>()

    init {
        load()
    }

    fun all(): List<Device> = synchronized(lock) { entries.values.sortedBy { it.name.lowercase() } }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    fun find(publicKey: ByteArray): Device? = synchronized(lock) { entries[key(publicKey)] }

    fun isTrusted(publicKey: ByteArray): Boolean = find(publicKey) != null

    fun remember(publicKey: ByteArray, name: String): Device = synchronized(lock) {
        val now = System.currentTimeMillis()
        val existing = entries[key(publicKey)]
        val device = Device(
            publicKey = publicKey.copyOf(),
            name = name.ifBlank { "device" }.take(64),
            addedAt = existing?.addedAt ?: now,
            lastSeen = now
        )
        entries[key(publicKey)] = device
        save()
        device
    }

    fun touch(publicKey: ByteArray) = synchronized(lock) {
        val existing = entries[key(publicKey)] ?: return
        entries[key(publicKey)] = existing.copy(lastSeen = System.currentTimeMillis())
        save()
    }

    fun rename(publicKey: ByteArray, name: String) = synchronized(lock) {
        val existing = entries[key(publicKey)] ?: return
        entries[key(publicKey)] = existing.copy(name = name.ifBlank { "device" }.take(64))
        save()
    }

    fun forget(publicKey: ByteArray): Boolean = synchronized(lock) {
        val removed = entries.remove(key(publicKey)) != null
        if (removed) save()
        removed
    }

    fun forgetAll() = synchronized(lock) {
        entries.clear()
        save()
    }

    private fun key(publicKey: ByteArray): String =
        Base64.getEncoder().encodeToString(publicKey)

    private fun load() {
        if (!file.isFile) return
        runCatching {
            file.readLines().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val parts = line.split('\t')
                if (parts.size < 4) return@forEach
                val publicKey = runCatching { Base64.getDecoder().decode(parts[0]) }.getOrNull()
                    ?: return@forEach
                if (publicKey.size != WssIdentity.KEY_SIZE) return@forEach
                entries[parts[0]] = Device(
                    publicKey = publicKey,
                    name = parts[3],
                    addedAt = parts[1].toLongOrNull() ?: 0L,
                    lastSeen = parts[2].toLongOrNull() ?: 0L
                )
            }
        }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val text = buildString {
                appendLine("# devices authorized for remote control")
                appendLine("# public key base64\tadded\tlast seen\tname")
                entries.forEach { (encoded, device) ->
                    appendLine("$encoded\t${device.addedAt}\t${device.lastSeen}\t${device.name}")
                }
            }
            file.writeText(text)
        }
    }
}

class PairingWindow(
    val pin: String = WssCrypto.randomDigits(8),
    val openedAt: Long = System.currentTimeMillis(),
    private val lifetimeMs: Long = 180_000,
    private val maxAttempts: Int = 3
) {

    private val lock = java.lang.Object()
    private var attempts = 0
    private var closed = false

    val display: String get() = pin.chunked(4).joinToString("-")

    val secondsLeft: Long
        get() = ((openedAt + lifetimeMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    val isOpen: Boolean
        get() = synchronized(lock) { !closed && secondsLeft > 0 && attempts < maxAttempts }

    val attemptsLeft: Int get() = synchronized(lock) { (maxAttempts - attempts).coerceAtLeast(0) }

    fun claim(): String = synchronized(lock) {
        if (!isOpen) throw WssProtocolException("pairing window closed")
        attempts++
        pin
    }

    fun close() = synchronized(lock) { closed = true }
}
