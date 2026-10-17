package com.thrust.revreport

/** Ce a ales utilizatorul pentru mașina lui. */
data class VehicleProfile(val diesel: Boolean = false, val hybrid: Boolean = false)

/**
 * PID-ul pentru nivelul de AdBlue (DEF).
 * NU există un PID universal confirmat: la majoritatea mașinilor e specific producătorului
 * (de obicei Mode 22, ex. "22XXXX"). Completează aici PID-ul mașinii tale.
 * Cât timp e gol, rândul AdBlue apare cu mesajul „PID nesetat".
 */
const val ADBLUE_PID = ""

/**
 * Descrierea unui parametru OBD2: cum îl ceri (pid), cum îl afișezi
 * și cum transformi octeții primiți în valoare reală (decode).
 */
data class ObdParameter(
    val key: String,
    val badge: String,
    val name: String,
    val unit: String,
    val pid: String,
    val decimals: Int = 0,
    val visibleFor: (VehicleProfile) -> Boolean = { true },
    val decode: (List<Int>) -> Double?
)

private fun List<Int>.a(): Int? = getOrNull(0)
private fun List<Int>.ab(): Int? {
    val a = getOrNull(0) ?: return null
    val b = getOrNull(1) ?: return null
    return a * 256 + b
}

val obdParameters = listOf(
    ObdParameter("rpm", "RPM", "Engine RPM", "rpm", "010C") { it.ab()?.let { v -> v / 4.0 } },
    ObdParameter("vss", "VSS", "Vehicle speed", "km/h", "010D") { it.a()?.toDouble() },
    ObdParameter("ect", "ECT", "Engine coolant temperature", "°C", "0105") { it.a()?.let { v -> v - 40.0 } },
    ObdParameter("aat", "AAT", "Ambient air temperature", "°C", "0146") { it.a()?.let { v -> v - 40.0 } },
    ObdParameter("iat", "IAT", "Intake air temperature", "°C", "010F") { it.a()?.let { v -> v - 40.0 } },
    ObdParameter("tp", "TP", "Throttle position", "%", "0111") { it.a()?.let { v -> v * 100.0 / 255.0 } },
    ObdParameter("maf", "MAF", "Mass air flow (MAF)", "g/s", "0110") { it.ab()?.let { v -> v / 100.0 } },
    ObdParameter("map", "MAP", "Intake manifold absolute pressure", "kPa", "010B") { it.a()?.toDouble() },
    ObdParameter("load", "LOAD", "Calculated engine load", "%", "0104") { it.a()?.let { v -> v * 100.0 / 255.0 } },
    ObdParameter("vpwr", "VPWR", "Control module voltage", "V", "0142", decimals = 2) { it.ab()?.let { v -> v / 1000.0 } },

    // Diesel engines do not have spark ignition
    ObdParameter(
        "spark", "SPARK", "Ignition timing advance, cylinder 1", "°", "010E",
        decimals = 1, visibleFor = { !it.diesel }
    ) { it.a()?.let { v -> v / 2.0 - 64.0 } },

    // Hybrid only: Standard PID 5B, one byte, 0–100%
    ObdParameter(
        "hvbat", "BATT", "Hybrid battery level", "%", "015B",
        visibleFor = { it.hybrid }
    ) { it.a()?.let { v -> v * 100.0 / 255.0 } },

    // Diesel only: The PID and formula depend on the manufacturer (see ADBLUE_PID)
    ObdParameter(
        "adblue", "DEF", "AdBlue level", "%", ADBLUE_PID,
        visibleFor = { it.diesel }
    ) { it.a()?.let { v -> v * 100.0 / 255.0 } }, // adjust the formula according to the car's PID
)


fun parametersFor(profile: VehicleProfile): List<ObdParameter> =
    obdParameters.filter { it.visibleFor(profile) }