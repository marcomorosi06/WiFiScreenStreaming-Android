package com.cuscus.wifiscreenstreaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cuscus.wifiscreenstreaming.ui.LocalHapticsEnabled
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cuscus.wifiscreenstreaming.ui.HomeActions
import com.cuscus.wifiscreenstreaming.ui.HomeScreen
import com.cuscus.wifiscreenstreaming.ui.HomeState
import com.cuscus.wifiscreenstreaming.ui.Machine
import com.cuscus.wifiscreenstreaming.ui.Phase
import com.cuscus.wifiscreenstreaming.ui.PinDialog
import com.cuscus.wifiscreenstreaming.ui.SasDialog
import com.cuscus.wifiscreenstreaming.ui.StageActions
import com.cuscus.wifiscreenstreaming.ui.TrustDialog
import com.cuscus.wifiscreenstreaming.ui.StageScreen
import com.cuscus.wifiscreenstreaming.ui.theme.WiFiScreenStreamingTheme
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private const val MAGIC = 0x57535333

private const val FRAME_CONFIG = 1
private const val FRAME_KEY = 2
private const val MAX_FRAME = 32 shl 20

private const val REDISCOVER_MS = 12000L
private const val STALE_MS = 30000L
private const val SEARCH_WINDOW_MS = 4000L

private class SurfaceLost : Exception("surface recreated")

class MainActivity : ComponentActivity() {

    private val state = HomeState()
    private var surfaceView: SurfaceView? = null

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var socket: Socket? = null
    private var audio: WfasClient? = null
    private var discovery: Discovery? = null
    private var trust: ClientTrust? = null
    private var remote: RemoteControl? = null

    private val seen = HashMap<String, String>()
    private val lastSighted = HashMap<String, Long>()

    private val sweeper = android.os.Handler(android.os.Looper.getMainLooper())

    private val sweep = object : Runnable {
        override fun run() {
            if (state.phase != Phase.Live && state.phase != Phase.Linking) {
                val now = System.currentTimeMillis()
                for (i in state.machines.indices) {
                    val machine = state.machines[i]
                    if (!machine.online) continue
                    val at = lastSighted[machine.address] ?: 0L
                    if (now - at > STALE_MS) state.machines[i] = machine.copy(online = false)
                }
                discovery?.refresh()
            }
            sweeper.postDelayed(this, REDISCOVER_MS)
        }
    }

