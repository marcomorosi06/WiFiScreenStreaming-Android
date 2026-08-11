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
package com.cuscus.wifiscreenstreaming.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val SignalDark = darkColorScheme(
    primary = Color(0xFFB6C6FF),
    onPrimary = Color(0xFF12266B),
    primaryContainer = Color(0xFF2C3F8C),
    onPrimaryContainer = Color(0xFFDDE2FF),
    inversePrimary = Color(0xFF4759A6),
    secondary = Color(0xFF6FE3C4),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF005042),
    onSecondaryContainer = Color(0xFF8FFFDE),
    tertiary = Color(0xFFFFB784),
    onTertiary = Color(0xFF4E2600),
    tertiaryContainer = Color(0xFF6F3900),
    onTertiaryContainer = Color(0xFFFFDCC4),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE3E2E7),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFE3E2E7),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF090B0E),
    surfaceContainerLow = Color(0xFF16181C),
    surfaceContainer = Color(0xFF1A1C20),
    surfaceContainerHigh = Color(0xFF25272B),
    surfaceContainerHighest = Color(0xFF303236),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

val SignalLight = lightColorScheme(
    primary = Color(0xFF41539E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF001256),
    inversePrimary = Color(0xFFB6C6FF),
    secondary = Color(0xFF00695A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF8FFFDE),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFF8B5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC4),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1A1B21),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C6D0)
)
