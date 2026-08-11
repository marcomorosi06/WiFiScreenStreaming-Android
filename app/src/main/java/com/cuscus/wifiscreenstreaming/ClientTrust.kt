package com.cuscus.wifiscreenstreaming

import android.content.Context
import java.io.File
import java.util.Base64

class ClientTrust(context: Context) {

    private val directory = File(context.filesDir, "wss").also { it.mkdirs() }
    private val servers = File(directory, "servers.tsv")

    val identity: WssIdentity = WssIdentity.loadOrCreate(File(directory, "identity.key"))

    data class KnownServer(
        val host: String,
        val publicKey: ByteArray,
        val name: String,
        val lastSeen: Long
    )

    private val entries = LinkedHashMap<String, KnownServer>()

    init {
        load()
    }

    fun all(): List<KnownServer> = entries.values.sortedByDescending { it.lastSeen }

    fun find(host: String): KnownServer? = entries[host]

    fun remember(host: String, publicKey: ByteArray, name: String) {
        entries[host] = KnownServer(host, publicKey.copyOf(), name, System.currentTimeMillis())
        save()
    }

    fun forget(host: String) {
        if (entries.remove(host) != null) save()
    }

    fun forgetAll() {
        entries.clear()
        save()
    }

    private fun load() {
        if (!servers.isFile) return
        runCatching {
            servers.readLines().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val parts = line.split('\t')
                if (parts.size < 4) return@forEach
                val key = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return@forEach
                if (key.size != WssIdentity.KEY_SIZE) return@forEach
                entries[parts[0]] = KnownServer(parts[0], key, parts[3], parts[2].toLongOrNull() ?: 0L)
            }
        }
    }

    private fun save() {
        runCatching {
            servers.writeText(
                buildString {
                    appendLine("# paired PCs")
                    appendLine("# host\tpublic key base64\tlast seen\tname")
                    entries.forEach { (host, server) ->
                        val encoded = Base64.getEncoder().encodeToString(server.publicKey)
                        appendLine("$host\t$encoded\t${server.lastSeen}\t${server.name}")
                    }
                }
            )
        }
    }
}