    private val surfaceLock = java.lang.Object()
    private var surface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        val opened = android.os.SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splash.setKeepOnScreenCondition {
                android.os.SystemClock.uptimeMillis() - opened < 700
            }
        }
        splash.setOnExitAnimationListener { provider ->
            val done = { runCatching { provider.remove() }.let { } }
            val root = runCatching { provider.view }.getOrNull()
            if (root == null) {
                done()
            } else {
                runCatching {
                    android.animation.ObjectAnimator.ofFloat(root, android.view.View.ALPHA, 1f, 0f)
                        .apply {
                            duration = 320
                            interpolator = android.view.animation.AccelerateInterpolator(1.6f)
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) = done()
                                override fun onAnimationCancel(animation: android.animation.Animator) = done()
                            })
                            start()
                        }
                }.onFailure { done() }
            }
        }

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        goFullScreen()

        trust = ClientTrust(this)
        remote = RemoteControl(this, trust!!, state) { message -> status(message) }

        loadSaved()
        state.wantsInput = inputWanted()
        state.dynamicColour = preferences().getBoolean("dynamicColour", true)
        state.debug = preferences().getBoolean("debug", false)
        state.debug = preferences().getBoolean("debug", false)
        state.controlOnly = preferences().getBoolean("controlOnly", false)
        state.haptics = preferences().getBoolean("haptics", true)
        state.barX = preferences().getFloat("barX", 0.04f)
        state.barY = preferences().getFloat("barY", 0.94f)
        setContent {
            WiFiScreenStreamingTheme(dynamic = state.dynamicColour) {
                CompositionLocalProvider(LocalHapticsEnabled provides state.haptics) {
                AnimatedContent(
                    targetState = state.phase == Phase.Live,
                    transitionSpec = {
                        (fadeIn() + scaleIn(
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
                            initialScale = 0.92f
                        )) togetherWith (fadeOut() + scaleOut(targetScale = 1.04f))
                    },
                    label = "shell"
                ) { live ->
                    if (live) StageScreen(state, stage()) else HomeScreen(state, home())
                }


                state.pinAsk?.let { ask ->
                    PinDialog(ask) { state.pinAsk = null }
                }
                state.sasAsk?.let { ask ->
                    SasDialog(ask) { state.sasAsk = null }
                }
                state.trustAsk?.let { ask ->
                    TrustDialog(
                        ask = ask,
                        onForget = {
                            remote?.forget(ask.host)
                            state.machines.removeAll { it.host == ask.host }
                            state.trustAsk = null
                        },
                        onKeep = { state.trustAsk = null }
                    )
                }
                }
            }
        }
    }

    private fun home(): HomeActions = HomeActions(
        look = { watchNetwork() },
        link = { machine -> start(machine) },
        forget = { machine -> drop(machine) },
        manual = { open -> state.manualOpen = open },
        manualGo = {
            val host = state.manualHost.trim()
            if (host.isNotEmpty()) {
                val port = state.manualPort.toIntOrNull() ?: 5000
                val machine = Machine(host, host, port)
                if (state.machines.none { it.address == machine.address }) state.machines.add(machine)
                state.manualOpen = false
                start(machine)
            }
        },
        wantsInput = { wanted ->
            state.wantsInput = wanted
            preferences().edit().putBoolean("input", wanted).apply()
            if (!wanted) {
                state.controlOnly = false
                remote?.detach()
            }
        },
        controlOnly = { only ->
            state.controlOnly = only
            preferences().edit().putBoolean("controlOnly", only).apply()
        },
        settings = { open -> state.settingsOpen = open },
        dynamicColour = { on ->
            state.dynamicColour = on
            preferences().edit().putBoolean("dynamicColour", on).apply()
        },
        debug = { on ->
            state.debug = on
            preferences().edit().putBoolean("debug", on).apply()
        },
        haptics = { on ->
            state.haptics = on
            preferences().edit().putBoolean("haptics", on).apply()
        }
    )

    private fun stage(): StageActions = StageActions(
        leave = { stop() },
        escape = { remote?.escape() },
        toggleKeyboard = { remote?.toggleKeyboard() },
        togglePointer = { remote?.togglePointer() },
        barMoved = { x, y ->
            preferences().edit()
                .putFloat("barX", x)
                .putFloat("barY", y)
                .apply()
        },
        surfaceReady = { view -> adopt(view) },
        padReady = { view -> adopt(view) },
        surfaceChanged = { holder -> publish(holder?.surface) },
        sinkReady = { view -> remote?.useSink(view) }
    )

    private fun adopt(view: View) {
        if (view is SurfaceView) surfaceView = view
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.defaultFocusHighlightEnabled = false

        view.setOnGenericMotionListener { target, event ->
            val control = remote
            control != null && control.active && control.onGeneric(target, event)
        }

        view.setOnTouchListener { target, event ->
            val control = remote
            if (control != null && control.active) {
                control.onTouch(target, event)
            } else {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (control != null && control.expected) control.explainDead()
                    target.performClick()
                }
                true
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val control = remote
        if (control != null && control.active && !state.overlayOpen) {
            if (control.onPhysicalKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val control = remote
        val view = surfaceView
        if (control != null && view != null && control.active && PhysicalInput.fromMouse(event)) {
            val copy = MotionEvent.obtain(event)
            try {
                copy.offsetLocation(-view.left.toFloat(), -view.top.toFloat())
                if (control.onGeneric(view, copy)) return true
            } finally {
                copy.recycle()
            }
        }
        return super.onGenericMotionEvent(event)
    }

    fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val dark = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (state.phase == Phase.Live) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!running.get()) {
            runCatching { watchNetwork() }.onFailure {
                state.phase = Phase.Idle
                state.note = "cannot look around: ${it.javaClass.simpleName}"
            }
        }
    }

    override fun onStop() {
        super.onStop()
        restNetwork()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    private fun preferences() = getSharedPreferences("wss", MODE_PRIVATE)

    private fun inputWanted(): Boolean = preferences().getBoolean("input", false)

    override fun onDestroy() {
        stop()
        sweeper.removeCallbacksAndMessages(null)
        runCatching { discovery?.stop() }
        discovery = null
        super.onDestroy()
    }

    private fun watchNetwork() {
        sweeper.removeCallbacks(sweep)
        sweeper.postDelayed(sweep, REDISCOVER_MS)

        if (discovery != null) return

        val instance = Discovery(
            this,
            onFound = { server -> runOnUiThread { sighted(server) } },
            onLost = { name -> runOnUiThread { vanished(name) } },
            onStatus = { message -> runOnUiThread { status(message) } }
        )
        discovery = instance
        runCatching { instance.start() }
    }

    private fun restNetwork() {
        sweeper.removeCallbacks(sweep)
        runCatching { discovery?.stop() }
        discovery = null
        for (i in state.machines.indices) {
            val machine = state.machines[i]
            if (machine.online) state.machines[i] = machine.copy(online = false)
        }
        if (state.phase == Phase.Looking) state.phase = Phase.Idle
    }

    private fun loadSaved() {
        val stored = preferences().getStringSet("saved", emptySet()) ?: emptySet()
        stored.sorted().forEach { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@forEach
            val port = parts[1].toIntOrNull() ?: return@forEach
            val host = parts[0]
            state.machines.add(
                Machine(
                    name = parts[2].ifBlank { host },
                    host = host,
                    port = port,
                    paired = trust?.find(host) != null,
                    remembered = true
                )
            )
        }
    }

    private fun keep(machine: Machine) {
        val stored = preferences().getStringSet("saved", emptySet()) ?: emptySet()
        val without = stored.filterNot { it.startsWith("${machine.host}|${machine.port}|") }
        val line = "${machine.host}|${machine.port}|${machine.name}"
        preferences().edit().putStringSet("saved", (without + line).toSet()).apply()

        val index = state.machines.indexOfFirst { it.address == machine.address }
        if (index >= 0) state.machines[index] = state.machines[index].copy(remembered = true)
    }

    private fun drop(machine: Machine) {
        val stored = preferences().getStringSet("saved", emptySet()) ?: emptySet()
        val without = stored.filterNot { it.startsWith("${machine.host}|${machine.port}|") }
        preferences().edit().putStringSet("saved", without.toSet()).apply()

        trust?.forget(machine.host)
        state.machines.removeAll { it.address == machine.address }
        seen.entries.removeAll { it.value == machine.address }
        lastSighted.remove(machine.address)

        discovery?.forget(machine.address)
        discovery?.refresh()
    }

    private fun vanished(name: String) {
        val address = seen.remove(name) ?: return
        lastSighted.remove(address)
        val index = state.machines.indexOfFirst { it.address == address }
        if (index < 0) return
        val machine = state.machines[index]
        if (machine.remembered) {
            state.machines[index] = machine.copy(online = false)
        } else {
            state.machines.removeAt(index)
        }
    }

    private fun sighted(server: Found) {
        seen[server.name] = "${server.host}:${server.port}"
        lastSighted["${server.host}:${server.port}"] = System.currentTimeMillis()
        val fresh = Machine(
            name = server.name,
            host = server.host,
            port = server.port,
            paired = trust?.find(server.host) != null,
            online = true
        )
        val known = state.machines.indexOfFirst { it.address == fresh.address }
        if (known >= 0) {
            state.machines[known] = fresh.copy(remembered = state.machines[known].remembered)
        } else {
            state.machines.add(fresh)
        }
        if (state.phase == Phase.Looking) state.phase = Phase.Idle
    }

    private fun search() {
        state.phase = Phase.Looking
        status("searching...")

        val startedAt = System.currentTimeMillis()
        watchNetwork()
        discovery?.refresh()

        sweeper.postDelayed({
            if (state.phase == Phase.Looking) state.phase = Phase.Idle

            for (i in state.machines.indices) {
                val machine = state.machines[i]
                if (!machine.online) continue
                if ((lastSighted[machine.address] ?: 0L) < startedAt) {
                    state.machines[i] = machine.copy(online = false)
                }
            }

            val live = state.machines.count { it.online }
            status(
                if (live == 0) {
                    "nothing answered. Open WiFi Screen Streaming on the PC and press Start."
                } else {
                    "$live answered"
                }
            )
        }, SEARCH_WINDOW_MS)
    }

    private fun publish(value: Surface?) {
        synchronized(surfaceLock) {
            surface = value
            surfaceLock.notifyAll()
        }
    }

    private fun liveSurface(): Surface? = synchronized(surfaceLock) {
        surface?.takeIf { it.isValid }
    }

    private fun awaitSurface(timeoutMs: Long): Surface? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(surfaceLock) {
            while (running.get()) {
                surface?.let { if (it.isValid) return it }
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                surfaceLock.wait(left)
            }
        }
        return null
    }

    private fun onUiThreadBlocking(action: () -> Unit) {
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await() }
    }

    private fun preferHighRefresh(): String {
        @Suppress("DEPRECATION")
        val screen = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        }.getOrNull() ?: return ""

        val current = screen.mode ?: return ""
        val best = screen.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate } ?: return ""

        if (best.refreshRate <= current.refreshRate + 0.5f) return ""

        return runCatching {
            val attributes = window.attributes
            attributes.preferredDisplayModeId = best.modeId
            window.attributes = attributes
            "   screen %.0f Hz".format(best.refreshRate)
        }.getOrDefault("")
    }

    private fun announceFrameRate(target: Surface, fps: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                target.setFrameRate(
                    fps.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } else {
                target.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }

    private fun status(text: String) = runOnUiThread { state.note = text }

    private fun audioStatus(text: String) = runOnUiThread { state.audio = text.ifBlank { null } }

    private fun start(machine: Machine) {
        if (running.get()) {
            stop()
            return
        }
        val host = machine.host
        val port = machine.port

        keep(machine)
        restNetwork()
        running.set(true)
        state.current = machine
        state.phase = Phase.Linking
        status("connecting to $host:$port")

        worker = Thread { sessions(host, port) }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun startRemoteControl(socket: Socket, stream: InputStream, host: String) {
        val store = trust
        val control = remote
        if (store == null || control == null || !inputWanted()) {
            WssHandshake.decline(socket)
            status("remote control off: view only")
            return
        }

        val known = store.find(host)
        val pin = if (known == null) control.askPin() else null
        if (known == null && pin == null) {
            WssHandshake.decline(socket)
            status("pairing cancelled: view only")
            return
        }

        val session = try {
            WssHandshake.connect(
                socket,
                store.identity,
                known?.publicKey,
                pin,
                android.os.Build.MODEL ?: "Phone",
                stream
            ) { sas -> control.confirmSas(sas) }
        } catch (e: Exception) {
            status("remote control not active: ${e.message}")
            if (known != null) control.offerForget(host, e.message)
            throw e
        }

        control.attach(session, host)
        status("remote control active on ${session.peerName}")
    }

    private fun stop() {
        running.set(false)
        runCatching { remote?.detach() }
        runCatching { audio?.stop() }
        audio = null
        runCatching { socket?.close() }
        socket = null
        worker = null
        synchronized(surfaceLock) { surfaceLock.notifyAll() }
        audioStatus("")
        runOnUiThread {
            state.phase = Phase.Idle
            state.overlayOpen = false
            state.video = null
            state.current = null
            goFullScreen()
            runCatching { watchNetwork() }
        }
        status("stopped")
    }

    private fun sessions(host: String, port: Int) {
        var restarts = 0
        while (running.get()) {
            val lost = try {
                session(host, port)
            } catch (e: MediaCodec.CodecException) {
                if (running.get()) status(describe(e))
                false
            } catch (e: java.net.ConnectException) {
                if (running.get()) status(unreachable(host, port))
                false
            } catch (e: java.net.SocketTimeoutException) {
                if (running.get()) status(unreachable(host, port))
                false
            } catch (e: java.net.NoRouteToHostException) {
                if (running.get()) status(unreachable(host, port))
                false
            } catch (e: Exception) {
                if (running.get()) status("error: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (!lost || !running.get()) break
            restarts++
            status("surface recreated, reconnecting ($restarts)")
            Thread.sleep(400)
        }
        if (running.get()) {
            running.set(false)
            runOnUiThread {
                state.phase = Phase.Lost
                state.overlayOpen = false
                state.video = null
                goFullScreen()
            }
        }
    }

    private fun unreachable(host: String, port: Int): String =
        "cannot reach $host:$port. On the PC open WiFi Screen Streaming and press Start, " +
            "then check the address shown in the Link panel."

    private fun session(host: String, port: Int): Boolean {
        var codec: MediaCodec? = null
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)
            socket = s

            val input = DataInputStream(s.getInputStream().buffered(1 shl 16))
            val magic = input.readInt()
            if (magic == 0x57535332) {
                throw IllegalStateException("the PC speaks the old stream format: update the desktop program")
            }
            if (magic != MAGIC) throw IllegalStateException("invalid header: 0x${magic.toString(16)}")
            val width = input.readInt()
            val height = input.readInt()
            val fps = input.readInt().coerceAtLeast(1)
            val hevc = input.readInt() == 1
            val audioPort = input.readInt()
            val audioRate = input.readInt()
            val audioChannels = input.readInt()
            val inputEnabled = input.readInt() == 1
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

            val blind = state.controlOnly || width <= 0 || height <= 0

            if (blind) {
                onUiThreadBlocking {
                    state.controlOnly = true
                    state.video = null
                    state.geometry = null
                    state.phase = Phase.Live
                    state.overlayOpen = false
                    goFullScreen()
                }

                if (inputEnabled) {
                    startRemoteControl(s, input, host)
                    remote?.askForVideo(false)
                    status("driving only: no picture, no sound")
                } else {
                    status("the PC is not sending a picture and control is off")
                }

                while (running.get()) Thread.sleep(200)
                return false
            }

            var note = ""
            onUiThreadBlocking {
                state.video = width to height
                state.geometry = geometryOf(width, height)
                state.phase = Phase.Live
                state.overlayOpen = false
                goFullScreen()
                surfaceView?.holder?.setFixedSize(width, height)
                note = preferHighRefresh()
            }
            status("${width}x$height @ $fps  ${if (hevc) "H.265" else "H.264"}$note")
            Thread.sleep(200)

            val target = awaitSurface(5000) ?: run {
                if (!running.get()) return false
                throw IllegalStateException("surface not ready")
            }
            announceFrameRate(target, fps)

            if (inputEnabled) startRemoteControl(s, input, host)

            if (audioPort > 0) {
                if (audio?.isRunning != true) {
                    runCatching { audio?.stop() }
                    audioStatus("audio: WFAS v2 on $audioPort, $audioRate Hz, $audioChannels ch")
                    audio = WfasClient(host, audioPort, audioRate, audioChannels) { audioStatus(it) }
                        .also { it.start() }
                }
            } else {
                runCatching { audio?.stop() }
                audio = null
                audioStatus("audio: not advertised by the server")
            }

            val reader = FrameReader(input)
            val held = ArrayList<Au>()
            val csd = collectCsd(reader, hevc, held)

            codec = openDecoder(mime, width, height, fps, csd, target)

            decodeLoop(codec, reader, held, fps, target)
            return false
        } catch (e: SurfaceLost) {
            return true
        } catch (e: IllegalStateException) {
            if (running.get() && liveSurface() == null) return true
            throw e
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { audio?.stop() }
            audio = null
            runCatching { socket?.close() }
            socket = null
        }
    }

    private fun describe(e: MediaCodec.CodecException): String {
        val kind = when {
            e.isTransient -> "transient"
            e.isRecoverable -> "recoverable"
            else -> "fatal"
        }
        val detail = e.diagnosticInfo.ifBlank { e.message ?: "" }
        return "decoder: $kind error ${e.errorCode}  $detail"
    }

    private fun collectCsd(reader: FrameReader, hevc: Boolean, held: MutableList<Au>): Csd {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val deadline = System.currentTimeMillis() + 5000

        val vpsType = if (hevc) 32 else -1
        val spsType = if (hevc) 33 else 7
        val ppsType = if (hevc) 34 else 8

        while (running.get() && System.currentTimeMillis() < deadline) {
            val unit = reader.next() ?: break
            held.add(unit)
            for (nal in nalsOf(unit.bytes)) {
                when (nalType(nal, hevc)) {
                    vpsType -> vps = nal
                    spsType -> sps = nal
                    ppsType -> pps = nal
                }
            }
            if (sps != null && pps != null && (!hevc || vps != null)) break
        }

        if (sps == null || pps == null) throw IllegalStateException("codec parameter sets not received")
        return Csd(vps, sps, pps)
    }

    private fun openDecoder(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        csd: Csd,
        target: Surface
    ): MediaCodec {
        var lastError: Exception? = null

        for (lowLatency in listOf(true, false)) {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            csd.apply(format)
            if (lowLatency) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, target, null, 0)
                codec.start()
                if (!lowLatency) status("decoder started without low latency")
                return codec
            } catch (e: Exception) {
                lastError = e
                runCatching { codec?.release() }
                if (lowLatency) status("low latency refused by the decoder, retrying")
            }
        }
        throw lastError ?: IllegalStateException("decoder did not start")
    }

    private fun decodeLoop(
        codec: MediaCodec,
        reader: FrameReader,
        held: List<Au>,
        fps: Int,
        target: Surface
    ) {
        val step = 1_000_000L / fps
        val meter = Meter(fps * 5)
        val pipeline = Pipeline()
        val alive = AtomicBoolean(true)
        var pts = 0L

        val renderer = Thread { renderLoop(codec, meter, pipeline, alive) }
        renderer.isDaemon = true
        renderer.name = "render"
        renderer.priority = Thread.MAX_PRIORITY
        renderer.start()

        try {
            for (unit in held) {
                if (!feedUnit(codec, pipeline, target, unit, pts)) return
                if (unit.flags and FRAME_CONFIG == 0) pts += step
            }

            while (running.get() && alive.get()) {
                if (!target.isValid) throw SurfaceLost()

                val unit = reader.next() ?: break
                if (!feedUnit(codec, pipeline, target, unit, pts)) break
                if (unit.flags and FRAME_CONFIG == 0) pts += step
            }
        } finally {
            alive.set(false)
            runCatching { renderer.join(500) }
        }
    }

    private fun feedUnit(
        codec: MediaCodec,
        pipeline: Pipeline,
        target: Surface,
        unit: Au,
        pts: Long
    ): Boolean {
        val config = unit.flags and FRAME_CONFIG != 0
        val codecFlags = when {
            config -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            unit.flags and FRAME_KEY != 0 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }
        return feed(codec, pipeline, target, unit.bytes, pts, codecFlags, !config)
    }

    private fun feed(
        codec: MediaCodec,
        pipeline: Pipeline,
        target: Surface,
        payload: ByteArray,
        pts: Long,
        codecFlags: Int,
        track: Boolean
    ): Boolean {
        var index = -1
        while (running.get() && index < 0) {
            if (!target.isValid) throw SurfaceLost()
            index = try {
                codec.dequeueInputBuffer(10_000)
            } catch (e: IllegalStateException) {
                throw SurfaceLost()
            }
        }
        if (index < 0) return false

        try {
            val buffer = codec.getInputBuffer(index) ?: return false
            buffer.clear()
            buffer.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, pts, codecFlags)
        } catch (e: IllegalStateException) {
            throw SurfaceLost()
        }

        if (track) pipeline.queued(pts)
        return true
    }

    private fun renderLoop(
        codec: MediaCodec,
        meter: Meter,
        pipeline: Pipeline,
        alive: AtomicBoolean
    ) {
        val info = MediaCodec.BufferInfo()

        while (running.get() && alive.get()) {
            var index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                alive.set(false)
                return
            }
            if (index < 0) continue
            var pts = info.presentationTimeUs

            while (true) {
                val newer = try {
                    codec.dequeueOutputBuffer(info, 0)
                } catch (e: Exception) {
                    alive.set(false)
                    return
                }
                if (newer < 0) break
                runCatching { codec.releaseOutputBuffer(index, false) }
                pipeline.rendered(pts)
                meter.dropped++
                index = newer
                pts = info.presentationTimeUs
            }

            runCatching { codec.releaseOutputBuffer(index, true) }
            meter.rendered(pipeline.rendered(pts))?.let { line ->
                runOnUiThread { state.stats = line }
            }
        }
    }

    private fun geometryOf(videoWidth: Int, videoHeight: Int): String? {
        val view = surfaceView ?: return null
        val parent = view.parent as? View ?: return null
        if (parent.width == 0 || parent.height == 0 || videoWidth == 0 || videoHeight == 0) return null

        val scale = minOf(
            parent.width.toFloat() / videoWidth,
            parent.height.toFloat() / videoHeight
        )
        val shownWidth = (videoWidth * scale).toInt()
        val shownHeight = (videoHeight * scale).toInt()
        val warning = if (scale < 0.8f) "  <- text unreadable at this size, rotate the phone" else ""

        return "%dx%d -> %dx%d on screen (%.0f%%)%s"
            .format(videoWidth, videoHeight, shownWidth, shownHeight, scale * 100, warning)
    }
}

