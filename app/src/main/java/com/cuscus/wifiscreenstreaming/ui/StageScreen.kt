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

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cuscus.wifiscreenstreaming.KeySink

class StageActions(
    val leave: () -> Unit = {},
    val escape: () -> Unit = {},
    val toggleKeyboard: () -> Unit = {},
    val togglePointer: () -> Unit = {},
    val barMoved: (Float, Float) -> Unit = { _, _ -> },
    val surfaceReady: (SurfaceView) -> Unit = {},
    val padReady: (android.view.View) -> Unit = {},
    val surfaceChanged: (SurfaceHolder?) -> Unit = {},
    val sinkReady: (KeySink) -> Unit = {}
)

@Composable
fun StageScreen(state: HomeState, actions: StageActions) {
    BackHandler {
        if (state.overlayOpen) state.overlayOpen = false else actions.leave()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (state.controlOnly) {
            Touchpad(state, actions.padReady)
        } else {
            Screen(state, actions)
        }

        AndroidView(
            factory = { context -> KeySink(context).also(actions.sinkReady) },
            modifier = Modifier.size(1.dp)
        )

        BoxWithConstraints(modifier = Modifier.safeDrawingPadding()) {
            val room = with(LocalDensity.current) {
                IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
            }
            var barSize by remember { mutableStateOf(IntSize.Zero) }

            AnimatedVisibility(
                visible = state.overlayOpen,
                enter = fadeIn() + slideInVertically(
                    animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
                ) { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Readout(state, actions)
            }

            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically(
                    animationSpec = spring(dampingRatio = 0.55f)
                ) { it },
                exit = fadeOut(),
                modifier = Modifier
                    .offset {
                        val spanX = (room.width - barSize.width).coerceAtLeast(0)
                        val spanY = (room.height - barSize.height).coerceAtLeast(0)
                        IntOffset(
                            (state.barX * spanX).toInt(),
                            (state.barY * spanY).toInt()
                        )
                    }
                    .onSizeChanged { barSize = it }
            ) {
                Bar(state, actions, room, barSize)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Touchpad(state: HomeState, pad: (android.view.View) -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pad")
    val glow by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context -> android.view.View(context).also(pad) },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer { alpha = glow }
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = PolygonShape(MaterialShapes.Cookie9Sided)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp)
                )
            }

            Text(
                text = state.current?.name ?: "Driving the PC",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Drag to move the pointer, tap to click.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}

@Composable
private fun Screen(state: HomeState, actions: StageActions) {
    val size = state.video

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val fitted = if (size == null) {
            Modifier.fillMaxSize()
        } else {
            val scale = minOf(
                maxWidth.value / size.first,
                maxHeight.value / size.second
            )
            Modifier.size(
                width = (size.first * scale).dp,
                height = (size.second * scale).dp
            )
        }

        AndroidView(
            factory = { context ->
                SurfaceView(context).also { view ->
                    view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) =
                            actions.surfaceChanged(holder)

                        override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) =
                            actions.surfaceChanged(holder)

                        override fun surfaceDestroyed(holder: SurfaceHolder) =
                            actions.surfaceChanged(null)
                    })
                    actions.surfaceReady(view)
                }
            },
            modifier = fitted
        )
    }
}

@Composable
private fun Readout(state: HomeState, actions: StageActions) {
    val touch = rememberAppHaptics()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = state.current?.name ?: "Streaming",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = state.video?.let { "${it.first} x ${it.second}" } ?: state.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.audio?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.contains("no reply") || it.contains("not advertised") ||
                        it.contains("busy") || it.contains("unauthorized") ||
                        it.contains("incompatible")
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            }

            if (state.debug) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        state.stats?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.geometry?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Text(
                            text = state.note,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { touch.reject(); actions.leave() },
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("Disconnect") }

                TextButton(onClick = { touch.tap(); state.overlayOpen = false }) { Text("Close") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Bar(state: HomeState, actions: StageActions, room: IntSize, barSize: IntSize) {
    val touch = rememberAppHaptics()
    val open = state.barExpanded

    val lean by animateFloatAsState(
        targetValue = if (open) 1f else 0.55f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "lean"
    )

    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "turn"
    )

    HorizontalFloatingToolbar(
        expanded = open,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        modifier = Modifier.graphicsLayer { alpha = lean },
        leadingContent = if (!state.inputLive) null else {
            {
                IconButton(onClick = { touch.tap(); actions.toggleKeyboard() }) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Keyboard")
                }
                ToggleButton(
                    checked = state.pointer == Pointer.Trackpad,
                    onCheckedChange = { on -> touch.toggle(on); actions.togglePointer() }
                ) {
                    Icon(
                        imageVector = if (state.pointer == Pointer.Trackpad) {
                            Icons.Filled.Mouse
                        } else {
                            Icons.Filled.TouchApp
                        },
                        contentDescription = "Pointer"
                    )
                }
            }
        },
        trailingContent = {
            if (state.inputLive) {
                TextButton(onClick = { touch.press(); actions.escape() }) { Text("Esc") }
            }
            IconButton(onClick = { touch.tap(); state.overlayOpen = !state.overlayOpen }) {
                Icon(Icons.Filled.Info, contentDescription = "Details")
            }
            Icon(
                imageVector = Icons.Filled.DragIndicator,
                contentDescription = "Drag me",
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .pointerInput(room, barSize) {
                        detectDragGestures(
                            onDragStart = { touch.gestureStart() },
                            onDragEnd = {
                                touch.gestureEnd()
                                actions.barMoved(state.barX, state.barY)
                            }
                        ) { change, delta ->
                            change.consume()
                            val spanX = (room.width - barSize.width).coerceAtLeast(1)
                            val spanY = (room.height - barSize.height).coerceAtLeast(1)
                            state.barX = (state.barX + delta.x / spanX).coerceIn(0f, 1f)
                            state.barY = (state.barY + delta.y / spanY).coerceIn(0f, 1f)
                        }
                    }
            )
        }
    ) {
        IconButton(onClick = { touch.toggle(!state.barExpanded); state.barExpanded = !state.barExpanded }) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = "Controls",
                modifier = Modifier.graphicsLayer { rotationZ = turn }
            )
        }
    }
}
