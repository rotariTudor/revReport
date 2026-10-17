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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Red = Color(0xFFD32F2F)
private val Bg = Color(0xFF0E0E0E)
private val Card = Color(0xFF1C1C1C)
private val TextMain = Color.White
private val TextDim = Color(0xFFB0B0B0)

/** Un dispozitiv asociat, pregătit pentru afișare în meniu. */
data class DeviceItem(val name: String, val address: String)

enum class Screen { Params, Info }

/** Ce am citit din mașină pe ecranul „Info vehicul". */
data class VehicleInfoState(
    val vin: String? = null,
    val fuelType: String? = null,
    val hybridDetected: Boolean? = null,
    val supportedPids: List<Int>? = null,
    val decoded: VinInfo? = null,
    val message: String? = null
)

fun fuelTypeName(code: Int): String = when (code) {
    0 -> "Indisponibil"
    1 -> "Benzină"
    2 -> "Metanol"
    3 -> "Etanol"
    4 -> "Motorină"
    5 -> "GPL"
    6 -> "GNC"
    7 -> "Propan"
    8 -> "Electric"
    in 9..16 -> "Bi-fuel (cod $code)"
    17 -> "Hibrid benzină"
    18 -> "Hibrid etanol"
    19 -> "Hibrid motorină"
    20 -> "Hibrid electric"
    21 -> "Hibrid (electric + termic)"
    22 -> "Hibrid cu recuperare"
    23 -> "Bi-fuel motorină"
    else -> "Necunoscut (cod $code)"
}

class MainActivity : ComponentActivity() {

    private lateinit var btManager: revReportBluetoothManagement

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        btManager = revReportBluetoothManagement(bluetoothManager?.adapter)
        val prefs = getSharedPreferences("revreport", MODE_PRIVATE)

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            var status by remember { mutableStateOf("Neconectat") }
            var busy by remember { mutableStateOf(false) }
            var connectedDevice by remember { mutableStateOf<DeviceItem?>(null) }
            var pollJob by remember { mutableStateOf<Job?>(null) }
            var showDevices by remember { mutableStateOf(false) }
            var showMainMenu by remember { mutableStateOf(false) }
            var screen by remember { mutableStateOf(Screen.Params) }
            var profile by remember { mutableStateOf(VehicleProfile()) }
            var profileKnown by remember { mutableStateOf(false) }
            var info by remember { mutableStateOf(VehicleInfoState()) }
            var infoLoading by remember { mutableStateOf(false) }
            var vehicle by remember { mutableStateOf<VehicleId?>(null) }
            val devices = remember { mutableStateListOf<DeviceItem>() }
            val values = remember { mutableStateMapOf<String, String?>() }

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

            fun startPolling() {
                stopPolling()
                pollJob = coroutineScope.launch {
                    var failures = 0
                    while (isActive) {
                        var gotAny = false
                        for (p in parametersFor(profile)) {
                            if (!isActive) break
                            if (p.pid.isBlank()) continue // ex. AdBlue fără PID setat
                            val bytes = btManager.readPid(p.pid)
                            val v = bytes?.let { p.decode(it) }
                            values[p.key] = v?.let { "%.${p.decimals}f".format(it) }
                            if (v != null) gotAny = true
                        }

                        failures = if (gotAny) 0 else failures + 1
                        if (failures >= 3) {
                            status = "Conexiunea s-a pierdut"
                            connectedDevice = null
                            vehicle = null
                            profile = VehicleProfile()
                            profileKnown = false
                            btManager.closeConnection()
                            break
                        }
                        delay(100)
                    }
                }
            }

            fun disconnect() {
                stopPolling()
                btManager.closeConnection()
                connectedDevice = null
                vehicle = null
                profile = VehicleProfile()
                profileKnown = false
                values.clear()
                status = "Deconectat"
            }