private class Csd(
    val vps: ByteArray?,
    val sps: ByteArray,
    val pps: ByteArray
) {

    fun apply(format: MediaFormat) {
        if (vps != null) {
            val all = java.io.ByteArrayOutputStream()
            all.write(vps)
            all.write(sps)
            all.write(pps)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(all.toByteArray()))
        } else {
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
        }
    }
}

private class Pipeline(private val size: Int = 128) {

    private val marks = LongArray(size)
    private val times = LongArray(size)
    private var next = 0

    @Synchronized
    fun queued(pts: Long) {
        val slot = next % size
        marks[slot] = pts
        times[slot] = System.nanoTime()
        next++
    }

    @Synchronized
    fun rendered(pts: Long): Double? {
        for (slot in 0 until size) {
            if (times[slot] != 0L && marks[slot] == pts) {
                val elapsed = (System.nanoTime() - times[slot]) / 1e6
                times[slot] = 0
                return elapsed
            }
        }
        return null
    }
}

private class Track(private val window: Int) {

    private val values = DoubleArray(window)
    private var count = 0

    fun add(value: Double) {
        if (count < window) values[count] = value
        count++
    }

    fun ready(): Boolean = count >= window

    fun take(): Triple<Double, Double, Double> {
        val n = if (count == 0) 1 else minOf(count, window)
        val sorted = values.copyOf(n).sortedArray()
        val result = Triple(sorted.average(), sorted[((n - 1) * 95) / 100], sorted[n - 1])
        count = 0
        return result
    }
}

