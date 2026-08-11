package com.cuscus.wifiscreenstreaming.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.launch
import com.cuscus.wifiscreenstreaming.ui.theme.WiFiScreenStreamingTheme

class HomeActions(
    val look: () -> Unit = {},
    val link: (Machine) -> Unit = {},
    val forget: (Machine) -> Unit = {},
    val manual: (Boolean) -> Unit = {},
    val manualGo: () -> Unit = {},
    val wantsInput: (Boolean) -> Unit = {},
    val controlOnly: (Boolean) -> Unit = {},
    val settings: (Boolean) -> Unit = {},
    val dynamicColour: (Boolean) -> Unit = {},
    val debug: (Boolean) -> Unit = {},
    val haptics: (Boolean) -> Unit = {}
)

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { TopRow(state, actions) }

            item { Hero(state) }

            item { ControlToggle(state, actions) }

            item {
                Radar(
                    scanning = state.looking || state.machines.none { it.online },
                    found = state.machines.count { it.online },
                    onManual = { actions.manual(true) }
                )
            }

            val here = state.machines.filter { it.online }
            val saved = state.machines.filter { !it.online }

            if (here.isNotEmpty()) {
                item { SectionTitle("Here now", "${here.size}") }
                itemsIndexed(here, key = { _, machine -> "here-${machine.address}" }) { index, machine ->
                    MachineCard(
                        machine = machine,
                        live = state.current?.address == machine.address && state.phase == Phase.Live,
                        index = index,
                        onLink = { actions.link(machine) },
                        onForget = { actions.forget(machine) }
                    )
                }
            }

            if (saved.isNotEmpty()) {
                item { SectionTitle("Saved") }
                itemsIndexed(saved, key = { _, machine -> "saved-${machine.address}" }) { index, machine ->
                    MachineCard(
                        machine = machine,
                        live = state.current?.address == machine.address && state.phase == Phase.Live,
                        index = index,
                        onLink = { actions.link(machine) },
                        onForget = { actions.forget(machine) }
                    )
                }
            }

            if (state.machines.isEmpty() && !state.looking) {
                item { Nothing(state, actions) }
            }
        }

    }

    if (state.manualOpen) ManualSheet(state, actions)
    if (state.settingsOpen) SettingsSheet(state, actions)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ControlToggle(state: HomeState, actions: HomeActions) {
    val touch = rememberAppHaptics()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton(
                checked = state.wantsInput,
                onCheckedChange = { on -> touch.toggle(on); actions.wantsInput(on) }
            ) {
                Icon(
                    imageVector = if (state.wantsInput) Icons.Filled.Mouse else Icons.Filled.Keyboard,
                    contentDescription = null
                )
                Text("  Control the PC")
            }

            AnimatedVisibility(visible = state.wantsInput) {
                ToggleButton(
                    checked = state.controlOnly,
                    onCheckedChange = { on -> touch.toggle(on); actions.controlOnly(on) }
                ) {
                    Icon(
                        imageVector = if (state.controlOnly) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = null
                    )
                    Text(if (state.controlOnly) "  No picture" else "  Picture")
                }
            }
        }

        AnimatedVisibility(visible = state.wantsInput && state.controlOnly) {
            Text(
                text = "The PC will not send video or sound: your screen becomes a touchpad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp)
            )
        }
    }
}