            fun connectToDevice(item: DeviceItem) {
                if (busy) return
                val adapter = btManager.bluetoothAdapter
                if (adapter == null) {
                    status = "Telefonul nu are Bluetooth"
                    return
                }
                if (!adapter.isEnabled) {
                    status = "Bluetooth-ul este oprit"
                    toast(status, true)
                    return
                }

                stopPolling()
                btManager.closeConnection()
                connectedDevice = null
                vehicle = null
                profile = VehicleProfile()
                profileKnown = false
                values.clear()

                busy = true
                coroutineScope.launch {
                    try {
                        val device = adapter.getRemoteDevice(item.address)
                        status = "Mă conectez la ${item.name}..."

                        val isConnected = withContext(Dispatchers.IO) { btManager.connect(device) }
                        if (!isConnected) {
                            status = "Eșec la conectarea cu ${item.name}"
                            toast(status, true)
                            return@launch
                        }

                        status = "Inițializez adaptorul..."
                        val atzResponse = btManager.initializeAdapter()

                        if (atzResponse.isNotBlank()) {
                            connectedDevice = item
                            status = "Conectat la ${item.name} · $atzResponse"

                            // Detectăm automat dacă e hibrid / diesel
                            status = "Detectez tipul mașinii..."
                            val detection = btManager.detectVehicle()
                            profile = detection.profile
                            profileKnown = true
                            status = "Conectat la ${item.name} · $atzResponse"

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
                            status = "Conectat la ${item.name}, dar fără răspuns la comenzi"
                            toast(status, true)
                        }
                    } catch (e: SecurityException) {
                        status = "Sistemul a blocat Bluetooth-ul (permisiuni)"
                        toast(status)
                    } catch (e: Exception) {
                        Log.e("RevReport", "Eroare internă", e)
                        status = "Eroare internă: ${e.message}"
                        toast(status, true)
                    } finally {
                        busy = false
                    }
                }
            }

