package com.cuscus.wifiscreenstreaming.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.cuscus.wifiscreenstreaming.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Splash(onDone: () -> Unit) {
    val innerTurn = remember { Animatable(-140f) }
    val outerTurn = remember { Animatable(140f) }
    val innerScale = remember { Animatable(0.2f) }
    val outerScale = remember { Animatable(2.6f) }
    val veil = remember { Animatable(0f) }
    val logo = remember { Animatable(0f) }
    val curtain = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch {
            innerTurn.animateTo(0f, tween(1000, easing = FastOutSlowInEasing))
        }
        launch {
            outerTurn.animateTo(0f, tween(1000, easing = FastOutSlowInEasing))
        }
        launch {
            innerScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            outerScale.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
        }
        launch {
            delay(220)
            veil.animateTo(0.35f, tween(300))
        }
        launch {
            delay(260)
            logo.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        delay(1150)
        curtain.animateTo(0f, tween(320))
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = curtain.value }
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .graphicsLayer {
                    rotationZ = outerTurn.value
                    scaleX = outerScale.value
                    scaleY = outerScale.value
                    alpha = veil.value
                }
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = PolygonShape(MaterialShapes.Cookie12Sided)
                )
        )

        Box(
            modifier = Modifier
                .size(148.dp)
                .graphicsLayer {
                    rotationZ = innerTurn.value
                    scaleX = innerScale.value
                    scaleY = innerScale.value
                }
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = PolygonShape(MaterialShapes.Cookie9Sided)
                )
        )

        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(132.dp)
                .graphicsLayer {
                    val pop = 0.7f + logo.value * 0.3f
                    scaleX = pop
                    scaleY = pop
                    alpha = logo.value
                }
        )
    }
}
