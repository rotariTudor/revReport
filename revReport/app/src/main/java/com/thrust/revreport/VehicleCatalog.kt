package com.thrust.revreport

import java.text.Normalizer
import java.util.Calendar

/** Rezultatul identificării: marcă, model, an, generație. */
data class VehicleId(
    val make: String?,
    val model: String?,
    val year: Int?,
    val generation: String?,
    /** true dacă anul a fost estimat din caracterul 10 al VIN-ului, nu primit de la decodor */
    val approx: Boolean
) {
    val title: String
        get() {
            val name = listOfNotNull(make, model).joinToString(" ")
            if (name.isBlank()) return "Vehicul necunoscut"
            return if (generation != null) "$name · $generation" else name
        }
}

/**
 * Tabel ORIENTATIV de generații: marcă + model + interval de ani model.
 * La limita dintre generații (ani de tranziție) rezultatul poate fi greșit.
 * Extinde-l liber cu modelele tale.
 */
private data class Gen(val make: String, val model: String, val label: String, val from: Int, val to: Int)

private val generations = listOf(
    Gen("toyota", "prius", "1st gen (XW10)", 1997, 2003),
    Gen("toyota", "prius", "2nd gen (XW20)", 2004, 2009),
    Gen("toyota", "prius", "3rd gen (XW30)", 2010, 2015),
    Gen("toyota", "prius", "4th gen (XW50)", 2016, 2022),
    Gen("toyota", "prius", "5th gen (XW60)", 2023, 2040),
    Gen("toyota", "rav4", "XA30", 2006, 2012),
    Gen("toyota", "rav4", "XA40", 2013, 2018),
    Gen("toyota", "rav4", "XA50", 2019, 2040),

    Gen("audi", "a3", "8L", 1996, 2003),
    Gen("audi", "a3", "8P", 2004, 2012),
    Gen("audi", "a3", "8V", 2013, 2020),
    Gen("audi", "a3", "8Y", 2021, 2040),
    Gen("audi", "a4", "B5", 1995, 2001),
    Gen("audi", "a4", "B6", 2002, 2004),
    Gen("audi", "a4", "B7", 2005, 2007),
    Gen("audi", "a4", "B8", 2008, 2015),
    Gen("audi", "a4", "B9", 2016, 2040),
    Gen("audi", "a6", "C5", 1998, 2004),
    Gen("audi", "a6", "C6", 2005, 2011),
    Gen("audi", "a6", "C7", 2012, 2018),
    Gen("audi", "a6", "C8", 2019, 2040),

    Gen("volkswagen", "golf", "Mk4", 1998, 2003),
    Gen("volkswagen", "golf", "Mk5", 2004, 2008),
    Gen("volkswagen", "golf", "Mk6", 2009, 2012),
    Gen("volkswagen", "golf", "Mk7", 2013, 2019),
    Gen("volkswagen", "golf", "Mk8", 2020, 2040),
    Gen("volkswagen", "passat", "B5", 1997, 2005),
    Gen("volkswagen", "passat", "B6", 2006, 2010),
    Gen("volkswagen", "passat", "B7", 2011, 2014),
    Gen("volkswagen", "passat", "B8", 2015, 2023),

    Gen("bmw", "3series", "E46", 1999, 2005),
    Gen("bmw", "3series", "E90", 2006, 2011),
    Gen("bmw", "3series", "F30", 2012, 2018),
    Gen("bmw", "3series", "G20", 2019, 2040),

    Gen("skoda", "octavia", "A4", 1997, 2004),
    Gen("skoda", "octavia", "A5", 2005, 2012),
    Gen("skoda", "octavia", "A7", 2013, 2019),
    Gen("skoda", "octavia", "A8", 2020, 2040),

    Gen("dacia", "duster", "1st gen", 2010, 2017),
    Gen("dacia", "duster", "2nd gen", 2018, 2023),
    Gen("dacia", "duster", "3rd gen", 2024, 2040),
    Gen("dacia", "logan", "1st gen", 2005, 2012),
    Gen("dacia", "logan", "2nd gen", 2013, 2020),
    Gen("dacia", "logan", "3rd gen", 2021, 2040),

    Gen("ford", "focus", "Mk2", 2005, 2010),
    Gen("ford", "focus", "Mk3", 2011, 2017),
    Gen("ford", "focus", "Mk4", 2018, 2040),

    Gen("honda", "civic", "8th gen", 2006, 2011),
    Gen("honda", "civic", "9th gen", 2012, 2015),
    Gen("honda", "civic", "10th gen", 2016, 2021),
    Gen("honda", "civic", "11th gen", 2022, 2040),

    Gen("renault", "clio", "Gen 2", 1999, 2005),
    Gen("renault", "clio", "Gen 3", 2006, 2012),
    Gen("renault", "clio", "Gen 4", 2013, 2019),
    Gen("renault", "clio", "Gen 5", 2020, 2040),
)

