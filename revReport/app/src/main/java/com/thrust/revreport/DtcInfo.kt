package com.thrust.revreport

/**
 * Descrieri pentru codurile de eroare (DTC).
 * Sursa principala: [DtcData] (generat din obd-trouble-codes.csv).
 * Pentru coduri care nu sunt in CSV se foloseste [extra], apoi o descriere generala dupa categorie.
 */
object DtcDescriptions {

    /** Coduri care lipsesc din CSV (hibrid, DPF etc.). */
    private val extra = mapOf(
        "P0A80" to "Replace hybrid battery pack",
        "P2002" to "Particulate filter efficiency below threshold (bank 1)",
        "P2463" to "Particulate filter soot accumulation"
    )

    fun describe(rawCode: String): String {
        val code = rawCode.trim().uppercase()
        DtcData.all[code]?.let { return it }
        extra[code]?.let { return it }
        if (code.length < 2) return "Unknown code"

        val system = when (code[0]) {
            'P' -> "Powertrain"
            'C' -> "Chassis"
            'B' -> "Body"
            'U' -> "Network / communication"
            else -> "Unknown"
        }
        val scope = when (code[1]) {
            '0', '2' -> "generic (SAE) code"
            '1' -> "manufacturer-specific code"
            '3' -> "generic or manufacturer-specific code"
            else -> "code"
        }
        return "$system, $scope. Look it up for your car's make."
    }
}
