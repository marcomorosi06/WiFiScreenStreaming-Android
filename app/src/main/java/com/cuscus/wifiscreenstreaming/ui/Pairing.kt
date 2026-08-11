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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Sheet(
    badge: androidx.graphics.shapes.RoundedPolygon,
    tint: androidx.compose.ui.graphics.Color,
    onBadge: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String,
    onDismiss: () -> Unit,
    body: @Composable () -> Unit
) {
    val spinner = rememberInfiniteTransition(label = "badge")
    val turn by spinner.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "turn"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer { rotationZ = turn }
                        .background(color = tint, shape = PolygonShape(badge)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = -turn }
                            .background(color = onBadge, shape = PolygonShape(MaterialShapes.Circle))
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                body()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PinDialog(ask: PinAsk, onClosed: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val touch = rememberAppHaptics()

    Sheet(
        badge = MaterialShapes.Cookie9Sided,
        tint = MaterialTheme.colorScheme.primaryContainer,
        onBadge = MaterialTheme.colorScheme.primary,
        title = "First handshake",
        detail = "On the PC press \"Pair device\". It shows eight digits: type them here.",
        onDismiss = {
            ask.finish(null)
            onClosed()
        }
    ) {
        OutlinedTextField(
            value = pin,
            onValueChange = { text -> pin = text.filter { it.isDigit() }.take(8) },
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                letterSpacing = 6.sp
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(
                onClick = {
                    ask.finish(null)
                    onClosed()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Not now") }

            Button(
                onClick = {
                    touch.confirm()
                    ask.finish(pin)
                    onClosed()
                },
                enabled = pin.length == 8,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Continue") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SasDialog(ask: SasAsk, onClosed: () -> Unit) {
    val touch = rememberAppHaptics()

    Sheet(
        badge = MaterialShapes.Clover4Leaf,
        tint = MaterialTheme.colorScheme.secondaryContainer,
        onBadge = MaterialTheme.colorScheme.secondary,
        title = "Same number?",
        detail = "The PC is showing a code too. If the two differ, someone is sitting in the middle: refuse.",
        onDismiss = {
            ask.finish(false)
            onClosed()
        }
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = ask.code.chunked(3).joinToString("  "),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(
                onClick = {
                    touch.reject()
                    ask.finish(false)
                    onClosed()
                },
                modifier = Modifier.weight(1f)
            ) { Text("They differ") }

            Button(
                onClick = {
                    touch.confirm()
                    ask.finish(true)
                    onClosed()
                },
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f)
            ) { Text("They match") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrustDialog(ask: TrustAsk, onForget: () -> Unit, onKeep: () -> Unit) {
    val touch = rememberAppHaptics()

    Sheet(
        badge = MaterialShapes.Burst,
        tint = MaterialTheme.colorScheme.errorContainer,
        onBadge = MaterialTheme.colorScheme.error,
        title = "This is not the same PC",
        detail = "${ask.host} failed the identity check" +
            (if (ask.reason.isNullOrBlank()) "" else " (${ask.reason})") +
            ". It may be a reinstalled PC, or someone pretending to be it. If you were not expecting this, keep it.",
        onDismiss = onKeep
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(onClick = onKeep, modifier = Modifier.weight(1f)) { Text("Keep it") }
            Button(
                onClick = { touch.reject(); onForget() },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.weight(1f)
            ) { Text("Forget") }
        }
    }
}
