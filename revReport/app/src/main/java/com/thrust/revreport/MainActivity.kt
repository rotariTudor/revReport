package com.thrust.revreport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

//private val Purple = Color(0xFF673AB7)
private val Orange = Color(0xFFEF6C00)
private val Red = Color(0xFFB00020)
private val Bg = Color(0xFF0E0E0E)
private val Card = Color(0xFF1C1C1C)
private val TextMain = Color.White
private val TextDim = Color(0xFFB0B0B0)

/** Un dispozitiv asociat, pregătit pentru afișare în meniu. */
data class DeviceItem(val name: String, val address: String)

enum class Screen { Params, Info, Faults }

/** Ce am citit din mașină pe ecranul „Info vehicul". */
data class VehicleInfoState(
    val vin: String? = null,
    val fuelType: String? = null,
    val hybridDetected: Boolean? = null,
    val supportedPids: List<Int>? = null,
    val decoded: VinInfo? = null,
    val message: String? = null
)

/** Codurile de eroare citite din mașină. null = încă necitit. */
data class DtcState(
    val stored: List<String>? = null,
    val pending: List<String>? = null,
    val permanent: List<String>? = null,
    val milOn: Boolean? = null,
    val dtcCount: Int? = null,
    val readiness: Readiness? = null,
    val message: String? = null
)

fun fuelTypeName(code: Int): String = when (code) {
    0 -> "Unknown"
    1 -> "Gas"
    2 -> "Methanol"
    3 -> "Ethanol"
    4 -> "Diesel"
    5 -> "GPL"
    6 -> "GNC"
    7 -> "Propan"
    8 -> "Electric"
    in 9..16 -> "Bi-fuel (cod $code)"
    17 -> "Hybrid gas"
    18 -> "Hybrid ethanol"
    19 -> "Hybrid diesel"
    20 -> "Electric hybrid"
    21 -> "Electric hybrid + thermal"
    22 -> "Regenerative hybrid"
    23 -> "Bi-fuel diesel"
    else -> "Necunoscut (cod $code)"
}

class MainActivity : ComponentActivity() {

    private lateinit var btManager: revReportBluetoothManagement

