package com.example.myapplication.pw20.ui.components

import androidx.compose.ui.graphics.Color

/** Кольорова схема компонентів: вектори, осі X/Y/Z і рівні стану. */
enum class WidgetPalette(
    val label: String,
    val accel: Color,
    val gyro: Color,
    val axes: List<Color>,
    val ok: Color,
    val warn: Color,
    val alarm: Color,
) {
    INDUSTRIAL(
        "Промислова", Color(0xFFFB8C00), Color(0xFF00897B),
        listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5)),
        Color(0xFF43A047), Color(0xFFFFB300), Color(0xFFE53935),
    ),
    CONTRAST(
        "Контрастна", Color(0xFFD50000), Color(0xFF2962FF),
        listOf(Color(0xFFD50000), Color(0xFF00C853), Color(0xFF2962FF)),
        Color(0xFF00C853), Color(0xFFFFAB00), Color(0xFFD50000),
    ),
    OCEAN(
        "Морська", Color(0xFF0277BD), Color(0xFF8E24AA),
        listOf(Color(0xFF0277BD), Color(0xFF26A69A), Color(0xFF8E24AA)),
        Color(0xFF26A69A), Color(0xFFFFA000), Color(0xFFC2185B),
    ),
}

enum class MarkerStyle(val label: String) {
    ARROW("Стрілка"),
    DOT("Точка"),
}

/**
 * Параметри відображення, які приймають усі користувацькі компоненти ПЗ 20.
 * [scale] — масштаб (збільшення) векторів і кутів, [strokeDp] — товщина ліній.
 */
data class WidgetStyle(
    val palette: WidgetPalette = WidgetPalette.INDUSTRIAL,
    val scale: Float = 1f,
    val strokeDp: Float = 3f,
    val marker: MarkerStyle = MarkerStyle.ARROW,
    val showGrid: Boolean = true,
    val showTrail: Boolean = true,
    val showAccel: Boolean = true,
    val showGyro: Boolean = true,
) {
    companion object {
        val SCALE_RANGE = 0.5f..4f
        val STROKE_RANGE = 1f..6f
    }
}
