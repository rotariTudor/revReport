package com.thrust.revreport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VinInfo(
    val make: String?,
    val model: String?,
    val year: String?,
    val cylinders: String?,
    val displacementL: String?,
    val fuel: String?,
    val electrification: String?
)

/** Decodare VIN prin API-ul gratuit NHTSA vPIC. Necesită permisiunea INTERNET. */
object VinDecoder {
    suspend fun decode(vin: String): VinInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/$vin?format=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val text = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val obj = JSONObject(text).getJSONArray("Results").getJSONObject(0)
            fun field(k: String): String? =
                obj.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }

            VinInfo(
                make = field("Make"),
                model = field("Model"),
                year = field("ModelYear"),
                cylinders = field("EngineCylinders"),
                displacementL = field("DisplacementL"),
                fuel = field("FuelTypePrimary"),
                electrification = field("ElectrificationLevel")
            )
        } catch (e: Exception) {
            Log.e("RevReport", "Decodare VIN eșuată: ${e.message}")
            null
        }
    }
}

// ── Salvare locală (cache), ca VIN-ul deja decodat să funcționeze și fără internet ──

fun VinInfo.toJson(): String = JSONObject().apply {
    make?.let { put("make", it) }
    model?.let { put("model", it) }
    year?.let { put("year", it) }
    cylinders?.let { put("cylinders", it) }
    displacementL?.let { put("displacementL", it) }
    fuel?.let { put("fuel", it) }
    electrification?.let { put("electrification", it) }
}.toString()

fun vinInfoFromJson(s: String): VinInfo? = try {
    val o = JSONObject(s)
    fun f(k: String): String? = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
    VinInfo(
        make = f("make"), model = f("model"), year = f("year"),
        cylinders = f("cylinders"), displacementL = f("displacementL"),
        fuel = f("fuel"), electrification = f("electrification")
    )
} catch (e: Exception) {
    null
}