    @OptIn(ExperimentalLayoutApi::class)
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()   // trebuie apelat înainte de super.onCreate
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        btManager = revReportBluetoothManagement(bluetoothManager?.adapter)
        val prefs = getSharedPreferences("revreport", MODE_PRIVATE)

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            var status by remember { mutableStateOf("Disconnected") }
            var busy by remember { mutableStateOf(false) }
            var connectedDevice by remember { mutableStateOf<DeviceItem?>(null) }
            var pollJob by remember { mutableStateOf<Job?>(null) }
            var showDevices by remember { mutableStateOf(false) }
            var showMainMenu by remember { mutableStateOf(false) }
            var screen by remember { mutableStateOf(Screen.Params) }
            var profile by remember { mutableStateOf(VehicleProfile()) }
            var profileKnown by remember { mutableStateOf(false) }
            // PID-urile de Mode 01 pe care le are mașina (null = încă necunoscut -> le arătăm pe toate)
            var supported by remember { mutableStateOf<Set<Int>?>(null) }
            var info by remember { mutableStateOf(VehicleInfoState()) }
            var infoLoading by remember { mutableStateOf(false) }
            var vehicle by remember { mutableStateOf<VehicleId?>(null) }
            var faults by remember { mutableStateOf(DtcState()) }
            var faultsLoading by remember { mutableStateOf(false) }
            var showClearConfirm by remember { mutableStateOf(false) }
            // Consum: media pe cursă se păstrează între reconectări; se resetează doar din buton
            val fuelTrip = remember { FuelTrip() }
            var fuel by remember { mutableStateOf(FuelSnapshot()) }
            var engineL by remember { mutableStateOf(prefs.getFloat("engine_l", 1.6f).toDouble()) }
            var showEngineDialog by remember { mutableStateOf(false) }
            val devices = remember { mutableStateListOf<DeviceItem>() }
            val values = remember { mutableStateMapOf<String, String?>() }
            val unavailable = remember { mutableStateMapOf<String, Boolean>() }
            var locationGranted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                )
            }
            val locationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { locationGranted = it }

            LaunchedEffect(Unit) {
                if (!locationGranted) locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // Temperatura telefonului: apare ca placă pe ecranul Parameters
            LaunchedEffect(Unit) {
                while (true) {
                    values["phoneTemp"] = readPhoneTempC(context)?.let { "%.0f".format(it) }
                    delay(5_000)
                }
            }
            // Temperatura de afară (Open-Meteo); dacă nu avem locație, reîncearcă după 1 minut
            LaunchedEffect(locationGranted) {
                if (!locationGranted) return@LaunchedEffect
                while (true) {
                    val t = lastKnownLatLon(context)?.let { fetchOutsideTempC(it.first, it.second) }
                    values["weatherTemp"] = t?.let { "%.0f".format(it) }
                    delay(if (t == null) 60_000L else 15 * 60_000L)
                }
            }

            val params = parametersFor(profile)

            fun toast(msg: String, long: Boolean = false) {
                Toast.makeText(context, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }

            fun cachedVinInfo(vin: String): VinInfo? =
                prefs.getString("vin_$vin", null)?.let { vinInfoFromJson(it) }

            fun cacheVinInfo(vin: String, v: VinInfo) {
                prefs.edit().putString("vin_$vin", v.toJson()).apply()
            }

            fun stopPolling() {
                pollJob?.cancel()
                pollJob = null
            }

            /** Șterge tot ce ține de mașina conectată, ca să nu rămână date vechi pe ecran. */
            fun resetSession() {
                connectedDevice = null
                vehicle = null
                profile = VehicleProfile()
                profileKnown = false
                supported = null
                fuel = fuelTrip.snapshot()
                info = VehicleInfoState()
                faults = DtcState()
                val local = values.filterKeys { it == "phoneTemp"}
                values.clear()
                unavailable.clear()
                values.putAll(local)
            }

            fun startPolling() {
                stopPolling()
                pollJob = coroutineScope.launch {
                    var failures = 0
                    var cycle = 0
                    var misses = mutableMapOf<String,Int>()
                    val raw = mutableMapOf<String, Double>()   // valori nerotunjite, pentru calculul consumului
                    val fuelSource = FuelMath.chooseSource(supported, profile.diesel)
                    val fuelKeys = FuelMath.keysFor(fuelSource, profile.diesel)
                    while (isActive) {
                        var attempted = false
                        var gotAny = false
                        for (p in parametersFor(profile)) {
                            if (!isActive) break
                            if (p.pid.isBlank()) continue                   // ex. AdBlue fără PID setat
                            if (!isParamShown(p, supported)) continue       // mașina nu are acest PID
                            // Viteza și turația se citesc la fiecare ciclu, restul la al 4-lea (afișaj mai rapid)
                            if (cycle % 4 != 0 && p.key !in HERO_KEYS && p.key !in fuelKeys) continue
                            attempted = true
                            val bytes = btManager.readPid(p.pid)
                            val v = bytes?.let { p.decode(it) }
                            values[p.key] = v?.let { "%.${p.decimals}f".format(it) }
                            if (v != null) raw[p.key] = v else raw.remove(p.key)
                            if (v != null) {
                                gotAny = true
                                misses[p.key] = 0
                                unavailable.remove(p.key)
                            }else{
                                val m = (misses[p.key] ?: 0) + 1
                                misses[p.key] = m
                                if (m >= 3) unavailable[p.key] = true
                            }
                        }

                        // Consumul: se recalculează la fiecare ciclu din ultimele valori citite
                        val lph = fuelSource?.let {
                            FuelMath.fuelRateLph(
                                it,
                                FuelInputs(
                                    fuelRateLph = raw["fuelrate"], mafGs = raw["maf"], mapKpa = raw["map"],
                                    iatC = raw["iat"], rpm = raw["rpm"], lambda = raw["lambda"]
                                ),
                                profile.diesel,
                                EngineConfig(displacementL = engineL)
                            )
                        }
                        fuel = fuelTrip.update(System.currentTimeMillis(), fuelSource, lph, raw["vss"])

                        if (attempted) {
                            failures = if (gotAny) 0 else failures + 1
                            if (failures >= 5) {
                                status = "Connection lost"
                                resetSession()
                                pollJob = null
                                btManager.closeConnection()
                                break
                            }
                        }
                        cycle++
                        delay(100)
                    }
                }
            }

            fun disconnect() {
                stopPolling()
                btManager.closeConnection()
                resetSession()
                status = "Disconnected"
            }

            fun connectToDevice(item: DeviceItem) {
                if (busy) return
                val adapter = btManager.bluetoothAdapter
                if (adapter == null) {
                    status = "The device does not have bluetooth"
                    return
                }
                if (!adapter.isEnabled) {
                    status = "Bluetooth is off"
                    toast(status, true)
                    return
                }

                stopPolling()
                btManager.closeConnection()
                resetSession()

                busy = true
                coroutineScope.launch {
                    try {
                        val device = adapter.getRemoteDevice(item.address)
                        status = "Connecting to ${item.name}..."

                        val isConnected = withContext(Dispatchers.IO) { btManager.connect(device) }
                        if (!isConnected) {
                            status = "Failed connecting to ${item.name}"
                            toast(status, true)
                            return@launch
                        }

                        status = "Initialising the adaptor..."
                        val atzResponse = btManager.initializeAdapter()

                        if (atzResponse.isNotBlank()) {
                            connectedDevice = item
                            status = "Connected to ${item.name} · $atzResponse"

                            // Prima comandă declanșează căutarea protocolului: o așteptăm
                            status = "Searching for the car's protocol..."
                            if (!btManager.warmUp()) {
                                btManager.closeConnection()
                                connectedDevice = null
                                status = "The car does not respond. Turn the ignition ON and connect again."
                                toast(status, true)
                                return@launch
                            }

                            // Detectăm automat dacă e hibrid / diesel
                            status = "Detecting the type of car..."
                            val detection = btManager.detectVehicle()
                            profile = detection.profile
                            profileKnown = true
                            supported = detection.supportedPids.takeIf { it.isNotEmpty() }?.toSet()
                            status = "Connected to ${item.name} · $atzResponse"

                            // Identificăm mașina: VIN din mașină, apoi marcă/model/generație
                            val vin = btManager.readVin()
                            if (vin != null) {
                                vehicle = VehicleCatalog.identify(vin, cachedVinInfo(vin))
                                startPolling()
                                // Decodarea online nu folosește Bluetooth, deci rulează în paralel cu citirea
                                coroutineScope.launch {
                                    val decoded = VinDecoder.decode(vin)
                                    if (decoded != null) {
                                        cacheVinInfo(vin, decoded)
                                        vehicle = VehicleCatalog.identify(vin, decoded)
                                    }
                                }
                            } else {
                                vehicle = null
                                startPolling()
                            }
                        } else {
                            btManager.closeConnection()   // altfel socket-ul rămâne deschis
                            status = "No response from the adapter (${item.name}). Is it a Bluetooth Classic ELM327?"
                            toast(status, true)
                        }
                    } catch (e: SecurityException) {
                        status = "The system blocked the bluetooth (permissions)"
                        toast(status)
                    } catch (e: Exception) {
                        Log.e("RevReport", "Internal error", e)
                        status = "Internal error: ${e.message}"
                        toast(status, true)
                    } finally {
                        busy = false
                    }
                }
            }


            fun connectToSimulator()
            {
                if (busy) return

                stopPolling()
                btManager.closeConnection()
                resetSession()

                busy = true

                coroutineScope.launch {
                    try
                    {
                        status = "Connecting to Python simulator..."

                        val isConnected = withContext(Dispatchers.IO)
                        {
                            //btManager.connectTcp("10.0.2.2", 35000)
                            btManager.connectTcp("127.0.0.1", 35000)
                        }

                        if (!isConnected)
                        {
                            status = "Cannot connect to Python simulator"
                            toast(status, true)
                            return@launch
                        }

                        status = "Initializing simulator..."

                        val atzResponse = btManager.initializeAdapter()

                        if (atzResponse.isBlank())
                        {
                            btManager.closeConnection()
                            status = "Simulator did not respond"
                            toast(status, true)
                            return@launch
                        }

                        connectedDevice = DeviceItem(
                            "Python Simulator",
                            "10.0.2.2"
                        )

                        status = "Checking simulated vehicle..."

                        if (!btManager.warmUp())
                        {
                            btManager.closeConnection()
                            connectedDevice = null
                            status = "Simulator did not respond to OBD commands"
                            toast(status, true)
                            return@launch
                        }

                        val detection = btManager.detectVehicle()

                        profile = detection.profile
                        profileKnown = true

                        supported = detection.supportedPids
                            .takeIf { it.isNotEmpty() }
                            ?.toSet()

                        vehicle = null

                        status = "Connected to Python Simulator"

                        startPolling()
                    }
                    catch (e: Exception)
                    {
                        btManager.closeConnection()
                        connectedDevice = null
                        status = "Simulator error: ${e.message}"
                        toast(status, true)
                    }
                    finally
                    {
                        busy = false
                    }
                }
            }


            fun openDevices() {
                val adapter = btManager.bluetoothAdapter
                if (adapter == null) {
                    status = "The device does not support bluetooth"
                    return
                }
                devices.clear()
                adapter.bondedDevices.orEmpty()
                    .map { DeviceItem(it.name ?: "Without name", it.address) }
                    .sortedBy { it.name.lowercase() }
                    .forEach { devices.add(it) }
                showDevices = true
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                if (permissions.values.all { it }) {
                    openDevices()
                } else {
                    status = "App needs Bluetooth permissions"
                    toast(status, true)
                }
            }

            fun onDevicesClick() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val connectGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                    val scanGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!connectGranted || !scanGranted) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                            )
                        )
                        return
                    }
                }
                openDevices()
            }

            /** Oprește citirea live cât rulează blocul, apoi o repornește. */
            suspend fun <T> withPollingPaused(block: suspend () -> T): T {
                val wasPolling = pollJob != null
                pollJob?.cancelAndJoin()
                pollJob = null
                try {
                    return block()
                } finally {
                    if (wasPolling && connectedDevice != null) startPolling()
                }
            }

            /** Citește VIN, combustibil și PID-urile suportate; apoi decodează VIN-ul online. */
            fun readVehicleInfo() {
                if (connectedDevice == null) {
                    toast("Firstly connect to a device", true)
                    return
                }
                if (infoLoading || faultsLoading) {
                    toast("Another reading is in progress")
                    return
                }
                infoLoading = true

                coroutineScope.launch {
                    try {
                        withPollingPaused {
                            info = VehicleInfoState(message = "Reading the VIN...")
                            val vin = btManager.readVin()

                            info = info.copy(vin = vin, message = "Detecting the type of car...")
                            val detection = btManager.detectVehicle()
                            profile = detection.profile
                            profileKnown = true
                            supported = detection.supportedPids.takeIf { it.isNotEmpty() }?.toSet()

                            info = info.copy(
                                fuelType = detection.fuelCode?.let { fuelTypeName(it) },
                                hybridDetected = detection.profile.hybrid,
                                supportedPids = detection.supportedPids,
                                message = if (vin != null) "Decoding the VIN online..." else "VIN is unavailable for this car"
                            )

                            if (vin != null) {
                                val decoded = VinDecoder.decode(vin)
                                if (decoded != null) cacheVinInfo(vin, decoded)
                                val usable = decoded ?: cachedVinInfo(vin)
                                vehicle = VehicleCatalog.identify(vin, usable)
                                info = info.copy(
                                    decoded = usable,
                                    message = if (decoded == null) "Online decoding failed (internet?)" else null
                                )
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("RevReport", "Error at reading information", e)
                        info = info.copy(message = "Error: ${e.message}")
                    } finally {
                        infoLoading = false
                    }
                }
            }

            suspend fun loadFaults(): DtcState {
                val mil = btManager.readMilStatus()
                val stored = btManager.readDtcs("03")
                val pending = btManager.readDtcs("07")
                val permanent = btManager.readDtcs("0A")
                val readiness = btManager.readReadiness()
                return DtcState(
                    stored = stored,
                    pending = pending,
                    permanent = permanent,
                    milOn = mil?.first,
                    dtcCount = mil?.second,
                    readiness = readiness,
                    message = if (stored == null && pending == null && permanent == null)
                        "No valid response from the vehicle." else null
                )
            }

            fun readFaults() {
                if (connectedDevice == null) {
                    toast("Firstly connect to a device", true)
                    return
                }
                if (faultsLoading || infoLoading) return
                faultsLoading = true

                coroutineScope.launch {
                    try {
                        withPollingPaused {
                            faults = DtcState(message = "Reading fault codes...")
                            faults = loadFaults()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("RevReport", "Error at reading fault codes", e)
                        faults = faults.copy(message = "Error: ${e.message}")
                    } finally {
                        faultsLoading = false
                    }
                }
            }

            fun clearFaults() {
                if (connectedDevice == null) {
                    toast("Firstly connect to a device", true)
                    return
                }
                if (faultsLoading || infoLoading) return
                faultsLoading = true

                coroutineScope.launch {
                    try {
                        withPollingPaused {
                            if (btManager.isStationary() == false) {
                                faults = faults.copy(
                                    message = "Cannot clear codes while the engine is running or the car is moving. " +
                                            "Stop, turn the engine OFF (ignition ON) and try again."
                                )
                                return@withPollingPaused
                            }
                            faults = faults.copy(message = "Clearing fault codes...")
                            val (ok, raw) = btManager.clearDtcs()
                            Log.d("RevReport", "Clear DTC response: $raw")

                            val fresh = loadFaults()
                            val remaining = fresh.stored.orEmpty() + fresh.pending.orEmpty() + fresh.permanent.orEmpty()
                            faults = fresh.copy(
                                message = when {
                                    ok && remaining.isEmpty() -> "Fault codes cleared."
                                    ok -> "Clear command accepted, but some codes are still reported " +
                                            "(permanent codes only disappear once the car confirms the fault is gone)."
                                    else -> "The vehicle did not confirm the clear command " +
                                            "(response: ${raw.ifBlank { "none" }}). " +
                                            "Turn the ignition ON with the engine OFF and try again."
                                }
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("RevReport", "Error at clearing fault codes", e)
                        faults = faults.copy(message = "Error: ${e.message}")
                    } finally {
                        faultsLoading = false
                    }
                }
            }

            if (showEngineDialog) {
                EngineDialog(
                    currentL = engineL,
                    onSave = {
                        engineL = it
                        prefs.edit().putFloat("engine_l", it.toFloat()).apply()
                        showEngineDialog = false
                    },
                    onDismiss = { showEngineDialog = false }
                )
            }

            BackHandler(enabled = screen != Screen.Params) { screen = Screen.Params }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Bg)
                    .windowInsetsPadding(
                        WindowInsets.displayCutout.union(WindowInsets.navigationBars)
                            .only(WindowInsetsSides.Horizontal)
                    )
            ) {
                when (screen) {
                    Screen.Params -> ParametersScreen(
                        vehicle = vehicle,
                        status = status,
                        params = params.filter { unavailable[it.key] != true },
                        values = values,
                        supported = supported,
                        connected = connectedDevice != null,
                        fuel = fuel,
                        diesel = profile.diesel,
                        engineL = engineL,
                        onResetTrip = { fuelTrip.reset(); fuel = fuelTrip.snapshot(fuel.source) },
                        onEditEngine = { showEngineDialog = true },
                        onDisconnect = { disconnect() },
                        onMenuClick = { showMainMenu = true }
                    )

                    Screen.Info -> InfoScreen(
                        vehicle = vehicle,
                        profile = profile,
                        profileKnown = profileKnown,
                        info = info,
                        connected = connectedDevice != null,
                        loading = infoLoading,
                        onRead = { readVehicleInfo() },
                        onMenuClick = { showMainMenu = true }
                    )

                    Screen.Faults -> FaultsScreen(
                        faults = faults,
                        connected = connectedDevice != null,
                        loading = faultsLoading,
                        onRead = { readFaults() },
                        onClear = { showClearConfirm = true },
                        onMenuClick = { showMainMenu = true }
                    )
                }
            }

            if (showMainMenu) {
                MainMenuDialog(
                    onParams = { showMainMenu = false; screen = Screen.Params },
                    onInfo = { showMainMenu = false; screen = Screen.Info },
                    onFaults = {
                        showMainMenu = false
                        screen = Screen.Faults
                        if (connectedDevice != null) readFaults()
                    },
                    onDevices = { showMainMenu = false; onDevicesClick() },
                    onSimulator = { showMainMenu = false; connectToSimulator() },
                    onDismiss = { showMainMenu = false }
                )
            }

            if (showClearConfirm) {
                ClearFaultsDialog(
                    onConfirm = { showClearConfirm = false; clearFaults() },
                    onDismiss = { showClearConfirm = false }
                )
            }

            if (showDevices) {
                DeviceDialog(
                    devices = devices,
                    connected = connectedDevice,
                    busy = busy,
                    onSelect = { showDevices = false; connectToDevice(it) },
                    onDisconnect = { showDevices = false; disconnect() },
                    onDismiss = { showDevices = false }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btManager.closeConnection()
    }
}

// ───────────────────────── Bara de sus ─────────────────────────

@Composable
private fun TopBar(title: String, onMenuClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Orange)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        HamburgerButton(
            onClick = onMenuClick,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun HamburgerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        val w = size.width
        val h = size.height
        listOf(0.15f, 0.5f, 0.85f).forEach { y ->
            drawLine(
                color = Color.White,
                start = Offset(0f, h * y),
                end = Offset(w, h * y),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }
    }
}

// ───────────────────────── Ecranul Parametri ─────────────────────────

@Composable
private fun ParametersScreen(
    vehicle: VehicleId?,
    status: String,
    params: List<ObdParameter>,
    values: Map<String, String?>,
    supported: Set<Int>?,
    connected: Boolean,
    fuel: FuelSnapshot,
    diesel: Boolean,
    engineL: Double,
    onResetTrip: () -> Unit,
    onEditEngine: () -> Unit,
    onDisconnect: () -> Unit,
    onMenuClick: () -> Unit
) {
    // Pe ecran lat (landscape / tabletă) încap mai multe plăci pe rând
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val tileColumns = if (wide) 5 else 3
    val barColumns = if (wide) 3 else 2

    val visible = params.filter { isParamShown(it, supported) }
    val byKey = visible.associateBy { it.key }
    val hero = HERO_KEYS.mapNotNull { byKey[it] }
    val temps = TEMP_KEYS.mapNotNull { byKey[it] }
    val levels = LEVEL_KEYS.mapNotNull { byKey[it] }
    val rest = visible.filter { it.key !in HERO_KEYS && it.key !in TEMP_KEYS && it.key !in LEVEL_KEYS }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        TopBar("Parameters", onMenuClick)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                vehicle?.let {
                    Text(
                        text = it.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(text = status, color = TextDim, fontSize = 12.sp)
            }
            if (connected) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    Text("Disconnect", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hero.isNotEmpty()) {
                TileGrid(hero, columns = 2) { p, m ->
                    StatTile(m, shortLabel(p), values[p.key], p.unit, valueColor(p.key, values[p.key]), big = true)
                }
            }
            if (connected) {
                SectionLabel("Fuel consumption")
                FuelCard(fuel, diesel, engineL, onResetTrip, onEditEngine)
            }
            if (temps.isNotEmpty()) {
                SectionLabel("Temperatures")
                TileGrid(temps, tileColumns) { p, m ->
                    StatTile(m, shortLabel(p), values[p.key], p.unit, valueColor(p.key, values[p.key]), big = false)
                }
            }
            if (levels.isNotEmpty()) {
                SectionLabel("Levels & load")
                TileGrid(levels, barColumns) { p, m ->
                    LevelBar(m, shortLabel(p), values[p.key], p.unit, p.key)
                }
            }
            if (rest.isNotEmpty()) {
                SectionLabel("Engine & electrical")
                TileGrid(rest, tileColumns) { p, m ->
                    StatTile(m, shortLabel(p), values[p.key], p.unit, valueColor(p.key, values[p.key]), big = false)
                }

            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// Ce parametru merge în ce grup
private val HERO_KEYS = listOf("vss", "rpm")
private val TEMP_KEYS = listOf("ect", "oilt", "cat", "iat", "aat", "phoneTemp")
private val LEVEL_KEYS = listOf("fuel", "hvsoc", "adblue", "absload","iceeffort", "tprel", "pedal")

private val OkGreen = Color(0xFF4CAF50)
private val WarnYellow = Color(0xFFFFD600)
private val WarnOrange = Color(0xFFFFA000)
private val ColdBlue = Color(0xFF42A5F5)
private val BadRed = Color(0xFFE53935)

/** Parametrul apare doar dacă mașina îl suportă (când știm lista) și are PID setat. */
private fun isParamShown(p: ObdParameter, supported: Set<Int>?): Boolean {
    if (p.pid.isBlank()) return p.key == "phoneTemp"
    if (supported == null || !p.pid.startsWith("01")) return true
    val n = p.pid.substring(2).toIntOrNull(16) ?: return true
    return n in supported
}

private fun shortLabel(p: ObdParameter): String = when (p.key) {
    "iceeffort" -> "Engine effort"
    "absload" -> "Engine load"
    "hvsoc" -> "Hybrid battery"
    "phoneTemp" -> "Phone"
    "vss" -> "Speed"
    "rpm" -> "Engine RPM"
    "ect" -> "Coolant"
    "oilt" -> "Oil"
    "cat" -> "Catalyst"
    "iat" -> "Intake air"
    "aat" -> "Outside"
    "fuel" -> "Fuel"
    "hvbat" -> "Hybrid battery"
    "adblue" -> "AdBlue"
    "load" -> "Air flow ratio"
    "tp" -> "Throttle"
    "maf" -> "Air flow"
    "fuelrate" -> "Fuel rate"
    "lambda" -> "Lambda"
    "map" -> "Manifold"
    "spark" -> "Ignition"
    "vpwr" -> "12 V battery"
    else -> p.name
}

/** Culoarea valorii după prag. Pragurile sunt orientative; le poți ajusta aici. */
private fun valueColor(key: String, raw: String?): Color {
    val v = raw?.replace(',', '.')?.toDoubleOrNull() ?: return TextMain
    return when (key) {
        "phoneTemp" -> when {
            v < 40 -> OkGreen
            v < 45 -> WarnOrange
            else -> BadRed
        }
        "tprel" -> when {
            v < 50 -> OkGreen
            v <= 75 -> WarnOrange
            else -> BadRed
        }
        "tp" -> when {
            v < 50 -> OkGreen
            v <= 75 -> WarnOrange
            else -> BadRed
        }
        "absload" -> when {
            v < 50 -> OkGreen
            v <= 75 -> WarnOrange
            else -> BadRed
        }
        "iceeffort" -> when {
            v < 50 -> OkGreen
            v <= 75 -> WarnOrange
            else -> BadRed
        }
        "ect" -> when {
            v < 60 -> ColdBlue
            v <= 105 -> OkGreen
            v <= 115 -> WarnOrange
            else -> BadRed
        }
        "oilt" -> when {
            v < 60 -> ColdBlue
            v <= 120 -> OkGreen
            v <= 135 -> WarnOrange
            else -> BadRed
        }
        "iat" -> when {
            v <= 60 -> TextMain
            v <= 80 -> WarnOrange
            else -> BadRed
        }
        "fuel", "adblue" -> when {
            v < 15 -> BadRed
            v < 30 -> WarnOrange
            else -> OkGreen
        }
        "hvbat", "hvsoc" -> when {
            v < 15 -> BadRed
            v < 30 -> WarnOrange
            else -> OkGreen
        }
        "cat" -> when {
            v < 400 -> ColdBlue
            v < 870 -> OkGreen
            else -> WarnOrange
        }
        "vpwr" -> if (v in 11.8..15.2) OkGreen else BadRed
        else -> TextMain
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextDim,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** Aranjează elementele în rânduri de câte [columns], toate cu aceeași lățime. */
@Composable
private fun <T> TileGrid(
    items: List<T>,
    columns: Int,
    tile: @Composable (T, Modifier) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item -> tile(item, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Placă cu etichetă mică sus și valoare mare. [big] = placa mare (viteză, turație). */
@Composable
private fun StatTile(
    modifier: Modifier,
    label: String,
    value: String?,
    unit: String,
    color: Color,
    big: Boolean
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Card)
            .padding(horizontal = 10.dp, vertical = if (big) 10.dp else 8.dp)
    ) {
        Text(label, color = TextDim, fontSize = if (big) 13.sp else 11.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value ?: "–",
                color = if (value == null) TextDim else color,
                fontSize = if (big) 48.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (value != null && unit.isNotBlank()) {
                Text(
                    text = unit,
                    color = TextDim,
                    fontSize = if (big) 16.sp else 12.sp,
                    modifier = Modifier.padding(start = 3.dp, bottom = if (big) 8.dp else 4.dp)
                )
            }
        }
    }
}

/** Nivel (0–100%) ca bară: combustibil, baterie hibrid, AdBlue, sarcină, accelerație. */
@Composable
private fun LevelBar(
    modifier: Modifier,
    label: String,
    value: String?,
    unit: String,
    key: String
) {
    val number = value?.replace(',', '.')?.toDoubleOrNull()
    val fraction = ((number ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val c = valueColor(key, value)
    val barColor = if (c == TextMain) Orange else c

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Card)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                text = if (value == null) "–" else "$value$unit",
                color = if (value == null) TextDim else c,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF2E2E2E))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(barColor)
            )
        }
    }
}

// ───────────────────────── Ecranul Info vehicul ─────────────────────────

@Composable
private fun InfoScreen(
    vehicle: VehicleId?,
    profile: VehicleProfile,
    profileKnown: Boolean,
    info: VehicleInfoState,
    connected: Boolean,
    loading: Boolean,
    onRead: () -> Unit,
    onMenuClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        TopBar("Vehicle info", onMenuClick)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Tipul vehiculului, detectat automat
            SectionCard("Type of vehicle (automatically detected)") {
                InfoLine(
                    "Engine fuel type",
                    if (profileKnown) (if (profile.diesel) "Diesel" else "Gas / other") else null
                )
                InfoLine(
                    "Hybrid",
                    if (profileKnown) (if (profile.hybrid) "yes" else "no") else null
                )
                Spacer(Modifier.height(4.dp))
                val extra = buildList {
                    if (profile.hybrid) add("Hybrid Battery level")
                    if (profile.diesel) add("AdBlue level")
                }
                Text(
                    text = when {
                        !profileKnown -> "Detected on connection."
                        extra.isEmpty() -> "In parameters are shown values."
                        else -> "In parameters is added: ${extra.joinToString(" și ")}."
                    },
                    color = TextDim,
                    fontSize = 12.sp
                )
            }

            // 2. Citit din mașină
            SectionCard("Read from the car") {
                Button(
                    onClick = onRead,
                    enabled = connected && !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "It is read..." else "Read the informations", color = Color.White)
                }
                if (!connected) {
                    Text(
                        "Firstly connect to a device.",
                        color = TextDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                info.message?.let {
                    Text(it, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }

                Spacer(Modifier.height(8.dp))
                InfoLine("VIN", info.vin)
                InfoLine("Make", info.decoded?.make)
                InfoLine("Model", info.decoded?.model)
                InfoLine("Year", info.decoded?.year ?: vehicle?.year?.toString())
                InfoLine(
                    "Gen",
                    vehicle?.generation?.let { if (vehicle?.approx == true) "$it (estimat)" else it }
                )
                InfoLine("Cylinders", info.decoded?.cylinders ?: if (info.vin != null && info.message == null) "unknown" else null)
                InfoLine("Fuel type", info.fuelType)
                InfoLine(
                    "Hybrid (detected)",
                    info.hybridDetected?.let { if (it) "yes" else "no" }
                )

            }

            // 3. PID-uri suportate
            info.supportedPids?.let { pids ->
                SectionCard("Supported PIDs (${pids.size})") {
                    Text(
                        text = if (pids.isEmpty()) "No device detected." else pids.joinToString(" ") { "%02X".format(it) },
                        color = TextMain,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextDim, fontSize = 14.sp)
        Text(
            text = value ?: "-",
            color = TextMain,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}

// ───────────────────────── Dialoguri ─────────────────────────

@Composable
private fun MainMenuDialog(
    onParams: () -> Unit,
    onInfo: () -> Unit,
    onFaults: () -> Unit,
    onDevices: () -> Unit,
    onSimulator: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Menu", color = Color.White) },
        text = {
            Column {
                MenuItem("Parameters", onParams)
                HorizontalDivider(color = Color(0xFF2A2A2A))
                MenuItem("Bluetooth Device", onDevices)
                ///
                HorizontalDivider(color = Color(0xFF2A2A2A))
                MenuItem("Python Simulator", onSimulator)
                ///
                HorizontalDivider(color = Color(0xFF2A2A2A))
                MenuItem("Vehicle Information", onInfo)
                HorizontalDivider(color = Color(0xFF2A2A2A))
                MenuItem("Trouble Codes", onFaults)
                HorizontalDivider(color = Color(0xFF2A2A2A))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        }
    )
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    )
}

@Composable
private fun DeviceDialog(
    devices: List<DeviceItem>,
    connected: DeviceItem?,
    busy: Boolean,
    onSelect: (DeviceItem) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Choose a device", color = Color.White) },
        text = {
            if (devices.isEmpty()) {
                Text(
                    "No device connected. Connect the device from settings -> bluetooth, then come back.",
                    color = TextDim
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(devices, key = { it.address }) { d ->
                        val isCurrent = connected?.address == d.address
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { onSelect(d) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = if (isCurrent) "${d.name}  ✓" else d.name,
                                color = if (isCurrent) Orange else Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(d.address, color = TextDim, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Color(0xFF2A2A2A))
                    }
                }
            }
        },
        confirmButton = {
            if (connected != null) {
                TextButton(onClick = onDisconnect) { Text("Disconnect", color = Orange) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        }
    )
}

// ───────────────────────── Ecranul Fault Codes ─────────────────────────

@Composable
private fun FaultsScreen(
    faults: DtcState,
    connected: Boolean,
    loading: Boolean,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onMenuClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        TopBar("Trouble codes", onMenuClick)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("Vehicle status") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onRead,
                        enabled = connected && !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (loading) "Working..." else "Read codes", color = Color.White)
                    }
                    Button(
                        onClick = onClear,
                        enabled = connected && !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear codes", color = Red)
                    }
                }

                if (!connected) {
                    Text(
                        "Firstly connect to a device (menu -> Bluetooth Device).",
                        color = TextDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                faults.message?.let {
                    Text(it, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }

                Spacer(Modifier.height(8.dp))
                InfoLine(
                    "Check engine light",
                    faults.milOn?.let { if (it) "ON" else "OFF" }
                )
                InfoLine("Codes reported by ECU", faults.dtcCount?.toString())
            }

            DtcSection("Stored codes", faults.stored)
            DtcSection("Pending codes", faults.pending)
            DtcSection("Permanent codes", faults.permanent)
            ReadinessSection(faults.readiness)
        }
    }
}

@Composable
private fun ReadinessSection(readiness: Readiness?) {
    SectionCard("Readiness monitors") {
        when {
            readiness == null -> Text("-", color = TextDim, fontSize = 14.sp)
            readiness.monitors.isEmpty() ->
                Text("The car does not report any readiness monitors.", color = TextDim, fontSize = 14.sp)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val total = readiness.monitors.size
                val done = total - readiness.notReady.size
                Text(
                    if (readiness.allReady) "All $total monitors ready" else "$done of $total monitors ready",
                    color = if (readiness.allReady) Color(0xFF4CAF50) else Color(0xFFFFA000),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                readiness.monitors.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(m.name, color = TextMain, fontSize = 14.sp)
                        Text(
                            if (m.ready) "Ready" else "Not ready",
                            color = if (m.ready) Color(0xFF4CAF50) else Color(0xFFFFA000),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
                if (!readiness.allReady) {
                    Text(
                        "Monitors that are not ready usually mean the codes were just cleared or the battery " +
                                "was disconnected. The car needs to be driven for a while to finish them.",
                        color = TextDim,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DtcSection(title: String, codes: List<String>?) {
    SectionCard(title) {
        when {
            codes == null -> Text("-", color = TextDim, fontSize = 14.sp)
            codes.isEmpty() -> Text("No codes", color = TextDim, fontSize = 14.sp)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                codes.forEach { DtcRow(it) }
            }
        }
    }
}

@Composable
private fun DtcRow(code: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Orange)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(code, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Text(
            text = DtcDescriptions.describe(code),
            color = TextMain,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
    }
}

@Composable
private fun ClearFaultsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Clear trouble codes?", color = Color.White) },
        text = {
            Text(
                "This erases the stored codes and freeze-frame data and turns off the check-engine light. " +
                        "The fault itself is not repaired and the code returns if it is still present. " +
                        "It also resets the readiness monitors to \"not ready\" until the car has been driven for a while, " +
                        "so do not clear codes right before an inspection. " +
                        "Note the codes down first. Use with the ignition ON and the engine OFF.",
                color = TextDim
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Clear", color = Orange) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
        }
    )
}

// ───────────────────────── Consum ─────────────────────────

private fun fmt1(v: Double?): String? = v?.let { "%.1f".format(it) }

/** Consum instantaneu, media pe cursă și totalurile cursei. */
@Composable
private fun FuelCard(
    fuel: FuelSnapshot,
    diesel: Boolean,
    engineL: Double,
    onResetTrip: () -> Unit,
    onEditEngine: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Card)
            .padding(10.dp)
    ) {
        if (fuel.source == null) {
            Text(
                if (diesel)
                    "Consumption can't be calculated: this diesel reports neither fuel rate (PID 5E) nor lambda (PID 24) with air flow."
                else
                    "Consumption can't be calculated: the car reports neither fuel rate, air flow (MAF) nor manifold pressure (MAP).",
                color = TextDim,
                fontSize = 12.sp
            )
            return@Column
        }

        // Când mașina stă, L/100 km nu are sens: arătăm L/h
        val stopped = fuel.lPer100Km == null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                Modifier.weight(1f),
                "Now",
                fmt1(if (stopped) fuel.lPerHour else fuel.lPer100Km),
                if (stopped) "L/h" else "L/100km",
                TextMain,
                big = false
            )
            StatTile(Modifier.weight(1f), "Trip average", fmt1(fuel.avgLPer100Km), "L/100km", TextMain, big = false)
            StatTile(
                Modifier.weight(1f),
                "Trip",
                "%.1f".format(fuel.tripKm),
                "km · ${"%.2f".format(fuel.tripLiters)} L",
                TextMain,
                big = false
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val method = when (fuel.source) {
                FuelSource.FUEL_RATE -> "Source: car fuel rate (accurate)"
                FuelSource.MAF -> "Source: air flow (MAF), estimate"
                else -> "Source: manifold pressure, rough estimate"
            }
            Text(method, color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (fuel.source == FuelSource.MAP) {
                TextButton(onClick = onEditEngine) {
                    Text("Engine ${"%.1f".format(engineL)} L", color = Orange, fontSize = 12.sp)
                }
            }
            TextButton(onClick = onResetTrip) { Text("Reset trip", color = Orange, fontSize = 12.sp) }
        }
    }
}

/** Capacitatea motorului, necesară doar pentru estimarea din MAP/RPM. */
@Composable
private fun EngineDialog(currentL: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("%.1f".format(currentL)) }
    val value = text.replace(',', '.').toDoubleOrNull()
    val valid = value != null && value in 0.5..8.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Engine displacement", color = Color.White) },
        text = {
            Column {
                Text(
                    "This car doesn't report air flow, so consumption is estimated from manifold " +
                            "pressure and RPM. Enter the engine size in litres (e.g. 1.6).",
                    color = TextDim,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !valid,
                    suffix = { Text("L", color = TextDim) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(value!!) }) { Text("Save", color = Orange) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim) }
        }
    )
}