@Composable
private fun TopRow(state: HomeState, actions: HomeActions) {
    val touch = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (state.phase == Phase.Live) "WATCHING" else "SCREEN",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "WiFi Screen Streaming",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { touch.tap(); actions.settings(true) }) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(state: HomeState) {
    val breathing = rememberInfiniteTransition(label = "breathing")
    val drift by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state.busy) 1100 else 4200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )
    val turn by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state.busy) 6000 else 26000),
            repeatMode = RepeatMode.Restart
        ),
        label = "turn"
    )

    val target = when (state.phase) {
        Phase.Idle -> MaterialShapes.Circle
        Phase.Looking -> MaterialShapes.Cookie9Sided
        Phase.Linking -> MaterialShapes.Sunny
        Phase.Live -> MaterialShapes.Cookie12Sided
        Phase.Lost -> MaterialShapes.Diamond
    }

    var fromShape by remember { mutableStateOf(target) }
    var toShape by remember { mutableStateOf(target) }
    val settling = remember { Animatable(1f) }
    val squash = remember { Animatable(1f) }
    val flick = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val beat = rememberAppHaptics()

    suspend fun morphTo(next: RoundedPolygon, bouncy: Boolean) {
        if (next === toShape) return
        fromShape = toShape
        toShape = next
        settling.snapTo(0f)
        settling.animateTo(
            targetValue = 1f,
            animationSpec = if (bouncy) {
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            } else {
                tween(durationMillis = 520)
            }
        )
    }

    LaunchedEffect(target) { morphTo(target, bouncy = false) }

    val morph = remember(fromShape, toShape) { Morph(fromShape, toShape) }
    val settle = settling.value

    val tint = when (state.phase) {
        Phase.Live -> MaterialTheme.colorScheme.secondaryContainer
        Phase.Lost -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(196.dp)
                    .graphicsLayer {
                        val breath = 1f + drift * 0.08f
                        scaleX = breath
                        scaleY = breath
                        rotationZ = -turn * 0.45f
                        alpha = 0.16f
                    }
                    .background(
                        color = tint,
                        shape = MorphShape(morph, settle)
                    )
            )

            Box(
                modifier = Modifier
                    .size(132.dp)
                    .graphicsLayer {
                        val pulse = (0.95f + drift * 0.05f) * squash.value
                        scaleX = pulse
                        scaleY = pulse
                        rotationZ = turn + flick.value
                    }
                    .background(
                        color = tint,
                        shape = MorphShape(morph, settle)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        beat.longPress()
                        scope.launch { morphTo(playfulShape(toShape), bouncy = true) }
                        scope.launch {
                            squash.snapTo(0.86f)
                            squash.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioHighBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }
                        scope.launch {
                            flick.animateTo(
                                targetValue = flick.value + 72f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessVeryLow
                                )
                            )
                        }
                    }
            )

            if (state.busy) {
                LoadingIndicator(modifier = Modifier.size(52.dp))
            }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = state.headline,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = state.note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MachineCard(
    machine: Machine,
    live: Boolean,
    index: Int,
    onLink: () -> Unit,
    onForget: () -> Unit
) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(machine.address) {
        entrance.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)
        )
    }

    val touch = remember { MutableInteractionSource() }
    val pressed by touch.collectIsPressedAsState()
    val feel = rememberAppHaptics()
    LaunchedEffect(pressed) { if (pressed) feel.press() }
    val squeeze by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "squeeze"
    )
    val corner by animateFloatAsState(
        targetValue = if (pressed) 40f else 28f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "corner"
    )

    Surface(
        onClick = { feel.confirm(); onLink() },
        interactionSource = touch,
        shape = RoundedCornerShape(corner.dp),
        color = if (live) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = squeeze
                scaleY = squeeze
                alpha = entrance.value
                translationY = (1f - entrance.value) * 60f
            }
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Badge(machine, live)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = if (machine.name == machine.host) "PC" else machine.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = machine.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (machine.paired) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Text(
                        text = "paired",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            } else if (machine.remembered) {
                TextButton(onClick = { feel.reject(); onForget() }) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun Badge(machine: Machine, live: Boolean) {
    val shape = remember(machine.address) { signatureShape(machine.address) }
    val glow by animateFloatAsState(
        targetValue = if (live) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow),
        label = "glow"
    )

    Box(
        modifier = Modifier
            .size(58.dp)
            .graphicsLayer { rotationZ = glow * 20f }
            .background(
                color = if (live) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                shape = PolygonShape(shape)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = machine.name.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (live) {
                MaterialTheme.colorScheme.onSecondary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.graphicsLayer { rotationZ = -glow * 20f }
        )
    }
}

@Composable
private fun Nothing(state: HomeState, actions: HomeActions) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = if (state.looking) "Listening for a PC" else "No PC yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Open WiFi Screen Streaming on the computer and press Start. " +
                    "It will announce itself here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSheet(state: HomeState, actions: HomeActions) {
    val sheet = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { actions.manual(false) },
        sheetState = sheet,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Where is the PC?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "The address is written in the Link panel of the desktop app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.manualHost,
                    onValueChange = { state.manualHost = it },
                    label = { Text("Address") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.manualPort,
                    onValueChange = { text -> state.manualPort = text.filter { it.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.width(120.dp)
                )
            }

            Surface(
                onClick = actions.manualGo,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Connect",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HomePreview() {
    WiFiScreenStreamingTheme(dynamic = false) {
        HomeScreen(HomeState.demo(), HomeActions())
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HomeEmptyPreview() {
    WiFiScreenStreamingTheme(dynamic = false) {
        HomeScreen(HomeState(), HomeActions())
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun HomeLivePreview() {
    val state = HomeState.demo().apply {
        phase = Phase.Live
        current = machines.first()
        note = "60 fps, 5.6 Mbps"
    }
    WiFiScreenStreamingTheme(dynamic = false) {
        HomeScreen(state, HomeActions())
    }
}
