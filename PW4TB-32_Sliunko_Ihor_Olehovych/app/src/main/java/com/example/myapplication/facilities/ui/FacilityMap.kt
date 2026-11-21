package com.example.myapplication.facilities.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapplication.facilities.Facility
import com.example.myapplication.facilities.GeoPoint
import com.example.myapplication.facilities.Readiness
import com.example.myapplication.ui.common.MessageBanner
import com.example.myapplication.ui.common.Tone
import com.example.myapplication.ui.common.fmt
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap as GmsGoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker as GmsMarker
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

private val KYIV = LatLng(50.4501, 30.5234)

fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

/** Перевіряє, чи підставлено ключ із local.properties; без нього мапа показала б порожній екран. */
@Composable
private fun rememberMapsKeyConfigured(): Boolean {
    val context = LocalContext.current
    return remember {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        !info.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
    }
}

/**
 * Мапа об'єктів, спільна для ПЗ 14 і ПЗ 16.
 * Зміна [selectedId] переводить камеру на маркер і відкриває InfoWindow, а натискання маркера
 * повідомляє про вибір через [onMarkerClick] — так список і мапа синхронізуються в обидва боки.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun FacilityMap(
    facilities: List<Facility>,
    selectedId: String?,
    onMarkerClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    myLocation: GeoPoint? = null,
    showMyLocation: Boolean = false,
) {
    if (!rememberMapsKeyConfigured()) {
        Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            MessageBanner("Додайте MAPS_API_KEY у local.properties, щоб побачити мапу", Tone.WARN)
        }
        return
    }
    val context = LocalContext.current
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(KYIV, 10f) }

    // Камера переходить до вибраного об'єкта; якщо користувач рухає мапу, анімація просто скасовується
    LaunchedEffect(selectedId) {
        val target = facilities.firstOrNull { it.id == selectedId } ?: return@LaunchedEffect
        camera.animate(CameraUpdateFactory.newLatLngZoom(target.location.toLatLng(), 13f), 600)
    }
    // Перша отримана позиція показує користувача, якщо жоден об'єкт ще не вибрано
    val hasFix = myLocation != null
    LaunchedEffect(hasFix) {
        if (hasFix && selectedId == null) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(myLocation!!.toLatLng(), 11f), 600)
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        // Шар «моє місце» вмикається лише після надання дозволу, інакше SDK викине SecurityException
        properties = MapProperties(isMyLocationEnabled = showMyLocation),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = showMyLocation, mapToolbarEnabled = false),
    ) {
        MapEffect(Unit) { map -> map.setInfoWindowAdapter(FacilityInfoWindowAdapter(context)) }
        facilities.forEach { facility ->
            key(facility.id) {
                val selected = facility.id == selectedId
                val markerState = rememberUpdatedMarkerState(position = facility.location.toLatLng())
                LaunchedEffect(selected) {
                    if (selected) markerState.showInfoWindow() else markerState.hideInfoWindow()
                }
                Marker(
                    state = markerState,
                    title = facility.name,
                    // Адаптер InfoWindow бере дані об'єкта з tag маркера
                    tag = facility,
                    icon = BitmapDescriptorFactory.defaultMarker(markerHue(facility.readiness, selected)),
                    zIndex = if (selected) 1f else 0f,
                    onClick = {
                        onMarkerClick(facility.id)
                        false
                    },
                )
            }
        }
    }
}

/** Вибраний маркер синій, решта мають колір готовності резерву. */
private fun markerHue(readiness: Readiness, selected: Boolean): Float = when {
    selected -> BitmapDescriptorFactory.HUE_AZURE
    readiness == Readiness.READY -> BitmapDescriptorFactory.HUE_GREEN
    readiness == Readiness.LIMITED -> BitmapDescriptorFactory.HUE_ORANGE
    else -> BitmapDescriptorFactory.HUE_RED
}

/**
 * Вміст спливаючого вікна. SDK малює InfoWindow як статичне зображення View,
 * тому тут використано звичайні TextView; кольори задані явно, бо вікно завжди має світлий фон.
 */
private class FacilityInfoWindowAdapter(private val context: Context) : GmsGoogleMap.InfoWindowAdapter {
    override fun getInfoWindow(marker: GmsMarker): View? = null

    override fun getInfoContents(marker: GmsMarker): View? {
        val facility = marker.tag as? Facility ?: return null
        val maxWidth = (240 * context.resources.displayMetrics.density).toInt()
        fun line(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            setMaxWidth(maxWidth)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(line(facility.name, 14f, AndroidColor.BLACK, bold = true))
            addView(line("${facility.type.label} · ${facility.status.label}", 12f, AndroidColor.DKGRAY))
            addView(line(facility.description, 12f, AndroidColor.BLACK))
            addView(line("${facility.reserveKw.fmt()} кВт · автономність ${facility.autonomyHours.fmt()} год", 12f, AndroidColor.BLACK, bold = true))
        }
    }
}
