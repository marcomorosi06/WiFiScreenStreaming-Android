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
package com.cuscus.wifiscreenstreaming.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Phase { Idle, Looking, Linking, Live, Lost }

enum class Pointer { Tap, Trackpad }

data class Machine(
    val name: String,
    val host: String,
    val port: Int,
    val paired: Boolean = false,
    val remembered: Boolean = false,
    val online: Boolean = false
) {
    val address: String get() = "$host:$port"
}

class HomeState {

    val machines = mutableStateListOf<Machine>()

    var phase by mutableStateOf(Phase.Idle)
    var current by mutableStateOf<Machine?>(null)
    var note by mutableStateOf("Nothing connected")
    var booting by mutableStateOf(true)
    var settingsOpen by mutableStateOf(false)
    var dynamicColour by mutableStateOf(true)
    var debug by mutableStateOf(false)
    var haptics by mutableStateOf(true)
    var helpOpen by mutableStateOf(false)

    var pinAsk by mutableStateOf<PinAsk?>(null)
    var sasAsk by mutableStateOf<SasAsk?>(null)
    var trustAsk by mutableStateOf<TrustAsk?>(null)
    var stats by mutableStateOf<String?>(null)
    var manualOpen by mutableStateOf(false)
    var manualHost by mutableStateOf("")
    var manualPort by mutableStateOf("5000")
    var wantsInput by mutableStateOf(false)
    var controlOnly by mutableStateOf(false)

    var video by mutableStateOf<Pair<Int, Int>?>(null)
    var geometry by mutableStateOf<String?>(null)
    var audio by mutableStateOf<String?>(null)
    var inputLive by mutableStateOf(false)
    var pointer by mutableStateOf(Pointer.Tap)
    var keyboardOpen by mutableStateOf(false)
    var barAtTop by mutableStateOf(false)
    var barExpanded by mutableStateOf(false)
    var barX by mutableStateOf(0.04f)
    var barY by mutableStateOf(0.94f)
    var overlayOpen by mutableStateOf(false)

    val looking: Boolean get() = phase == Phase.Looking
    val busy: Boolean get() = phase == Phase.Looking || phase == Phase.Linking

    val headline: String
        get() = when (phase) {
            Phase.Idle -> "Ready when you are"
            Phase.Looking -> "Looking around"
            Phase.Linking -> "Reaching ${current?.name ?: "the PC"}"
            Phase.Live -> current?.name ?: "Streaming"
            Phase.Lost -> "Lost the PC"
        }

    companion object {

        fun demo(): HomeState = HomeState().apply {
            machines += Machine("Computer", "192.168.1.174", 5000, paired = true, remembered = true)
            machines += Machine("Studio", "192.168.1.42", 5000, remembered = true)
            machines += Machine("Laptop", "192.168.1.98", 5000)
            note = "3 machines, one paired"
        }
    }
}