private class Meter(private val window: Int) {

    private val cadence = Track(window)
    private val decode = Track(window)
    private var total = 0L
    private var last = 0L

    var dropped = 0L

    fun rendered(decodeMs: Double?): String? {
        val now = System.nanoTime()
        if (last != 0L) {
            cadence.add((now - last) / 1e6)
            total++
        }
        last = now
        decodeMs?.let { decode.add(it) }

        if (!cadence.ready()) return null
        val (cAvg, cP95, cMax) = cadence.take()
        val (dAvg, dP95, dMax) = decode.take()
        return "%.0f fps   %d frames   %d late dropped\npacing  %5.1f %5.1f %5.1f ms\ndecoder %5.1f %5.1f %5.1f ms"
            .format(if (cAvg > 0) 1000.0 / cAvg else 0.0, total, dropped, cAvg, cP95, cMax, dAvg, dP95, dMax)
    }
}

private fun payloadStart(nal: ByteArray): Int =
    if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) 4 else 3

private fun nalType(nal: ByteArray, hevc: Boolean): Int {
    val start = payloadStart(nal)
    if (start >= nal.size) return -1
    val b = nal[start].toInt() and 0xFF
    return if (hevc) (b shr 1) and 0x3F else b and 0x1F
}

private class Au(val bytes: ByteArray, val flags: Int)

private class FrameReader(private val input: DataInputStream) {

    fun next(): Au? {
        val len = try {
            input.readInt()
        } catch (e: java.io.EOFException) {
            return null
        }
        val flags = input.readInt()
        if (len <= 0 || len > MAX_FRAME) throw IllegalStateException("frame of $len bytes, the stream is out of step")
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return Au(bytes, flags)
    }
}

private fun nalsOf(bytes: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var start = -1
    var i = 0
    while (i + 2 < bytes.size) {
        val three = bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1
        val four = i + 3 < bytes.size && bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 &&
            bytes[i + 2].toInt() == 0 && bytes[i + 3].toInt() == 1
        if (three || four) {
            if (start >= 0) out.add(bytes.copyOfRange(start, i))
            start = i
            i += if (four) 4 else 3
            continue
        }
        i++
    }
    if (start >= 0) out.add(bytes.copyOfRange(start, bytes.size))
    return out
}