/** Modele care încep la fel, dar nu aparțin aceleiași serii de generații. */
private val excludedModels = listOf("priusv", "priusplus")

/** Prefixul WMI (primele 3 caractere din VIN) → marcă. Funcționează offline. */
private val wmiMakes = mapOf(
    "WAU" to "Audi", "TRU" to "Audi",
    "WVW" to "Volkswagen", "WVG" to "Volkswagen", "3VW" to "Volkswagen",
    "WBA" to "BMW", "WBS" to "BMW", "WBY" to "BMW",
    "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "WDC" to "Mercedes-Benz",
    "JTD" to "Toyota", "JTE" to "Toyota", "JTM" to "Toyota", "SB1" to "Toyota",
    "TMB" to "Škoda",
    "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën",
    "UU1" to "Dacia",
    "ZFA" to "Fiat",
    "WF0" to "Ford",
    "WOL" to "Opel",
    "KMH" to "Hyundai", "KNA" to "Kia", "KND" to "Kia",
    "JHM" to "Honda",
    "JN1" to "Nissan",
    "YV1" to "Volvo",
    "WP0" to "Porsche",
    "VSS" to "SEAT"
)

object VehicleCatalog {

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }

    private fun prettyMake(raw: String): String =
        raw.lowercase().split(" ").joinToString(" ") { word ->
            word.split("-").joinToString("-") { part ->
                when {
                    part == "bmw" -> "BMW"
                    part.length <= 2 -> part.uppercase()
                    else -> part.replaceFirstChar { it.uppercase() }
                }
            }
        }

    /** Anul model din caracterul 10 al VIN-ului (estimare, valabilă la majoritatea mașinilor 2001+). */
    fun yearFromVin(vin: String): Int? {
        if (vin.length < 10) return null
        val c = vin[9].uppercaseChar()
        val letters = "ABCDEFGHJKLMNPRSTVWXY"
        val max = Calendar.getInstance().get(Calendar.YEAR) + 1
        return when {
            c in '1'..'9' -> 2000 + (c - '0')
            c in letters -> {
                val y = 2010 + letters.indexOf(c)
                if (y <= max) y else y - 30
            }
            else -> null
        }
    }

    private fun findGeneration(make: String, model: String, year: Int): String? {
        val m = norm(make)
        val mod = norm(model)
        if (excludedModels.any { mod.startsWith(it) }) return null
        return generations.firstOrNull {
            it.make == m && mod.startsWith(it.model) && year in it.from..it.to
        }?.label
    }

    /**
     * Treapta 1: marca din WMI (offline). Treapta 2: marcă/model/an de la decodorul online.
     * Treapta 3: generația din tabel, după model + an.
     */
    fun identify(vin: String, decoded: VinInfo?): VehicleId {
        val make = decoded?.make?.let { prettyMake(it) } ?: wmiMakes[vin.take(3).uppercase()]
        val model = decoded?.model
        val onlineYear = decoded?.year?.toIntOrNull()
        val year = onlineYear ?: yearFromVin(vin)
        val generation =
            if (make != null && model != null && year != null) findGeneration(make, model, year) else null
        return VehicleId(make, model, year, generation, approx = onlineYear == null)
    }
}