package com.thrust.revreport

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Ultima locație cunoscută (lat, lon) sau null. Necesită permisiunea ACCESS_COARSE_LOCATION. */
@SuppressLint("MissingPermission")
fun lastKnownLatLon(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var best: Location? = null
    for (p in lm.getProviders(true)) {
        val l = lm.getLastKnownLocation(p) ?: continue
        if (best == null || l.time > best.time) best = l
    }
    return best?.let { it.latitude to it.longitude }
}

suspend fun fetchOutsideTempC(lat: Double, lon: Double): Double? = withContext(Dispatchers.IO) {
    try {
        // Locale.US: în română "%.2f" ar produce virgulă și ar strica adresa
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=" + String.format(Locale.US, "%.2f", lat) +
                    "&longitude=" + String.format(Locale.US, "%.2f", lon) + "&current=temperature_2m"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val text = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        JSONObject(text).getJSONObject("current").getDouble("temperature_2m")
    } catch (e: Exception) {
        null
    }
}