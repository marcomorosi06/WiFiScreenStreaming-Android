package com.cuscus.wifiscreenstreaming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.ArrayDeque

const val SERVICE_TYPE = "_wfss._tcp."

private const val REANNOUNCE_MS = 4000L
private const val RESTART_DELAY_MS = 350L

data class Found(
    val name: String,
    val host: String,
    val port: Int,
    val codec: String,
    val fps: String,
    val audioPort: Int
) {
    val summary: String
        get() = buildString {
            append(name)
            append("  ")
            append(host)
            append(':')
            append(port)
            if (codec.isNotEmpty() || fps.isNotEmpty()) {
                append("   ")
                append(codec.uppercase())
                if (fps.isNotEmpty()) append(" @ $fps")
            }
            if (audioPort > 0) append("   audio")
        }
}

class Discovery(
    context: Context,
    private val onFound: (Found) -> Unit,
    private val onLost: (String) -> Unit = {},
    private val onStatus: (String) -> Unit
) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val lock = Any()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private val publishedAt = HashMap<String, Long>()
    private val keyOfService = HashMap<String, String>()

    fun start() {
        if (listener != null) return
        synchronized(lock) {
            publishedAt.clear()
            keyOfService.clear()
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(type: String) {
                onStatus("searching...")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) {
                    queue.add(info)
                }
                pump()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                synchronized(lock) {
                    keyOfService.remove(name)?.let { publishedAt.remove(it) }
                }
                onLost(name)
            }

            override fun onDiscoveryStopped(type: String) {}

            override fun onStartDiscoveryFailed(type: String, code: Int) {
                onStatus("discovery did not start (code $code)")
                listener = null
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) {
                listener = null
            }
        }

        listener = discoveryListener
        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            listener = null
            onStatus("discovery unavailable: ${it.message}")
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val current = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(current) }
        synchronized(lock) { queue.clear() }
    }

    fun refresh() {
        val current = listener
        if (current == null) {
            start()
            return
        }
        listener = null
        runCatching { nsd.stopServiceDiscovery(current) }
        synchronized(lock) { queue.clear() }
        handler.postDelayed({ start() }, RESTART_DELAY_MS)
    }

    fun forget(address: String) {
        synchronized(lock) {
            publishedAt.remove(address)
            keyOfService.entries.removeAll { it.value == address }
        }
    }

    private fun pump() {
        val next: NsdServiceInfo?
        synchronized(lock) {
            if (resolving) return
            next = queue.poll()
            if (next == null) return
            resolving = true
        }

        nsd.resolveService(next, object : NsdManager.ResolveListener {

            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                synchronized(lock) { resolving = false }
                pump()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                synchronized(lock) { resolving = false }
                publish(info)
                pump()
            }
        })
    }

    private fun publish(info: NsdServiceInfo) {
        val address = info.host?.hostAddress ?: return
        val key = "$address:${info.port}"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val previous = publishedAt[key]
            if (previous != null && now - previous < REANNOUNCE_MS) return
            publishedAt[key] = now
            info.serviceName?.let { keyOfService[it] = key }
        }

        fun attribute(name: String): String =
            runCatching { info.attributes[name]?.toString(Charsets.UTF_8) ?: "" }.getOrDefault("")

        onFound(
            Found(
                name = attribute("name").ifEmpty { info.serviceName ?: "PC" },
                host = address,
                port = info.port,
                codec = attribute("codec"),
                fps = attribute("fps"),
                audioPort = attribute("audio").toIntOrNull() ?: 0
            )
        )
    }
}
