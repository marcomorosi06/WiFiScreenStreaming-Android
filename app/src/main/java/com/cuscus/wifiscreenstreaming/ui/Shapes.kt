package com.cuscus.wifiscreenstreaming.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

class MorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val turn: Float = 0f
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = morph.toPath(progress).asComposePath()
        path.transform(place(size, turn))
        return Outline.Generic(path)
    }
}

class PolygonShape(
    private val polygon: RoundedPolygon,
    private val turn: Float = 0f
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = polygon.toPath().asComposePath()
        path.transform(place(size, turn))
        return Outline.Generic(path)
    }
}

private fun place(size: Size, turn: Float): Matrix {
    val matrix = Matrix()
    if (turn != 0f) {
        matrix.translate(size.width / 2f, size.height / 2f)
        matrix.rotateZ(turn)
        matrix.translate(-size.width / 2f, -size.height / 2f)
    }
    matrix.scale(size.width, size.height)
    return matrix
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun playfulShape(exclude: RoundedPolygon): RoundedPolygon {
    val shapes = listOf(
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Cookie12Sided,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Sunny,
        MaterialShapes.Burst,
        MaterialShapes.Flower,
        MaterialShapes.Pill,
        MaterialShapes.Gem
    ).filter { it !== exclude }
    return shapes.random()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun signatureShape(seed: String): RoundedPolygon {
    val shapes = listOf(
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Sunny,
        MaterialShapes.Burst,
        MaterialShapes.Pill,
        MaterialShapes.Diamond,
        MaterialShapes.Square,
        MaterialShapes.Circle
    )
    val hash = seed.fold(7) { acc, char -> acc * 31 + char.code }
    return shapes[((hash % shapes.size) + shapes.size) % shapes.size]
}
