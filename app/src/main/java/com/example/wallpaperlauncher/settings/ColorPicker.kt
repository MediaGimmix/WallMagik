package com.example.wallpaperlauncher.settings

import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val PRESETS = listOf(
    0xFF000000, 0xFF1B1036, 0xFF0D1B2A, 0xFF13315C, 0xFF3A0CA3, 0xFF6A040F, 0xFF2D6A4F, 0xFF5A189A,
    0xFFFFFFFF, 0xFFFFC796, 0xFFF8F1E5, 0xFF90E0EF, 0xFFFFD60A, 0xFFFF758F, 0xFFB7E4C7, 0xFFE0AAFF,
).map { it.toInt() }

/** HSV sliders + hex field + swatches. */
@Composable
fun ColorPickerDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv0 = remember { FloatArray(3).also { AColor.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    val color = AColor.HSVToColor(floatArrayOf(hue, sat, value))
    var hexText by remember { mutableStateOf(hex(initial)) }

    fun setColor(c: Int) {
        val f = FloatArray(3)
        AColor.colorToHSV(c, f)
        hue = f[0]; sat = f[1]; value = f[2]
        hexText = hex(c)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).background(Color(color), RoundedCornerShape(12.dp)))
                GradientSlider(
                    "Hue", hue / 360f,
                    Brush.horizontalGradient((0..6).map { Color(AColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) })
                ) { hue = it * 360f; hexText = hex(AColor.HSVToColor(floatArrayOf(hue, sat, value))) }
                GradientSlider(
                    "Saturation", sat,
                    Brush.horizontalGradient(listOf(
                        Color(AColor.HSVToColor(floatArrayOf(hue, 0f, value))),
                        Color(AColor.HSVToColor(floatArrayOf(hue, 1f, value))),
                    ))
                ) { sat = it; hexText = hex(AColor.HSVToColor(floatArrayOf(hue, sat, value))) }
                GradientSlider(
                    "Brightness", value,
                    Brush.horizontalGradient(listOf(Color.Black, Color(AColor.HSVToColor(floatArrayOf(hue, sat, 1f)))))
                ) { value = it; hexText = hex(AColor.HSVToColor(floatArrayOf(hue, sat, value))) }
                TextField(
                    value = hexText,
                    onValueChange = { t ->
                        hexText = t
                        val s = t.trim().removePrefix("#")
                        if (s.length == 6) s.toLongOrNull(16)?.let { setColor((0xFF000000 or it).toInt()); hexText = t }
                    },
                    label = { Text("Hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PRESETS.chunked(8).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { c ->
                            Box(
                                Modifier.size(26.dp).background(Color(c), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                    .clickable { setColor(c) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color or 0xFF000000.toInt()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GradientSlider(label: String, v: Float, brush: Brush, onChange: (Float) -> Unit) {
    Column {
        Text(label)
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(12.dp).background(brush, RoundedCornerShape(6.dp)))
            Slider(
                value = v, onValueChange = onChange,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    thumbColor = Color.White,
                ),
            )
        }
    }
}
