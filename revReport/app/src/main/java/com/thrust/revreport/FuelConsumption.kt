package com.thrust.revreport

enum class FuelSource { FUEL_RATE, MAF, MAP }

/** Citirile brute de care are nevoie calculul (null = nu s-a citit). */
data class FuelInputs(
    val fuelRateLph: Double? = null,   // PID 015E, direct în L/h
    val mafGs: Double? = null,         // PID 0110, g/s
    val mapKpa: Double? = null,        // PID 010B
    val iatC: Double? = null,          // PID 010F
    val rpm: Double? = null,           // PID 010C
    val lambda: Double? = null         // PID 0124, doar pentru diesel cu MAF
)

/** Datele motorului, necesare doar pentru metoda MAP/RPM. */
data class EngineConfig(
    val displacementL: Double = 1.6,
    val volumetricEfficiency: Double = 0.85
)

object FuelMath {
    const val GASOLINE_AFR = 14.7
    const val GASOLINE_DENSITY_G_PER_L = 745.0
    const val DIESEL_AFR = 14.5
    const val DIESEL_DENSITY_G_PER_L = 835.0
    private const val GAS_CONSTANT = 287.05      // J/(kg*K), aer
    private const val MAX_PLAUSIBLE_LPH = 150.0

    /**
     * Alege cea mai precisă metodă pe care o permite mașina:
     *  1. PID 5E (debit combustibil), 2. MAF (diesel doar cu lambda, PID 24), 3. MAP/RPM (doar benzină).
     * null = nu se poate calcula.
     */
    fun chooseSource(supported: Set<Int>?, diesel: Boolean): FuelSource? {
        if (supported == null) return null
        return when {
            0x5E in supported -> FuelSource.FUEL_RATE
            0x10 in supported && (!diesel || 0x24 in supported) -> FuelSource.MAF
            !diesel && 0x0B in supported -> FuelSource.MAP
            else -> null
        }
    }

    /** Cheile parametrelor (din ObdParameters) care trebuie citite la fiecare ciclu pentru sursa aleasă. */
    fun keysFor(source: FuelSource?, diesel: Boolean): Set<String> = when (source) {
        FuelSource.FUEL_RATE -> setOf("fuelrate")
        FuelSource.MAF -> if (diesel) setOf("maf", "lambda") else setOf("maf")
        FuelSource.MAP -> setOf("map", "iat")
        null -> emptySet()
    }

    /** Debitul de aer din MAP, turație și temperatura aerului (speed-density), în g/s. */
    fun airFlowFromMap(mapKpa: Double, iatC: Double, rpm: Double, cfg: EngineConfig): Double {
        val tempK = iatC + 273.15
        val kgPerIntake = mapKpa * 1000.0 * (cfg.displacementL / 1000.0) * cfg.volumetricEfficiency /
                (GAS_CONSTANT * tempK)
        val intakesPerSecond = rpm / 120.0          // 4 timpi: o admisie la 2 rotații
        return kgPerIntake * intakesPerSecond * 1000.0
    }

    /** Consumul instantaneu în litri pe oră, sau null dacă lipsesc date. */
    fun fuelRateLph(source: FuelSource, inp: FuelInputs, diesel: Boolean, cfg: EngineConfig): Double? {
        val density = if (diesel) DIESEL_DENSITY_G_PER_L else GASOLINE_DENSITY_G_PER_L
        val lph: Double = when (source) {
            FuelSource.FUEL_RATE -> inp.fuelRateLph ?: return null
            FuelSource.MAF -> {
                val maf = inp.mafGs ?: return null
                val fuelGs = if (diesel) {
                    val lambda = inp.lambda ?: return null
                    if (lambda <= 0.0) return null
                    maf / (DIESEL_AFR * lambda)
                } else {
                    maf / GASOLINE_AFR
                }
                fuelGs * 3600.0 / density
            }
            FuelSource.MAP -> {
                val map = inp.mapKpa ?: return null
                val rpm = inp.rpm ?: return null
                val air = airFlowFromMap(map, inp.iatC ?: 25.0, rpm, cfg)
                air / GASOLINE_AFR * 3600.0 / density
            }
        }
        if (lph.isNaN() || lph < 0.0 || lph > MAX_PLAUSIBLE_LPH) return null
        return lph
    }
}

/** Ce afișăm pe ecran. */
data class FuelSnapshot(
    val source: FuelSource? = null,
    val lPerHour: Double? = null,
    /** null când mașina stă (sub [FuelTrip.MIN_SPEED_KMH]); atunci se arată L/h. */
    val lPer100Km: Double? = null,
    val avgLPer100Km: Double? = null,
    val tripKm: Double = 0.0,
    val tripLiters: Double = 0.0
)

/** Integrează în timp consumul și distanța, pentru media pe cursă. */
class FuelTrip {
    companion object {
        const val MIN_SPEED_KMH = 5.0
        const val MAX_GAP_MS = 3000L       // peste atât (pierdere de conexiune) nu integrăm
        const val SMOOTHING = 0.3          // 0..1; mai mic = mai lin
        const val MIN_TRIP_KM = 0.1
    }

    var distanceKm = 0.0
        private set
    var liters = 0.0
        private set
    private var lastMs: Long? = null
    private var smoothedLph: Double? = null

    fun reset() {
        distanceKm = 0.0
        liters = 0.0
        lastMs = null
        smoothedLph = null
    }

    /** Stare curentă, fără o citire nouă (ex. după o reconectare). */
    fun snapshot(source: FuelSource? = null): FuelSnapshot = FuelSnapshot(
        source = source,
        avgLPer100Km = if (distanceKm >= MIN_TRIP_KM) liters / distanceKm * 100.0 else null,
        tripKm = distanceKm,
        tripLiters = liters
    )

    fun update(nowMs: Long, source: FuelSource?, lph: Double?, speedKmh: Double?): FuelSnapshot {
        val previous = lastMs
        lastMs = nowMs

        if (lph == null) {
            smoothedLph = null
            return snapshot(source)
        }
        smoothedLph = smoothedLph?.let { it + SMOOTHING * (lph - it) } ?: lph

        if (previous != null && speedKmh != null) {
            val dtMs = nowMs - previous
            if (dtMs in 1..MAX_GAP_MS) {
                val hours = dtMs / 3_600_000.0
                liters += lph * hours
                distanceKm += speedKmh * hours
            }
        }

        val smooth = smoothedLph
        val inst = if (smooth != null && speedKmh != null && speedKmh >= MIN_SPEED_KMH) {
            smooth / speedKmh * 100.0
        } else null
        return snapshot(source).copy(lPerHour = smooth, lPer100Km = inst)
    }
}
