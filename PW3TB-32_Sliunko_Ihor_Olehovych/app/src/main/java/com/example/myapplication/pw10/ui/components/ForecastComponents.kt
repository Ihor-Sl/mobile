package com.example.myapplication.pw10.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.pw10.domain.FacilityForecast
import com.example.myapplication.pw10.domain.OutagePlanner
import com.example.myapplication.pw10.domain.OutageWindow
import com.example.myapplication.pw10.domain.Readiness
import com.example.myapplication.ui.common.InfoRow
import com.example.myapplication.ui.common.StatusBadge
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.colors
import com.example.myapplication.ui.common.fmt

fun Readiness.tone() = when (this) {
    Readiness.SECURED -> Tone.GOOD
    Readiness.AT_RISK -> Tone.WARN
    Readiness.SHORTAGE -> Tone.BAD
}

private fun categoryLabel(c: Int) = when (c) {
    1 -> "I категорія"
    2 -> "II категорія"
    else -> "III категорія"
}

/** Шкала доби 0–24 год, на якій кольором позначено вікна планових відключень. */
@Composable
fun OutageTimeline(windows: List<OutageWindow>, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            windows.forEach { w ->
                val x = size.width * w.fromH / 24f
                val width = size.width * w.hours / 24f
                drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(width, size.height), cornerRadius = r)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Адаптивна картка об'єкта. Натискання розгортає склад резервних джерел і телеметрію.
 * Стан розгортання зберігається через rememberSaveable (переживає поворот екрана).
 */
@Composable
fun FacilityCard(item: FacilityForecast, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(item.facility.id) { mutableStateOf(false) }
    val tone = item.readiness.tone()
    val accent = tone.colors().second
    val f = item.facility
    Card(
        modifier = modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${categoryLabel(f.category)} · ${f.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(item.readiness.label, tone)
            }
            Text(
                "Черга ${item.queue ?: "—"}: " + item.windows.joinToString { "%02d–%02d".format(it.fromH, it.toH) } +
                    " (разом ${item.requiredH.fmt(0)} год)",
                style = MaterialTheme.typography.bodySmall
            )
            OutageTimeline(item.windows, accent.copy(alpha = 0.75f))
            InfoRow(
                "Автономність / потрібно ×${OutagePlanner.SAFETY_FACTOR}",
                "${item.autonomyH.fmt()} / ${(item.requiredH * OutagePlanner.SAFETY_FACTOR).fmt()} год",
                valueColor = accent
            )
            InfoRow(
                "Навантаження від резерву",
                "${item.servedLoadKw.fmt(0)} кВт" + if (item.loadShedding) " (лише критичні)" else ""
            )
            if (item.fuelNeededL > 0) {
                Text("Рекомендація: довезти ${item.fuelNeededL.fmt(0)} л дизпалива", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = accent)
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    f.generator?.let { InfoRow("ДГУ", "${it.ratedKw.fmt(0)} кВт, бак ${it.tankL.fmt(0)} л, ${it.fullLoadLph.fmt(0)} л/год") }
                    f.ups?.let { InfoRow("ДБЖ", "${it.ratedKw.fmt(0)} кВт, АКБ ${it.batteryKwh.fmt(0)} кВт·год") }
                    item.telemetry?.let { t ->
                        InfoRow("Паливо (телеметрія)", "${t.fuelL.fmt(0)} л, витрата ${item.consumptionLph.fmt(2)} л/год")
                        t.batterySoc?.let { InfoRow("Заряд АКБ", "${(it * 100).fmt(0)} %") }
                        InfoRow("Мережа зараз", if (t.gridOnline) "є" else "немає — працює резерв")
                    }
                }
            }
            if (!expanded) Text("Натисніть, щоб побачити джерела й телеметрію", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
