package com.thrust.revreport

/** Un test (monitor) al mașinii: gata sau nu. */
data class MonitorStatus(val name: String, val ready: Boolean)

/** Starea testelor de pregătire (readiness), din răspunsul la PID 0101. */
data class Readiness(
    val diesel: Boolean,
    val monitors: List<MonitorStatus>
) {
    val notReady: List<MonitorStatus> get() = monitors.filter { !it.ready }
    val allReady: Boolean get() = notReady.isEmpty()
}

/**
 * Interpretează cei 4 octeți A,B,C,D din răspunsul la 0101 (SAE J1979).
 *  - B: biții 0-2 = test suportat (misfire, fuel system, components), biții 4-6 = test NEterminat,
 *       bitul 3 = 0 benzină / 1 diesel
 *  - C: ce teste suportă mașina; D: ce teste sunt NEterminate (același bit)
 * Întoarce null dacă răspunsul e prea scurt.
 */
fun parseReadiness(bytes: List<Int>): Readiness? {
    if (bytes.size < 4) return null
    val b = bytes[1]
    val c = bytes[2]
    val d = bytes[3]
    val diesel = (b and 0x08) != 0
    val monitors = mutableListOf<MonitorStatus>()

    val continuous = listOf("Misfire", "Fuel system", "Components")
    continuous.forEachIndexed { i, name ->
        if (((b shr i) and 1) == 1) {
            monitors += MonitorStatus(name, ((b shr (i + 4)) and 1) == 0)
        }
    }

    val names: List<String?> = if (diesel) {
        listOf(
            "NMHC catalyst", "NOx/SCR aftertreatment", null, "Boost pressure",
            null, "Exhaust gas sensor", "PM filter", "EGR / VVT system"
        )
    } else {
        listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT system"
        )
    }
    names.forEachIndexed { i, name ->
        if (name != null && ((c shr i) and 1) == 1) {
            monitors += MonitorStatus(name, ((d shr i) and 1) == 0)
        }
    }
    return Readiness(diesel, monitors)
}
