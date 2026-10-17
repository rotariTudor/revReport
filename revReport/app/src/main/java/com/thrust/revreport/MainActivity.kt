package com.thrust.revreport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {

    private lateinit var btManager: revReportBluetoothManagement

    // Numele laptopului. Dacă vrei, pune în loc adresa MAC (mai sigur), ex. "AA:BB:CC:DD:EE:FF"
    private val targetName = "DESKTOP-I6T331R"
    private val targetMac: String? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        btManager = revReportBluetoothManagement(bluetoothManager?.adapter)

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            var status by remember { mutableStateOf("Neconectat") }
            var busy by remember { mutableStateOf(false) }
            var rpm by remember { mutableStateOf<Int?>(null) }
            var speed by remember { mutableStateOf<Int?>(null) }
            var temp by remember { mutableStateOf<Int?>(null) }
            var pollJob by remember { mutableStateOf<Job?>(null) }

            fun stopPolling() {
                pollJob?.cancel()
                pollJob = null
            }

            fun startPolling() {
                stopPolling()
                pollJob = coroutineScope.launch {
                    var failures = 0
                    while (isActive) {
                        rpm = btManager.readRpm()
                        speed = btManager.readSpeed()
                        temp = btManager.readCoolantTemp()

                        // Dacă nu vine nimic de 5 ori la rând, conexiunea probabil a căzut
                        if (rpm == null && speed == null && temp == null) failures++ else failures = 0
                        if (failures >= 5) {
                            status = "Conexiunea s-a pierdut"
                            btManager.closeConnection()
                            break
                        }
                        delay(300)
                    }
                }
            }

            fun toast(msg: String, long: Boolean = false) {
                Toast.makeText(context, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }

            val connectToObd: () -> Unit = connect@{
                if (busy) return@connect
                val adapter = btManager.bluetoothAdapter
                if (adapter == null) {
                    status = "Telefonul nu are Bluetooth"
                    return@connect
                }
                if (!adapter.isEnabled) {
                    status = "Bluetooth-ul este oprit. Pornește-l și încearcă din nou."
                    toast(status, true)
                    return@connect
                }

                busy = true
                coroutineScope.launch {
                    try {
                        val pairedDevices = adapter.bondedDevices.orEmpty()
                        pairedDevices.forEach {
                            Log.d("RevReport", "Paired: ${it.name} / ${it.address}")
                        }

                        val laptop: BluetoothDevice? = pairedDevices.find {
                            (targetMac != null && it.address.equals(targetMac, ignoreCase = true)) ||
                                    it.name?.equals(targetName, ignoreCase = true) == true
                        }

                        if (laptop == null) {
                            status = "Laptopul NU este asociat (vezi Logcat: RevReport)"
                            toast(status, true)
                            return@launch
                        }

                        status = "Mă conectez la ${laptop.name}..."

                        // Conectarea blochează, deci o rulăm pe un fir de fundal
                        val isConnected = withContext(Dispatchers.IO) {
                            btManager.connect(laptop)
                        }

                        if (!isConnected) {
                            status = "Eșec la deschiderea portului pe PC"
                            toast(status, true)
                            return@launch
                        }

                        status = "Conectat. Inițializez adaptorul..."
                        val atzResponse = btManager.initializeAdapter()

                        status = if (atzResponse.isNotBlank()) {
                            "Inițializat. Răspuns: $atzResponse"
                        } else {
                            "Conectat, dar emulatorul nu a răspuns la comenzi"
                        }
                        toast(status, true)
                        if (atzResponse.isNotBlank()) startPolling()
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

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                if (permissions.values.all { it }) {
                    connectToObd()
                } else {
                    status = "Aplicația are nevoie de permisiuni Bluetooth"
                    toast(status, true)
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Button(
                    enabled = !busy,
                    onClick = {
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
                                return@Button
                            }
                        }
                        connectToObd()
                    }
                ) {
                    Text("Conectează la PC (Emulator)")
                }

                Text(text = status, modifier = Modifier.padding(top = 16.dp))
                Text("RPM: ${rpm ?: "-"}", modifier = Modifier.padding(top = 24.dp))
                Text("Viteză: ${speed?.let { "$it km/h" } ?: "-"}")
                Text("Temp. motor: ${temp?.let { "$it °C" } ?: "-"}")

                Button(
                    onClick = { stopPolling(); btManager.closeConnection(); status = "Deconectat" },
                    modifier = Modifier.padding(top = 16.dp)
                ) { Text("Deconectează") }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btManager.closeConnection()
    }
}