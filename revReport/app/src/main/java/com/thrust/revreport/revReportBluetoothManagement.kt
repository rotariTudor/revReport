package com.thrust.revreport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class revReportBluetoothManagement(val bluetoothAdapter: BluetoothAdapter?) {

    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    var currentSocket: BluetoothSocket? = null
    var inputStream: InputStream? = null
    var outputStream: OutputStream? = null

    /** O singură comandă odată pe stream: perechea cerere + răspuns nu se mai amestecă. */
    private val ioLock = Mutex()

    private fun openStreams(socket: BluetoothSocket) {
        currentSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
    }

    // 1. Conectarea la adaptor (apelează-o de pe Dispatchers.IO)
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        bluetoothAdapter?.cancelDiscovery()
        closeConnection()

        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "SPP standard" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "SPP insecure" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "canal 1 (reflection)" to {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            }
        )

        for ((name, create) in attempts) {
            var socket: BluetoothSocket? = null
            try {
                Log.d("RevReport", "Încerc $name...")
                socket = create()
                socket.connect()
                openStreams(socket)
                return true
            } catch (e: Exception) {
                Log.e("RevReport", "$name a eșuat: ${e.message}")
                // Socket-ul eșuat trebuie închis, altfel rămâne deschis și strică încercările următoare
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    // 2. Inițializarea adaptorului cu comenzi AT. Returnează răspunsul la ATZ.
    suspend fun initializeAdapter(): String = withContext(Dispatchers.IO) {
        val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")
        var firstResponse = ""

        for (cmd in commands) {
            try {
                drainInput()
                outputStream?.write("$cmd\r".toByteArray())
                outputStream?.flush()
                val response = readResponse(if (cmd == "ATZ") 4000 else 2000)
                Log.d("RevReport", "Răspuns la $cmd: '$response'")
                if (cmd == "ATZ") firstResponse = response
            } catch (e: Exception) {
                Log.e("RevReport", "Eroare la comanda $cmd: ${e.message}")
                break
            }
        }
        firstResponse
    }

    private fun drainInput() {
        try {
            inputStream?.let { s -> while (s.available() > 0) s.read() }
        } catch (_: Exception) {
        }
    }

    // 3. Citirea fluxului până la '>' (cu timeout)
    suspend fun readResponse(timeoutMs: Long = 3000): String = withContext(Dispatchers.IO) {
        val response = StringBuilder()
        val stream = inputStream ?: return@withContext ""
        val start = System.currentTimeMillis()

        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (stream.available() > 0) {
                    val c = stream.read().toChar()
                    response.append(c)
                    if (c == '>') break
                    if (response.length > 500) break
                } else {
                    delay(20)
                }
            }
        } catch (e: Exception) {
            Log.e("RevReport", "Eroare I/O la citire: ${e.message}")
        }

        response.toString().replace(">", "").trim()
    }

    suspend fun sendCommand(command: String) = withContext(Dispatchers.IO) {
        try {
            outputStream?.write("$command\r".toByteArray())
            outputStream?.flush()
            Log.d("RevReport", "Comandă trimisă: $command")
        } catch (e: Exception) {
            Log.e("RevReport", "Eroare la trimiterea comenzii $command: ${e.message}")
        }
    }

    /** Golește bufferul, trimite comanda și întoarce răspunsul brut. */
    suspend fun request(cmd: String, timeoutMs: Long = 3000): String = ioLock.withLock {
        withContext(Dispatchers.IO) { drainInput() }
        sendCommand(cmd)
        readResponse(timeoutMs)
    }

    fun sanitizeResponse(rawResponse: String): String {
        return rawResponse
            .replace("SEARCHING...", "")
            .replace("\\s".toRegex(), "")
            .replace("STOPPED", "ERROR")
            .trim()
    }

    /**
     * Cere un PID (ex. "010C" sau "22XXXX") și întoarce octeții de date,
     * sau null dacă răspunsul e NO DATA / eroare.
     * Răspunsul începe cu modul + 0x40 (01 -> 41, 22 -> 62).
     */
    suspend fun readPid(pid: String): List<Int>? {
        val clean = sanitizeResponse(request(pid)).uppercase()
        val mode = pid.substring(0, 2).toIntOrNull(16) ?: return null
        val prefix = "%02X".format(mode + 0x40) + pid.substring(2).uppercase()
        val idx = clean.indexOf(prefix)
        if (idx < 0) return null
        return clean.substring(idx + prefix.length)
            .chunked(2)
            .filter { it.length == 2 }
            .mapNotNull { it.toIntOrNull(16) }
    }

    suspend fun readRpm(): Int? {
        val b = readPid("010C") ?: return null
        return if (b.size >= 2) (b[0] * 256 + b[1]) / 4 else null
    }

    suspend fun readSpeed(): Int? = readPid("010D")?.firstOrNull()
    suspend fun readCoolantTemp(): Int? = readPid("0105")?.firstOrNull()?.minus(40)

    /** Tipul de combustibil (PID 0151), cod brut. */
    suspend fun readFuelType(): Int? = readPid("0151")?.firstOrNull()

    /**
     * După ATSP0, prima comandă declanșează căutarea protocolului (5–10 s pe mașini mai vechi).
     * O așteptăm aici, ca să nu se piardă răspunsuri. false = mașina nu răspunde (contact oprit?).
     */
    suspend fun warmUp(): Boolean {
        val r = sanitizeResponse(request("0100", 12_000)).uppercase()
        return r.contains("4100")
    }

    /** true = viteză 0 și motor oprit; false = se mișcă sau motorul merge; null = nu știm. */
    suspend fun isStationary(): Boolean? {
        val speed = readPid("010D")?.firstOrNull() ?: return null
        val rpm = readPid("010C")?.let { b -> if (b.size >= 2) (b[0] * 256 + b[1]) / 4 else null }
        return speed == 0 && (rpm == null || rpm == 0)
    }

    /** Toate PID-urile de Mode 01 suportate de mașină (numere, ex. 0x0C). */
    suspend fun readSupportedPids(): List<Int> {
        val result = mutableListOf<Int>()
        var base = 0
        while (base <= 0xE0) {
            val bytes = readPid("01%02X".format(base)) ?: break
            if (bytes.size < 4) break
            for (i in 0 until 32) {
                val bit = (bytes[i / 8] shr (7 - i % 8)) and 1
                if (bit == 1) result.add(base + i + 1)
            }
            // Ultimul bit spune dacă există și grupul următor
            if (result.lastOrNull() != base + 32) break
            base += 32
        }
        return result
    }

    /** Rezultatul detectării automate: profilul + datele brute pe care s-a bazat. */
    data class Detection(
        val profile: VehicleProfile,
        val fuelCode: Int?,
        val supportedPids: List<Int>
    )

    /**
     * Detectează singură dacă mașina e hibridă și/sau diesel:
     *  - combustibil din PID 0151 (17–22 = hibrid; 4, 19, 23 = motorină);
     *  - hibrid și dacă mașina suportă PID 015B (nivel baterie hibridă);
     *  - dacă 0151 nu răspunde, diesel se deduce din PID 0101 (bitul de aprindere prin compresie).
     */
    suspend fun detectVehicle(): Detection {
        val fuel = readFuelType()
        val supported = readSupportedPids()

        val hybrid = (fuel != null && fuel in 17..22) || supported.contains(0x5B)
        val diesel = when {
            fuel != null && fuel in listOf(4, 19, 23) -> true
            fuel != null && fuel != 0 -> false
            else -> ((readPid("0101")?.getOrNull(1) ?: 0) and 0x08) != 0
        }
        return Detection(VehicleProfile(diesel = diesel, hybrid = hybrid), fuel, supported)
    }

    /** VIN-ul (Mode 09 PID 02). Gestionează răspunsul pe mai multe cadre (CAN) și pe linii (K-line). */
    suspend fun readVin(): String? {
        val raw = request("0902", 6000)
        val lines = raw.split('\r', '\n')
            .map { it.trim().replace(" ", "").uppercase() }
            .filter { it.isNotEmpty() }

        val canFrame = Regex("^[0-9A-F]:.*")
        val hex = StringBuilder()

        if (lines.any { canFrame.matches(it) }) {
            // CAN: liniile "0:...", "1:...", "2:..." se lipesc, apoi se scoate 4902 + numărul de elemente (01)
            lines.filter { canFrame.matches(it) }.forEach { hex.append(it.substring(2)) }
            if (hex.startsWith("4902")) hex.delete(0, 6)
        } else {
            // K-line / ISO: fiecare linie e 4902 + index + 4 octeți
            lines.filter { it.startsWith("4902") && it.length > 6 }
                .forEach { hex.append(it.substring(6)) }
        }

        val vin = hex.toString()
            .chunked(2)
            .mapNotNull { it.toIntOrNull(16)?.toChar() }
            .filter { it.code < 128 && it.isLetterOrDigit() }
            .joinToString("")
            .take(17)

        return if (vin.length == 17) vin else null
    }

    // ───────────── Coduri de eroare (DTC) ─────────────

    /** Pe CAN (protocoalele 6–9) răspunsul conține un octet cu numărul de coduri; pe celelalte nu. */
    private suspend fun isCanProtocol(): Boolean {
        val r = sanitizeResponse(request("ATDPN")).uppercase()
        val c = r.lastOrNull() ?: return true
        return c !in '1'..'5'
    }

    /** Transformă doi octeți în cod, ex. 0x01 0x33 -> P0133. */
    private fun dtcToString(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 3]
        val d1 = (a shr 4) and 3
        val d2 = a and 0xF
        return "$letter$d1${"%X".format(d2)}${"%02X".format(b)}"
    }

    private fun parseDtcPairs(hex: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i + 4 <= hex.length) {
            val a = hex.substring(i, i + 2).toIntOrNull(16)
            val b = hex.substring(i + 2, i + 4).toIntOrNull(16)
            // perechea 0000 e doar umplutură
            if (a != null && b != null && !(a == 0 && b == 0)) out.add(dtcToString(a, b))
            i += 4
        }
        return out
    }

    /**
     * Citește codurile de eroare. mode: "03" = confirmate, "07" = în așteptare (pending), "0A" = permanente.
     * Întoarce lista (goală dacă nu sunt coduri) sau null dacă mașina nu a răspuns valid.
     */
    suspend fun readDtcs(mode: String): List<String>? {
        val respMode = "%02X".format(mode.toInt(16) + 0x40)
        val raw = request(mode, 6000)
        val lines = raw.split('\r', '\n')
            .map { it.trim().replace(" ", "").uppercase() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && !it.startsWith("BUSINIT") }

        if (lines.isEmpty()) return null
        if (lines.any { it.contains("UNABLE") || it.contains("ERROR") || it.contains("?") }) return null
        if (lines.all { it.contains("NODATA") }) return emptyList()

        val can = isCanProtocol()
        val frame = Regex("^[0-9A-F]:.*")
        val result = mutableListOf<String>()

        val multi = lines.filter { frame.matches(it) }
        if (multi.isNotEmpty()) {
            // răspuns pe mai multe cadre (CAN): "0:...", "1:..." se lipesc
            var h = multi.joinToString("") { it.substring(2) }
            if (h.startsWith(respMode)) h = h.substring(2)
            if (can && h.length >= 2) h = h.substring(2)
            result += parseDtcPairs(h)
        } else {
            for (line in lines) {
                if (!line.startsWith(respMode)) continue
                var h = line.substring(2)
                if (can && h.length >= 2) h = h.substring(2)
                result += parseDtcPairs(h)
            }
        }
        return result.distinct()
    }

    /** Starea becului check-engine și numărul de coduri raportate de ECU (PID 0101). Pair(milAprins, număr). */
    suspend fun readMilStatus(): Pair<Boolean, Int>? {
        val a = readPid("0101")?.firstOrNull() ?: return null
        return Pair((a and 0x80) != 0, a and 0x7F)
    }

    /** Starea testelor de pregătire (readiness monitors), din același PID 0101. */
    suspend fun readReadiness(): Readiness? = readPid("0101")?.let { parseReadiness(it) }

    /**
     * Șterge codurile de eroare (Mode 04). Întoarce (reușit, răspunsul brut).
     * Reușit = toate ECU-urile au răspuns cu 44. Cere de obicei contact pus și motor oprit.
     */
    suspend fun clearDtcs(): Pair<Boolean, String> {
        val raw = request("04", 6000)
        val clean = sanitizeResponse(raw).uppercase()
        val ok = Regex("^(44)+$").matches(clean)
        return Pair(ok, raw.replace(Regex("\\s+"), " ").trim())
    }

    fun closeConnection() {
        try {
            inputStream?.close()
            outputStream?.close()
            currentSocket?.close()
        } catch (e: IOException) {
            Log.e("RevReport", "Eroare la închiderea socket-ului: ${e.message}")
        } finally {
            inputStream = null
            outputStream = null
            currentSocket = null
        }
    }
}