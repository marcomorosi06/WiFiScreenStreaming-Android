package com.cuscus.wifiscreenstreaming.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PinAsk {

    private val latch = CountDownLatch(1)

    @Volatile
    private var value: String? = null

    fun finish(pin: String?) {
        value = pin
        latch.countDown()
    }

    fun await(seconds: Long): String? {
        runCatching { latch.await(seconds, TimeUnit.SECONDS) }
        return value?.takeIf { it.length == 8 }
    }
}

class SasAsk(val code: String) {

    private val latch = CountDownLatch(1)

    @Volatile
    private var value = false

    fun finish(matches: Boolean) {
        value = matches
        latch.countDown()
    }

    fun await(seconds: Long): Boolean {
        runCatching { latch.await(seconds, TimeUnit.SECONDS) }
        return value
    }
}

class TrustAsk(val host: String, val reason: String?)
