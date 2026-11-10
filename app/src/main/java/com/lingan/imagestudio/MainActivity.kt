package com.lingan.imagestudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.lingan.imagestudio.ui.StudioApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(
                primary = Color(0xFFFFB49B), onPrimary = Color(0xFF55200E),
                background = Color(0xFF181916), surface = Color(0xFF20211D),
                surfaceVariant = Color(0xFF34362F), secondaryContainer = Color(0xFF3D4034),
            ) else lightColorScheme(
                primary = Color(0xFFBF4F30), onPrimary = Color.White,
                primaryContainer = Color(0xFFFFE9DE), onPrimaryContainer = Color(0xFF763017),
                background = Color(0xFFF8F7F3), surface = Color(0xFFFFFEFA),
                surfaceVariant = Color(0xFFEFEDE6), onSurfaceVariant = Color(0xFF6E7065),
                secondaryContainer = Color(0xFFE9EBDD), onSecondaryContainer = Color(0xFF454C36),
                outlineVariant = Color(0xFFE3E2D9),
            )) { StudioApp() }
        }
    }
}