            fun openDevices() {
                val adapter = btManager.bluetoothAdapter
                if (adapter == null) {
                    status = "Telefonul nu are Bluetooth"
                    return
                }
                devices.clear()
                adapter.bondedDevices.orEmpty()
                    .map { DeviceItem(it.name ?: "Fără nume", it.address) }
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
                    status = "Aplicația are nevoie de permisiuni Bluetooth"
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

            /** Citește VIN, combustibil și PID-urile suportate; apoi decodează VIN-ul online. */
            fun readVehicleInfo() {
                if (connectedDevice == null) {
                    toast("Conectează-te mai întâi la un dispozitiv", true)
                    return
                }
                if (infoLoading) return
                infoLoading = true

                coroutineScope.launch {
                    val wasPolling = pollJob != null
                    pollJob?.cancelAndJoin()   // nu trimitem două comenzi în același timp
                    pollJob = null
                    try {
                        info = VehicleInfoState(message = "Citesc VIN-ul...")
                        val vin = btManager.readVin()

                        info = info.copy(vin = vin, message = "Detectez tipul mașinii...")
                        val detection = btManager.detectVehicle()
                        profile = detection.profile
                        profileKnown = true

                        info = info.copy(
                            fuelType = detection.fuelCode?.let { fuelTypeName(it) },
                            hybridDetected = detection.profile.hybrid,
                            supportedPids = detection.supportedPids,
                            message = if (vin != null) "Decodez VIN-ul online..." else "VIN indisponibil la această mașină"
                        )

                        if (vin != null) {
                            val decoded = VinDecoder.decode(vin)
                            if (decoded != null) cacheVinInfo(vin, decoded)
                            val usable = decoded ?: cachedVinInfo(vin)
                            vehicle = VehicleCatalog.identify(vin, usable)
                            info = info.copy(
                                decoded = usable,
                                message = if (decoded == null) "Decodarea online a eșuat (internet?)" else null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("RevReport", "Eroare la citirea informațiilor", e)
                        info = info.copy(message = "Eroare: ${e.message}")
                    } finally {
                        infoLoading = false
                        if (wasPolling && connectedDevice != null) startPolling()
                    }
                }
            }

            BackHandler(enabled = screen == Screen.Info) { screen = Screen.Params }

            when (screen) {
                Screen.Params -> ParametersScreen(
                    vehicle = vehicle,
                    status = status,
                    params = params,
                    values = values,
                    connected = connectedDevice != null,
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
            }

            if (showMainMenu) {
                MainMenuDialog(
                    onParams = { showMainMenu = false; screen = Screen.Params },
                    onInfo = { showMainMenu = false; screen = Screen.Info },
                    onDevices = { showMainMenu = false; onDevicesClick() },
                    onDismiss = { showMainMenu = false }
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
            .background(Red)
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
    connected: Boolean,
    onDisconnect: () -> Unit,
    onMenuClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        TopBar("Parametri", onMenuClick)

        vehicle?.let {
            Text(
                text = it.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = status,
                color = TextDim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (connected) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    Text("Deconectează", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            items(params, key = { it.key }) { p ->
                ParameterRow(p, values[p.key])
                HorizontalDivider(color = Color(0xFF2A2A2A))
            }
        }
    }
}

@Composable
private fun ParameterRow(p: ObdParameter, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 58.dp, height = 46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Red),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = p.badge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (p.badge.length > 4) 11.sp else 15.sp,
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(p.name, color = TextDim, fontSize = 13.sp)

            if (p.pid.isBlank()) {
                Text("PID nesetat (vezi ADBLUE_PID)", color = TextDim, fontSize = 14.sp)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value ?: "-",
                        color = TextMain,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (value != null) {
                        Text(
                            text = " ${p.unit}",
                            color = TextMain,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
            }
        }

        ChartIcon()
    }
}

@Composable
private fun ChartIcon() {
    Canvas(modifier = Modifier.size(38.dp)) {
        val w = size.width
        val h = size.height
        val line = Color.White

        drawRoundRect(
            color = line,
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = 2f)
        )

        val pts = listOf(
            Offset(w * 0.18f, h * 0.72f),
            Offset(w * 0.40f, h * 0.45f),
            Offset(w * 0.60f, h * 0.60f),
            Offset(w * 0.82f, h * 0.25f)
        )
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color = line, style = Stroke(width = 2f))
        pts.forEach { drawCircle(color = line, radius = 3f, center = it) }
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
        TopBar("Info vehicul", onMenuClick)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Tipul vehiculului, detectat automat
            SectionCard("Tip vehicul (detectat automat)") {
                InfoLine(
                    "Motorizare",
                    if (profileKnown) (if (profile.diesel) "Motorină" else "Benzină / altul") else null
                )
                InfoLine(
                    "Hibrid",
                    if (profileKnown) (if (profile.hybrid) "da" else "nu") else null
                )
                Spacer(Modifier.height(4.dp))
                val extra = buildList {
                    if (profile.hybrid) add("nivelul bateriei hibride")
                    if (profile.diesel) add("nivelul de AdBlue")
                }
                Text(
                    text = when {
                        !profileKnown -> "Se detectează la conectare."
                        extra.isEmpty() -> "Pe ecranul Parametri se afișează valorile de bază."
                        else -> "Pe ecranul Parametri se adaugă: ${extra.joinToString(" și ")}."
                    },
                    color = TextDim,
                    fontSize = 12.sp
                )
            }

            // 2. Citit din mașină
            SectionCard("Citit din mașină") {
                Button(
                    onClick = onRead,
                    enabled = connected && !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "Se citește..." else "Citește informațiile", color = Color.White)
                }
                if (!connected) {
                    Text(
                        "Conectează-te mai întâi la un dispozitiv (meniu → Dispozitiv Bluetooth).",
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
                InfoLine("Marcă", info.decoded?.make)
                InfoLine("Model", info.decoded?.model)
                InfoLine("An", info.decoded?.year ?: vehicle?.year?.toString())
                InfoLine(
                    "Generație",
                    vehicle?.generation?.let { if (vehicle?.approx == true) "$it (estimat)" else it }
                )
                InfoLine("Cilindri", info.decoded?.cylinders ?: if (info.vin != null && info.message == null) "necunoscut" else null)
                InfoLine("Combustibil (din mașină)", info.fuelType)
                InfoLine(
                    "Hibrid (detectat)",
                    info.hybridDetected?.let { if (it) "da" else "nu" }
                )

            }

            // 3. PID-uri suportate
            info.supportedPids?.let { pids ->
                SectionCard("PID-uri suportate (${pids.size})") {
                    Text(
                        text = if (pids.isEmpty()) "Niciunul detectat" else pids.joinToString(" ") { "%02X".format(it) },
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
    onDevices: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Meniu", color = Color.White) },
        text = {
            Column {
                MenuItem("Parametri", onParams)
                HorizontalDivider(color = Color(0xFF2A2A2A))
                MenuItem("Info vehicul", onInfo)
                HorizontalDivider(color = Color(0xFF2A2A2A))
                MenuItem("Dispozitiv Bluetooth", onDevices)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Închide", color = Color.White) }
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
        title = { Text("Alege dispozitivul", color = Color.White) },
        text = {
            if (devices.isEmpty()) {
                Text(
                    "Niciun dispozitiv asociat. Asociază adaptorul din Setări → Bluetooth, apoi revino aici.",
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
                                color = if (isCurrent) Red else Color.White,
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
                TextButton(onClick = onDisconnect) { Text("Deconectează", color = Red) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Închide", color = Color.White) }
        }
    )
}