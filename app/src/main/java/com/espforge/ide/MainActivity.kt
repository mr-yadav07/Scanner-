package com.espforge.ide

import android.app.PendingIntent
import android.app.ActivityManager
import android.content.*
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import dev.espflasher.app.domain.ChipId
import dev.espflasher.app.domain.EspFlasher
import dev.espflasher.app.domain.FirmwareImage
import dev.espflasher.app.domain.FlashProgress
import dev.espflasher.app.flash.Esp32Flasher
import dev.espflasher.app.flash.Esp8266Flasher
import dev.espflasher.app.usb.UsbSerialTransport
import com.espforge.ide.upload.Stk500v1Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private const val USB_PERMISSION_ACTION = "com.espforge.ide.USB_PERMISSION"
private const val FLASH_USB_PERMISSION_ACTION = "com.espforge.ide.FLASH_USB_PERMISSION"
private const val TERMUX_ACTION = "com.termux.RUN_COMMAND"
private const val WORKSPACE = "/storage/emulated/0/Documents/ESPForge"
private const val LIBRARY_ZIPS = "$WORKSPACE/LibraryZips"

private data class BoardOption(val id: String, val label: String, val fqbn: String, val core: String)
private data class ArduinoLibraryEntry(
    val name: String,
    val version: String,
    val sentence: String,
    val author: String,
    val url: String
)
internal data class FlashPermissionResult(val device: UsbDevice, val granted: Boolean)
private val boards = listOf(
    BoardOption("arduino_uno", "Arduino Uno", "arduino:avr:uno", "arduino:avr"),
    BoardOption("arduino_nano", "Arduino Nano", "arduino:avr:nano", "arduino:avr"),
    BoardOption("esp32", "ESP32 Dev Module", "esp32:esp32:esp32", "esp32:esp32"),
    BoardOption("esp32_s3", "ESP32-S3 Dev Module", "esp32:esp32:esp32s3", "esp32:esp32"),
    BoardOption("esp8266", "ESP8266 NodeMCU", "esp8266:esp8266:nodemcuv2", "esp8266:esp8266"),
    BoardOption("stm32_bluepill", "STM32 Blue Pill (F1)", "STMicroelectronics:stm32:GenF1", "STMicroelectronics:stm32"),
    BoardOption("ch32v003", "WCH CH32V003F4P6", "WCH:ch32v:CH32V00x_EVT", "WCH:ch32v")
)

class MainActivity : ComponentActivity() {
    var serialStatus by mutableStateOf("USB disconnected")
        private set
    var serialText by mutableStateOf("")
        private set
    var offlineBuildOutput by mutableStateOf("")
        private set
    internal var flashPermissionResult by mutableStateOf<FlashPermissionResult?>(null)
    private var serialPort: UsbSerialPort? = null
    private var pendingDriver: UsbSerialDriver? = null
    @Volatile private var readingSerial = false
    private var requestedBaudRate = 115200
    @Volatile private var activeBuildConnection: HttpURLConnection? = null
    fun setActiveBuildConnection(connection: HttpURLConnection?) { activeBuildConnection = connection }
    fun resetOfflineBuildOutput() { offlineBuildOutput = "" }
    fun cancelOnlineBuild() { activeBuildConnection?.disconnect() }

    private val termuxResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TermuxResultService.ACTION_TERMUX_RESULT) {
                offlineBuildOutput = intent.getStringExtra(TermuxResultService.EXTRA_OUTPUT).orEmpty()
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (intent.action == FLASH_USB_PERMISSION_ACTION) {
                if (device != null) flashPermissionResult = FlashPermissionResult(device, granted)
                return
            }
            if (intent.action != USB_PERMISSION_ACTION) return
            if (granted && device != null) {
                pendingDriver?.takeIf { it.device.deviceId == device.deviceId }?.let { openSerial(it, requestedBaudRate) }
            } else {
                serialStatus = "USB permission denied"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter().apply { addAction(USB_PERMISSION_ACTION); addAction(FLASH_USB_PERMISSION_ACTION) }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(termuxResultReceiver, IntentFilter(TermuxResultService.ACTION_TERMUX_RESULT), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(usbPermissionReceiver, filter)
            @Suppress("DEPRECATION") registerReceiver(termuxResultReceiver, IntentFilter(TermuxResultService.ACTION_TERMUX_RESULT))
        }
        setContent {
            val themePrefs = getSharedPreferences("espforge", Context.MODE_PRIVATE)
            var themeMode by remember { mutableStateOf(themePrefs.getString("themeMode", "Light") ?: "Light") }
            val colors = when (themeMode) {
                "Dark" -> darkColorScheme()
                "AMOLED" -> darkColorScheme(
                    primary = Color(0xFF90CAF9),
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF111111),
                    onSurface = Color(0xFFECEFF1)
                )
                else -> lightColorScheme()
            }
            SideEffect {
                val dark = themeMode != "Light"
                val barColor = when (themeMode) {
                    "AMOLED" -> android.graphics.Color.BLACK
                    "Dark" -> android.graphics.Color.rgb(18, 18, 18)
                    else -> android.graphics.Color.WHITE
                }
                window.statusBarColor = barColor
                window.navigationBarColor = barColor
                window.decorView.systemUiVisibility = if (dark) 0 else
                    (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            }
            MaterialTheme(colorScheme = colors) {
                ForgeHome(this, themeMode) { selected ->
                    themeMode = selected
                    themePrefs.edit().putString("themeMode", selected).apply()
                }
            }
        }
    }

    fun consumeFlashPermission() { flashPermissionResult = null }

    fun requestFlashPermission(device: UsbDevice) {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            flashPermissionResult = FlashPermissionResult(device, true)
            return
        }
        val permission = PendingIntent.getBroadcast(
            this, device.deviceId + 10_000,
            Intent(FLASH_USB_PERMISSION_ACTION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        manager.requestPermission(device, permission)
    }

    suspend fun flashEspFirmware(
        device: UsbDevice,
        boardId: String,
        images: List<FirmwareImage>,
        onProgress: (FlashProgress) -> Unit
    ): String = withContext(Dispatchers.IO) {
        require(boardId == "esp8266" || boardId == "esp32" || boardId == "esp32_s3") {
            "Direct USB flashing is currently implemented for ESP8266, ESP32 and ESP32-S3 only."
        }
        require(images.isNotEmpty()) { "No firmware images were selected." }
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val transport = UsbSerialTransport(manager, device, UsbSerialTransport.labelFor(device))
        val chip = when (boardId) {
            "esp8266" -> ChipId.ESP8266
            "esp32_s3" -> ChipId.ESP32_S3
            else -> ChipId.ESP32
        }
        val flasher: EspFlasher = if (boardId == "esp8266") {
            Esp8266Flasher(transport, autoReset = true)
        } else {
            Esp32Flasher(transport, autoReset = true)
        }
        try {
            flasher.connect()
            val detected = flasher.detectChip()
            require(detected.chipId == chip) { "Selected target is $chip but the connected board reports ${detected.chipId}. Flash cancelled." }
            flasher.writeFirmware(images) { onProgress(it) }
            flasher.verifyFirmware(images)
            flasher.reset()
            "Flash and MD5 verification succeeded. Detected: ${detected.description}, flash: ${detected.flashSizeLabel}."
        } finally {
            flasher.disconnect()
        }
    }

    suspend fun flashAvrFirmware(
        device: UsbDevice,
        boardId: String,
        hexText: String,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        require(boardId == "arduino_uno" || boardId == "arduino_nano") { "AVR uploader supports Uno and Nano only." }
        require(hexText.isNotBlank()) { "Intel HEX firmware is empty." }
        closeSerial()
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val transport = UsbSerialTransport(manager, device, UsbSerialTransport.labelFor(device))
        Stk500v1Uploader(transport).upload(
            hexText,
            nanoOldBootloader = boardId == "arduino_nano",
            onProgress = onProgress
        )
    }

    suspend fun flashFirmware(
        device: UsbDevice,
        boardId: String,
        images: List<FirmwareImage>,
        onProgress: (Float) -> Unit
    ): String {
        return when (boardId) {
            "arduino_uno", "arduino_nano" -> {
                val hexImage = images.firstOrNull { it.name.endsWith(".hex", true) }
                    ?: throw IllegalArgumentException("Choose an AVR .hex output generated for Uno/Nano.")
                flashAvrFirmware(device, boardId, String(hexImage.bytes, Charsets.UTF_8), onProgress)
            }
            else -> flashEspFirmware(device, boardId, images) { progress -> onProgress(progress.percent) }
        }
    }

    fun connectSerial(baudRate: Int = 115200) {
        requestedBaudRate = baudRate
        try {
            val manager = getSystemService(USB_SERVICE) as UsbManager
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            if (driver == null) {
                serialStatus = "No supported USB serial device found. Check OTG cable and adapter."
                return
            }
            pendingDriver = driver
            if (manager.hasPermission(driver.device)) openSerial(driver, baudRate)
            else {
                val permission = PendingIntent.getBroadcast(
                    this, 0, Intent(USB_PERMISSION_ACTION).setPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                manager.requestPermission(driver.device, permission)
                serialStatus = "Waiting for USB permission…"
            }
        } catch (e: Exception) {
            serialStatus = "USB error: ${e.message}"
        }
    }

    private fun openSerial(driver: UsbSerialDriver, baudRate: Int) {
        try {
            closeSerial()
            val manager = getSystemService(USB_SERVICE) as UsbManager
            val connection = manager.openDevice(driver.device)
                ?: throw IllegalStateException("Android could not open the USB device")
            val port = driver.ports.first()
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port
            readingSerial = true
            serialStatus = "Connected: ${driver.device.deviceName} @ ${baudRate} baud"
            Thread {
                val buffer = ByteArray(4096)
                while (readingSerial) {
                    try {
                        val count = port.read(buffer, 500)
                        if (count > 0) {
                            val chunk = String(buffer, 0, count, Charsets.UTF_8)
                            runOnUiThread {
                                serialText = (serialText + chunk).takeLast(20000)
                            }
                        }
                    } catch (e: Exception) {
                        if (readingSerial) runOnUiThread { serialStatus = "Serial read stopped: ${e.message}" }
                        break
                    }
                }
            }.start()
        } catch (e: Exception) {
            serialStatus = "Serial open failed: ${e.message}"
        }
    }

    fun sendSerial(value: String) {
        try {
            serialPort?.write((value + "\n").toByteArray(), 1000)
                ?: run { serialStatus = "Connect a USB serial device first" }
        } catch (e: Exception) { serialStatus = "Serial write failed: ${e.message}" }
    }

    fun clearSerial() { serialText = "" }

    fun closeSerial() {
        readingSerial = false
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        serialStatus = "USB disconnected"
    }

    override fun onDestroy() {
        closeSerial()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(termuxResultReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgeHome(activity: MainActivity, themeMode: String, onThemeChange: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("espforge", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf("ESPForgeSketch") }
    var board by remember { mutableStateOf(boards[2]) }
    var fqbn by remember { mutableStateOf(board.fqbn) }
    var mode by remember { mutableStateOf(prefs.getString("buildMode", "Offline") ?: "Offline") }
    val defaultSource = "#include <Arduino.h>\n\nvoid setup() {\n  Serial.begin(115200);\n}\n\nvoid loop() {\n}\n"
    var projectFiles by remember { mutableStateOf(loadProjectFiles(prefs, project, defaultSource)) }
    var activeFileName by remember { mutableStateOf("main.ino") }
    fun saveFiles(next: Map<String, String>) {
        projectFiles = next
        prefs.edit().putString("projectFiles:$project", JSONObject(next).toString()).apply()
    }
    var removedFiles by remember {
        mutableStateOf(runCatching {
            val json = JSONObject(prefs.getString("removedFiles:$project", "{}") ?: "{}")
            buildMap { json.keys().forEach { key -> put(key, json.getString(key)) } }
        }.getOrDefault(emptyMap()))
    }
    fun saveRemovedFiles(next: Map<String, String>) {
        removedFiles = next
        prefs.edit().putString("removedFiles:$project", JSONObject(next).toString()).apply()
    }
    fun addProjectFile(extension: String) {
        val safeExtension = extension.removePrefix(".").lowercase()
        val number = (1..99).firstOrNull { "File$it.$safeExtension" !in projectFiles } ?: return
        val name = "File$number.$safeExtension"
        saveFiles(projectFiles + (name to ""))
        activeFileName = name
    }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("apiKey", "") ?: "") }
    var compileJobs by remember { mutableStateOf(prefs.getInt("compileJobs", 0)) }
    var extraLibraries by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Select a board, prepare the local toolchain once, then compile locally or use the remote Arduino CLI service.") }
    var tab by remember { mutableStateOf("Editor") }
    var busy by remember { mutableStateOf(false) }
    var buildProgress by remember { mutableStateOf(0) }
    var buildStage by remember { mutableStateOf("Idle") }
    var buildLog by remember { mutableStateOf("") }
    var buildStartedAt by remember { mutableStateOf(0L) }
    var buildElapsed by remember { mutableStateOf(0L) }
    var cancelRequested by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var pendingZip by remember { mutableStateOf<ByteArray?>(null) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var libraryFiles by remember { mutableStateOf(listOf<File>()) }
    var libraryQuery by remember { mutableStateOf("") }
    var libraryResults by remember { mutableStateOf(emptyList<ArduinoLibraryEntry>()) }
    var libraryManagerBusy by remember { mutableStateOf(false) }
    var libraryManagerMessage by remember { mutableStateOf("Search the official Arduino Library Manager index.") }
    var usbDevices by remember { mutableStateOf(emptyList<UsbDevice>()) }
    var selectedUsbId by remember { mutableStateOf<Int?>(null) }
    var firmwareImages by remember { mutableStateOf(emptyList<FirmwareImage>()) }
    var firmwareLabel by remember { mutableStateOf("No firmware selected") }
    var flashAddress by remember { mutableStateOf("0x10000") }
    var flashProgress by remember { mutableStateOf(0f) }
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    val selectedUsb = usbDevices.firstOrNull { it.deviceId == selectedUsbId }
    val libDir = remember { File(context.filesDir, "library-zips").apply { mkdirs() } }
    fun refreshLibraries() { libraryFiles = libDir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "zip" }?.sortedBy { it.name } ?: emptyList() }
    fun refreshUsbDevices() {
        usbDevices = usbManager.deviceList.values.toList()
        if (usbDevices.none { it.deviceId == selectedUsbId }) selectedUsbId = usbDevices.firstOrNull()?.deviceId
    }

    val saveBuildLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingZip
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; output = "Firmware ZIP saved." }
                .onFailure { output = "Save failed: ${it.message}" }
        }
        pendingZip = null
    }
    val projectExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { stream ->
                java.util.zip.ZipOutputStream(stream).use { zip ->
                    projectFiles.forEach { (name, code) ->
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        zip.write(code.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry("espforge-project.json"))
                    zip.write(JSONObject().put("project", project).put("board", board.id).put("fqbn", fqbn).toString().toByteArray())
                    zip.closeEntry()
                }
            }
            output = "Project ZIP exported with ${projectFiles.size} source files."
        }.onFailure { output = "Project export failed: ${it.message}" }
    }
    val projectImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val imported = linkedMapOf<String, String>()
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name != "espforge-project.json") {
                            require(!entry.name.contains("/") && !entry.name.contains("\\") && !entry.name.startsWith(".")) {
                                "Project ZIP must contain flat source files only."
                            }
                            if (entry.name.matches(Regex("[A-Za-z0-9_.-]{1,64}\\.(ino|cpp|c|h|hpp)"))) {
                                imported[entry.name] = String(zip.readBytes(), Charsets.UTF_8)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            validateProjectFiles(imported)
            saveFiles(imported)
            activeFileName = "main.ino"
            output = "Imported ${imported.size} source files."
        }.onFailure { output = "Project import failed: ${it.message}" }
    }

    val exportLibraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingExport
        if (uri != null && file != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            output = "Library ZIP exported: ${file.name}"
        }.onFailure { output = "Export failed: ${it.message}" }
        pendingExport = null
    }
    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val loaded = withContext(Dispatchers.IO) { readFirmwareImages(context, uri, board.id, flashAddress) }
                    firmwareImages = loaded
                    firmwareLabel = queryDisplayName(context, uri).ifBlank { "Firmware package" } + " · ${loaded.size} image(s)"
                    output = "Firmware loaded. Review the target board and USB device before flashing."
                }.onFailure { output = "Firmware selection failed: ${it.message}" }
            }
        }
    }

    val importLibraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val name = queryDisplayName(context, uri).ifBlank { "library-${System.currentTimeMillis()}.zip" }
            require(name.endsWith(".zip", true)) { "Select a ZIP archive." }
            val target = File(libDir, name)
            context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            copyToSharedLibraryFolder(context, uri, name)
            refreshLibraries()
            output = "Library imported. It is stored for offline builds under Documents/ESPForge/LibraryZips."
        }.onFailure { output = "Library import failed: ${it.message}" }
    }

    LaunchedEffect(Unit) { refreshLibraries(); refreshUsbDevices() }
    LaunchedEffect(activity.offlineBuildOutput) {
        if (activity.offlineBuildOutput.isNotBlank()) {
            output = activity.offlineBuildOutput
            busy = false
            val success = activity.offlineBuildOutput.contains("exit code: 0")
            buildStage = if (success) "Build succeeded" else "Build failed"
            buildProgress = if (success) 100 else buildProgress
            buildLog = (buildLog + "\n" + activity.offlineBuildOutput).takeLast(24000)
            if (success) {
                val builtZip = File("$WORKSPACE/Builds/$project/firmware.zip")
                runCatching {
                    if (builtZip.isFile) {
                        firmwareImages = withContext(Dispatchers.IO) {
                            readFirmwareImagesFromBytes(builtZip.readBytes(), builtZip.name, board.id, flashAddress)
                        }
                        firmwareLabel = "Fresh local build · ${firmwareImages.size} firmware image(s) ready"
                        output = "Local compilation succeeded. Firmware is ready in Upload."
                    }
                }.onFailure { output += "\nUpload preparation failed: ${it.message}" }
            }
        }
    }
    LaunchedEffect(busy, mode, project) {
        if (busy && mode == "Offline") {
            val logFile = File("/storage/emulated/0/Documents/ESPForge/Builds/$project/build.log")
            while (busy && mode == "Offline") {
                runCatching {
                    if (logFile.exists()) {
                        val tail = logFile.readText().takeLast(20000)
                        if (tail.isNotBlank()) {
                            buildLog = tail
                            buildStage = "Compiling · Termux"
                            buildProgress = maxOf(buildProgress, 55)
                        }
                    }
                }
                delay(650)
            }
        }
    }
    LaunchedEffect(busy) {
        while (busy) {
            buildElapsed = if (buildStartedAt > 0L) (android.os.SystemClock.elapsedRealtime() - buildStartedAt) / 1000L else 0L
            delay(500)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("ESP Forge IDE") }, actions = {
        TextButton(onClick = { tab = "Settings" }, enabled = !busy) { Text("Settings") }
        Text(if (busy) "BUILDING" else "v0.4.0", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
    }) }) { insets ->
        Column(
            Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(project, { project = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(32) },
                label = { Text("Project") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(board.label, {}, readOnly = true, label = { Text("Target board") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    boards.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            board = option; fqbn = option.fqbn; expanded = false
                        })
                    }
                }
            }
            OutlinedTextField(fqbn, { fqbn = it }, label = { Text("Board FQBN (advanced)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "Online", onClick = { mode = "Online"; prefs.edit().putString("buildMode", mode).apply() }, label = { Text("Remote Arduino CLI") })
                FilterChip(selected = mode == "Offline", onClick = { mode = "Offline"; prefs.edit().putString("buildMode", mode).apply() }, label = { Text("Local build") })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Editor", "Libraries", "Upload", "Serial", "Settings").forEach { item ->
                    FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item) })
                }
            }
            when (tab) {
                "Editor" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { projectImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) { Text("Open project ZIP") }
                        OutlinedButton(onClick = { projectExportLauncher.launch("${project}-project.zip") }) { Text("Export project") }
                    }
                    Text("Project files · ${projectFiles.size}", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        projectFiles.keys.forEach { fileName ->
                            FilterChip(selected = activeFileName == fileName, onClick = { activeFileName = fileName }, label = { Text(fileName) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { addProjectFile("h") }) { Text("+ .h") }
                        OutlinedButton(onClick = { addProjectFile("cpp") }) { Text("+ .cpp") }
                        OutlinedButton(onClick = { addProjectFile("c") }) { Text("+ .c") }
                        OutlinedButton(onClick = { addProjectFile("ino") }) { Text("+ .ino") }
                        TextButton(
                            enabled = activeFileName != "main.ino" && activeFileName in projectFiles,
                            onClick = {
                                val name = activeFileName
                                saveRemovedFiles(removedFiles + (name to projectFiles.getValue(name)))
                                saveFiles(projectFiles - name)
                                activeFileName = projectFiles.keys.firstOrNull() ?: "main.ino"
                            }
                        ) { Text("Remove") }
                        TextButton(
                            enabled = activeFileName != "main.ino" && activeFileName in projectFiles,
                            onClick = { deleteTarget = activeFileName }
                        ) { Text("Delete") }
                        TextButton(
                            enabled = activeFileName != "main.ino" && activeFileName in projectFiles,
                            onClick = { renameTarget = activeFileName; renameValue = activeFileName }
                        ) { Text("Rename") }
                    }
                    if (removedFiles.isNotEmpty()) {
                        Text("Removed from project", style = MaterialTheme.typography.titleSmall)
                        removedFiles.forEach { (name, source) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(name, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    if (name !in projectFiles) {
                                        saveFiles(projectFiles + (name to source))
                                        saveRemovedFiles(removedFiles - name)
                                    }
                                }) { Text("Restore") }
                                TextButton(onClick = { saveRemovedFiles(removedFiles - name) }) { Text("Delete permanently") }
                            }
                        }
                    }
                    OutlinedTextField(
                        projectFiles[activeFileName].orEmpty(),
                        { edited -> saveFiles(projectFiles + (activeFileName to edited)) },
                        label = { Text(activeFileName) },
                        minLines = 15,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(extraLibraries, { extraLibraries = it }, label = { Text("Additional library names (comma separated, optional)") }, minLines = 1, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy, onClick = {
                        cancelRequested = false
                        buildProgress = 0
                        buildStage = "Starting"
                        buildLog = ""
                        buildStartedAt = android.os.SystemClock.elapsedRealtime()
                        buildElapsed = 0
                        if (mode == "Online") {
                            busy = true
                            output = "Connecting to streaming Arduino CLI compiler…"
                            buildLog = "Build requested · $project · ${board.label} · $fqbn\n"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    compileOnline(activity, endpoint, apiKey, board.id, fqbn, project, projectFiles, extraLibraries, compileJobs) { stage, percent, message, isLog ->
                                        activity.runOnUiThread {
                                            buildStage = stage
                                            if (percent >= 0) buildProgress = percent
                                            if (message.isNotBlank()) {
                                                buildLog = (buildLog + (if (isLog) message + "\n" else "[$stage] $message\n")).takeLast(24000)
                                            }
                                        }
                                    }
                                }
                                busy = false
                                result.onSuccess { bytes ->
                                    pendingZip = bytes
                                    runCatching {
                                        firmwareImages = withContext(Dispatchers.IO) {
                                            readFirmwareImagesFromBytes(bytes, "${project}-firmware.zip", board.id, flashAddress)
                                        }
                                        firmwareLabel = "Fresh build · ${firmwareImages.size} firmware image(s) ready"
                                    }.onFailure { firmwareLabel = "Build succeeded; upload preparation failed: ${it.message}" }
                                    output = "Online compilation succeeded. Firmware is ready in Upload; save the archive if you want a copy."
                                    saveBuildLauncher.launch("${project}-firmware.zip")
                                }.onFailure {
                                    output = if (cancelRequested) "Build cancelled." else "Online build failed: ${it.message}"
                                    buildStage = if (cancelRequested) "Cancelled" else "Build failed"
                                    buildLog = (buildLog + "\n" + output).takeLast(24000)
                                }
                            }
                        } else {
                            busy = true
                            buildStage = "Starting Termux"
                            buildProgress = 3
                            output = "Starting local Arduino CLI build in Termux. Live output will appear below."
                            activity.resetOfflineBuildOutput()
                            val success = if (board.id == "ch32v003") false else runOfflineCompile(activity, board, fqbn, project, projectFiles, compileJobs)
                            if (success) {
                                buildStage = "Waiting for local compiler"
                                buildLog = "Termux accepted the build command. Waiting for compiler output…"
                                buildProgress = 5
                                output = "Offline build started. Compiler output is being monitored."
                            } else {
                                busy = false
                                buildStage = "Could not start"
                                output = if (board.id == "ch32v003") "CH32 toolchain packages currently lack ARM64 Linux compiler binaries, so offline compilation on this Android phone is unavailable. Use Online mode." else "Could not start Termux. Install Termux, allow external apps, grant Run commands permission and run the offline setup first."
                                buildLog = output
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Building…" else "Compile firmware") }
                    if (busy && mode == "Online") {
                        OutlinedButton(onClick = {
                            cancelRequested = true
                            activity.cancelOnlineBuild()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Cancel build") }
                    }
                    if (buildStage != "Idle") {
                        Text("Build progress · $buildStage", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(
                            progress = { buildProgress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("$buildProgress% · stage estimate (not exact compiler %) · $buildElapsed s elapsed", style = MaterialTheme.typography.bodySmall)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Text(buildLog.ifBlank { "Waiting for compiler output…" },
                                modifier = Modifier.padding(10.dp).heightIn(min = 80.dp, max = 260.dp).verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                "Libraries" -> {
                    Text("Arduino Library Manager", style = MaterialTheme.typography.titleMedium)
                    Text("Search the official Arduino library index. Install downloads a ZIP into ESP Forge's local library store and adds the package to the current build dependencies.")
                    OutlinedTextField(
                        libraryQuery,
                        { libraryQuery = it },
                        label = { Text("Library name or keyword") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !libraryManagerBusy && libraryQuery.isNotBlank(),
                        onClick = {
                            libraryManagerBusy = true
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { searchArduinoLibraries(context, libraryQuery.trim()) }
                                }
                                libraryManagerBusy = false
                                result.onSuccess {
                                    libraryResults = it
                                    libraryManagerMessage = if (it.isEmpty()) "No matching libraries found." else "Found ${it.size} matching libraries."
                                }.onFailure {
                                    libraryManagerMessage = "Library search failed: ${it.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (libraryManagerBusy) "Searching…" else "Search official library index") }
                    if (libraryManagerBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(libraryManagerMessage, style = MaterialTheme.typography.bodySmall)
                    libraryResults.forEach { entry ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                Text("Version ${entry.version} · ${entry.author}", style = MaterialTheme.typography.bodySmall)
                                if (entry.sentence.isNotBlank()) Text(entry.sentence, style = MaterialTheme.typography.bodySmall)
                                Button(
                                    enabled = !libraryManagerBusy,
                                    onClick = {
                                        libraryManagerBusy = true
                                        scope.launch {
                                            val result = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    val zip = downloadArduinoLibrary(context, entry)
                                                    copyLibraryFileToSharedFolder(context, zip)
                                                    zip
                                                }
                                            }
                                            libraryManagerBusy = false
                                            result.onSuccess { zip ->
                                                refreshLibraries()
                                                val deps = (extraLibraries.split(',').map { it.trim() }.filter { it.isNotBlank() } + entry.name).distinct()
                                                extraLibraries = deps.joinToString(", ")
                                                libraryManagerMessage = "${entry.name} ${entry.version} installed locally and added to this project's dependencies."
                                            }.onFailure {
                                                libraryManagerMessage = "Install failed: ${it.message}"
                                            }
                                        }
                                    }
                                ) { Text("Install locally + add to project") }
                            }
                        }
                    }
                    Divider()
                    Text("Installed library archives · ${libraryFiles.size}", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { importLibraryLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Import library ZIP") }
                    if (libraryFiles.isEmpty()) Text("No local library ZIPs installed yet.")
                    libraryFiles.forEach { file ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(file.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { pendingExport = file; exportLibraryLauncher.launch(file.name) }) { Text("Export") }
                            TextButton(onClick = {
                                file.delete()
                                refreshLibraries()
                                libraryManagerMessage = "Removed ${file.name} from the local library store."
                            }) { Text("Remove") }
                        }
                    }
                }
                "Upload" -> {
                    Text("Firmware is flashed locally over USB OTG. Choose a build ZIP or raw .bin file.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { refreshUsbDevices() }) { Text("Refresh USB") }
                        OutlinedButton(onClick = { firmwarePicker.launch(arrayOf("*/*")) }) { Text("Choose firmware") }
                    }
                    var deviceExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = deviceExpanded, onExpandedChange = { deviceExpanded = !deviceExpanded }) {
                        OutlinedTextField(
                            selectedUsb?.let { "${it.deviceName} · VID ${it.vendorId.toString(16)} PID ${it.productId.toString(16)}" } ?: "No USB device detected",
                            {}, readOnly = true, label = { Text("USB OTG device") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(deviceExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = deviceExpanded, onDismissRequest = { deviceExpanded = false }) {
                            usbDevices.forEach { dev ->
                                DropdownMenuItem(
                                    text = { Text("${dev.deviceName} · ${dev.vendorId.toString(16)}:${dev.productId.toString(16)}") },
                                    onClick = { selectedUsbId = dev.deviceId; deviceExpanded = false }
                                )
                            }
                        }
                    }
                    Text(firmwareLabel)
                    OutlinedTextField(flashAddress, { flashAddress = it }, label = { Text("Raw .bin flash address (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Automatic multi-image offsets for standard Arduino ESP32 ZIPs: bootloader 0x1000, partitions 0x8000, boot_app0 0xE000 and app 0x10000. ESP8266 uses 0x0.")
                    firmwareImages.forEach { image ->
                        Text("${image.name} → 0x${image.address.toString(16)} · ${image.size} bytes", style = MaterialTheme.typography.bodySmall)
                    }
                    if (flashProgress > 0f && flashProgress < 100f) LinearProgressIndicator(progress = { flashProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = !busy && selectedUsb != null && firmwareImages.isNotEmpty() && board.id in listOf("arduino_uno", "arduino_nano", "esp8266", "esp32", "esp32_s3"),
                        onClick = {
                            val dev = selectedUsb
                            if (dev == null) output = "Connect the board through a USB OTG adapter first."
                            else if (firmwareImages.isEmpty()) output = "Choose a valid firmware ZIP or .bin file first."
                            else activity.requestFlashPermission(dev)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Flashing…" else "Flash + verify") }
                    Text("Direct USB flashing is implemented for ESP8266/ESP32 variants and ATmega328P Uno/Nano. STM32 and CH32 compile targets are included; their native upload protocols still need implementation.")
                    LaunchedEffect(activity.flashPermissionResult) {
                        val result = activity.flashPermissionResult
                        if (result != null) {
                            activity.consumeFlashPermission()
                            if (!result.granted) {
                                output = "Android USB permission was denied."
                            } else if (selectedUsb?.deviceId == result.device.deviceId && firmwareImages.isNotEmpty()) {
                                busy = true
                                flashProgress = 0f
                                output = "Entering ROM bootloader and flashing firmware…"
                                val attempt = runCatching {
                                    activity.flashFirmware(result.device, board.id, firmwareImages) { progress ->
                                        activity.runOnUiThread { flashProgress = progress }
                                    }
                                }
                                busy = false
                                attempt.onSuccess { output = it; flashProgress = 100f }
                                    .onFailure { output = "Flash failed: ${it.message}" }
                            }
                        }
                    }
                }
                "Serial" -> {
                    Text(activity.serialStatus)
                    var baudExpanded by remember { mutableStateOf(false) }
                    var serialBaudRate by remember { mutableStateOf(115200) }
                    ExposedDropdownMenuBox(expanded = baudExpanded, onExpandedChange = { baudExpanded = !baudExpanded }) {
                        OutlinedTextField(serialBaudRate.toString(), {}, readOnly = true, label = { Text("Baud rate") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(baudExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = baudExpanded, onDismissRequest = { baudExpanded = false }) {
                            listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).forEach { rate ->
                                DropdownMenuItem(text = { Text(rate.toString()) }, onClick = { serialBaudRate = rate; baudExpanded = false })
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { activity.connectSerial(serialBaudRate) }) { Text("Connect USB") }
                        OutlinedButton(onClick = { activity.closeSerial() }) { Text("Disconnect") }
                        TextButton(onClick = { activity.clearSerial() }) { Text("Clear") }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(activity.serialText.ifBlank { "Serial output will appear here." }, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    var serialCommand by remember { mutableStateOf("") }
                    OutlinedTextField(serialCommand, { serialCommand = it }, label = { Text("Send to device") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { activity.sendSerial(serialCommand); serialCommand = "" }) { Text("Send") }
                    Text("USB host support depends on phone OTG hardware and adapter compatibility.")
                }
                else -> {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Light", "Dark", "AMOLED").forEach { theme ->
                            FilterChip(selected = themeMode == theme, onClick = { onThemeChange(theme) }, label = { Text(theme) })
                        }
                    }
                    Text("Theme selection is saved on this device.", style = MaterialTheme.typography.bodySmall)
                    Divider()
                    Text("Remote Arduino CLI build service")
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Build API base URL (e.g. https://your-host)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Build API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { prefs.edit().putString("endpoint", endpoint.trim()).putString("apiKey", apiKey).apply(); output = "Build service settings saved on this device." }, modifier = Modifier.fillMaxWidth()) { Text("Save online settings") }
                    Divider()
                    Text("Local compiler · on-device")
                    Text("Compiler parallel jobs · ${if (compileJobs == 0) "Auto (all available CPU cores)" else compileJobs.toString()}")
                    Slider(
                        value = compileJobs.toFloat(),
                        onValueChange = { next -> compileJobs = next.toInt(); prefs.edit().putInt("compileJobs", compileJobs).apply() },
                        valueRange = 0f..8f,
                        steps = 7
                    )
                    Text("Local builds automatically cap compiler workers by available RAM and CPU, with a maximum of 4 simultaneous compiler processes to reduce overheating and out-of-memory failures. Your selected job count is treated as an upper limit.", style = MaterialTheme.typography.bodySmall)
                    Text("Local compilation currently runs Arduino CLI inside a Termux Debian environment on this phone. Install Termux, open it once, run termux-setup-storage, enable allow-external-apps=true, then grant ESP Forge the Run commands permission. A fully bundled Android-native toolchain is a separate porting task.")
                    Button(onClick = {
                        val ok = if (board.id == "ch32v003") false else runTermuxSetup(activity, board)
                        output = if (ok) "Termux offline toolchain setup launched. Keep Termux open until downloads and core installation finish." else "Termux setup could not start. Check Termux installation and permissions."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Prepare offline toolchain for selected board") }
                    Text("First setup downloads the toolchain once. Subsequent builds reuse the installed core, compiler packages, library folder and persistent per-project build cache. Avoid deleting Build/.build-cache if you want incremental builds.")
                }
            }
            Text("Build log", style = MaterialTheme.typography.titleMedium)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(output, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            renameTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { renameTarget = null },
                    title = { Text("Rename project file") },
                    text = {
                        OutlinedTextField(renameValue, { renameValue = it }, label = { Text("New filename") }, singleLine = true)
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val nextName = renameValue.trim()
                            if (nextName.matches(Regex("[A-Za-z0-9_.-]{1,64}\\.(ino|cpp|c|h|hpp)")) &&
                                nextName !in projectFiles && target != "main.ino") {
                                val next = linkedMapOf<String, String>()
                                projectFiles.forEach { (name, code) -> next[if (name == target) nextName else name] = code }
                                saveFiles(next)
                                activeFileName = nextName
                                renameTarget = null
                            } else {
                                output = "Invalid filename or a file with that name already exists."
                            }
                        }) { Text("Rename") }
                    },
                    dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
                )
            }
            deleteTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { deleteTarget = null },
                    title = { Text("Delete $target permanently?") },
                    text = { Text("This removes the file and its source from this project. Use Remove instead if you may want to restore it later.") },
                    confirmButton = {
                        TextButton(onClick = {
                            saveFiles(projectFiles - target)
                            activeFileName = projectFiles.keys.firstOrNull { it != target } ?: "main.ino"
                            deleteTarget = null
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
                )
            }
        }
    }
}

private fun validateProjectFiles(files: Map<String, String>) {
    require(files.isNotEmpty() && files.size <= 40) { "A project must contain 1–40 source files." }
    require(files.containsKey("main.ino")) { "The project needs main.ino." }
    require(files.keys.all { it.matches(Regex("[A-Za-z0-9_.-]{1,64}\\.(ino|cpp|c|h|hpp)")) }) {
        "Use safe source filenames with .ino, .cpp, .c, .h or .hpp extensions."
    }
    val bytes = files.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
    require(bytes <= 90_000) { "Project source exceeds 90 KB. Split the project or remove large embedded data." }
}

private fun loadProjectFiles(prefs: android.content.SharedPreferences, project: String, defaultSource: String): Map<String, String> {
    val raw = prefs.getString("projectFiles:$project", null) ?: return mapOf("main.ino" to defaultSource)
    return runCatching {
        val json = JSONObject(raw)
        val result = linkedMapOf<String, String>()
        json.keys().forEach { key ->
            if (key.matches(Regex("[A-Za-z0-9_.-]{1,64}\\.(ino|cpp|c|h|hpp)"))) result[key] = json.getString(key)
        }
        if (result.containsKey("main.ino")) result else mapOf("main.ino" to defaultSource)
    }.getOrDefault(mapOf("main.ino" to defaultSource))
}

private fun readFirmwareImages(context: Context, uri: Uri, boardId: String, rawAddress: String): List<FirmwareImage> {
    val displayName = queryDisplayName(context, uri).ifBlank { "firmware.bin" }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("Unable to read selected firmware.")
    return readFirmwareImagesFromBytes(bytes, displayName, boardId, rawAddress)
}

private fun readFirmwareImagesFromBytes(bytes: ByteArray, displayName: String, boardId: String, rawAddress: String): List<FirmwareImage> {
    if (displayName.endsWith(".zip", true) || (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())) {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith("firmware/") && (entry.name.endsWith(".bin", true) || entry.name.endsWith(".hex", true))) {
                    entries += entry.name.substringAfterLast('/') to zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (entries.isEmpty()) throw IllegalArgumentException("ZIP contains no supported firmware .bin or .hex files.")
        return entries.sortedBy { firmwareAddress(it.first, boardId) }
            .map { (name, data) -> FirmwareImage(name, data, firmwareAddress(name, boardId)) }
    }
    if (!(displayName.endsWith(".bin", true) || displayName.endsWith(".hex", true))) throw IllegalArgumentException("Choose a firmware ZIP, raw .bin or Intel HEX file.")
    return listOf(FirmwareImage(displayName, bytes, if (displayName.endsWith(".hex", true)) 0 else parseHexAddress(rawAddress)))
}

private fun firmwareAddress(name: String, boardId: String): Int {
    val n = name.lowercase()
    return when {
        boardId == "arduino_uno" || boardId == "arduino_nano" -> 0
        "merged" in n || "combined" in n -> 0
        boardId == "esp8266" -> 0
        "bootloader" in n -> 0x1000
        "partition" in n -> 0x8000
        "boot_app0" in n || "bootapp0" in n -> 0xE000
        else -> 0x10000
    }
}

private fun parseHexAddress(value: String): Int {
    val normalized = value.trim().removePrefix("0x").removePrefix("0X")
    val parsed = normalized.toLong(16)
    require(parsed in 0..0xFFFFFFFFL) { "Flash address is out of range." }
    return parsed.toInt()
}

private fun compileOnline(
    activity: MainActivity,
    endpoint: String, apiKey: String, board: String, fqbn: String,
    project: String, projectFiles: Map<String, String>, libraryText: String, jobs: Int,
    onUpdate: (String, Int, String, Boolean) -> Unit
): Result<ByteArray> = runCatching {
    require(endpoint.startsWith("https://")) { "Use an HTTPS build API URL in Settings." }
    require(apiKey.isNotBlank()) { "Enter the build API key in Settings." }
    require(projectFiles.containsKey("main.ino") && projectFiles["main.ino"].orEmpty().isNotBlank()) { "main.ino is missing or empty." }
    validateProjectFiles(projectFiles)
    val libs = JSONArray()
    libraryText.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { libs.put(it) }
    val files = JSONObject(projectFiles)
    val body = JSONObject().put("board", board).put("fqbn", fqbn).put("project_id", project).put("jobs", jobs).put("files", files).put("libraries", libs)
    val url = URL(endpoint.trimEnd('/') + "/v1/compile/stream")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20000
        readTimeout = 900000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "text/event-stream")
        setRequestProperty("X-API-Key", apiKey)
    }
    activity.setActiveBuildConnection(conn)
    try {
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val error = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
            throw IllegalStateException("HTTP $code: $error")
        }
        var archive: ByteArray? = null
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data: ")) continue
                val event = JSONObject(line.removePrefix("data: "))
                val type = event.optString("type")
                val stage = event.optString("stage", "Building")
                val percent = event.optInt("progress", -1)
                val message = event.optString("message", "")
                when (type) {
                    "progress" -> onUpdate(stage, percent, message, false)
                    "log" -> onUpdate(stage, percent, message, true)
                    "heartbeat" -> onUpdate(stage, percent, message, false)
                    "error" -> throw IllegalStateException(message.ifBlank { "Compiler failed without an error message." })
                    "complete" -> {
                        onUpdate(stage, 100, message, false)
                        val encoded = event.optString("archive_base64")
                        require(encoded.isNotBlank()) { "Compiler returned no firmware archive." }
                        archive = Base64.decode(encoded, Base64.DEFAULT)
                    }
                }
            }
        }
        val bytes = archive ?: throw IllegalStateException("Build stream ended before a completion event. Check the build service connection.")
        require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            "Build server did not return a valid ZIP firmware archive."
        }
        bytes
    } finally {
        activity.setActiveBuildConnection(null)
        conn.disconnect()
    }
}

private fun resolveLocalCompileJobs(activity: MainActivity, boardId: String, requested: Int): Int {
    val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val availableMiB = (memoryInfo.availMem / (1024L * 1024L)).toInt().coerceAtLeast(512)
    val reserveMiB = 512
    val memoryPerCompilerMiB = when (boardId) {
        "esp32", "esp32_s3" -> 1400
        "esp8266" -> 950
        "arduino_uno", "arduino_nano" -> 400
        else -> 850
    }
    val memorySlots = ((availableMiB - reserveMiB) / memoryPerCompilerMiB).coerceAtLeast(1)
    // Keep sustained phone thermals and UI responsiveness predictable under large builds.
    val safeMaximum = minOf(cpuCount, memorySlots, 4).coerceAtLeast(1)
    return if (requested <= 0) safeMaximum else requested.coerceIn(1, safeMaximum)
}

private fun runOfflineCompile(activity: MainActivity, board: BoardOption, fqbn: String, project: String, projectFiles: Map<String, String>, jobs: Int): Boolean {
    return runCatching {
        require(fqbn.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid FQBN" }
        validateProjectFiles(projectFiles)
        require(projectFiles.containsKey("main.ino")) { "main.ino is required." }
        val sketch = "$WORKSPACE/Projects/$project"
        val libs = "$WORKSPACE/InstalledLibraries"
        val out = "$WORKSPACE/Builds/$project"
        val zipFolder = "$WORKSPACE/LibraryZips"
        val effectiveJobs = resolveLocalCompileJobs(activity, board.id, jobs)
        val sourceWriter = projectFiles.entries.joinToString("\n") { (name, text) ->
            val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "tmp='$sketch/.$name.tmp'; printf '%s' '$encoded' | base64 -d > \"\$tmp\"; if ! cmp -s \"\$tmp\" '$sketch/$name'; then mv \"\$tmp\" '$sketch/$name'; else rm -f \"\$tmp\"; fi"
        }
        val expectedSourceFiles = projectFiles.keys.joinToString(" ")
        val sourceCleanup = """
            for f in '$sketch'/*.ino '$sketch'/*.cpp '$sketch'/*.c '$sketch'/*.h '$sketch'/*.hpp; do
              [ -f "${'$'}f" ] || continue
              case " $expectedSourceFiles " in
                *" ${'$'}(basename "${'$'}f") "*) ;;
                *) rm -f "${'$'}f" ;;
              esac
            done
        """.trimIndent()
        val boardOption = when (board.id) { "stm32_bluepill" -> "--board-options pnum=BLUEPILL_F103C8"; "ch32v003" -> "--board-options pnum=CH32V003F4"; else -> "" }
        val script = """
            set -e
            mkdir -p '$sketch' '$libs' '$out' '$out/artifacts'
            rm -f '$out/artifacts/'*
            $sourceWriter
            $sourceCleanup
            proot-distro login --bind /storage:/storage debian -- bash -lc 'set -e
              cache="${'$'}HOME/.cache/espforge/${project}/${fqbn.replace(':', '_')}"
              mkdir -p "${'$'}cache/build" "${libs}" "${out}"
              for z in "${zipFolder}"/*.zip; do
                [ -f "${'$'}z" ] || continue
                zipname="${'$'}(basename "${'$'}z")"
                marker="${libs}/.espforge-${'$'}zipname.sha256"
                digest="${'$'}(sha256sum "${'$'}z")"
                digest="${'$'}{digest%% *}"
                if [ -f "${'$'}marker" ] && [ "${'$'}(cat "${'$'}marker")" = "${'$'}digest" ]; then continue; fi
                tmp="${'$'}(mktemp -d)"
                unzip -oq "${'$'}z" -d "${'$'}tmp"
                prop="${'$'}(find "${'$'}tmp" -name "library.properties" -print -quit)"
                if [ -n "${'$'}prop" ]; then
                  src="${'$'}(dirname "${'$'}prop")"
                  libname="${'$'}(basename "${'$'}src")"
                  rm -rf "${libs}/${'$'}libname"
                  cp -a "${'$'}src" "${libs}/${'$'}libname"
                fi
                printf "%s" "${'$'}digest" > "${'$'}marker"
                rm -rf "${'$'}tmp"
              done
              cd "${sketch}"
              echo "ESP Forge resource-aware compiler parallelism: ${effectiveJobs} job(s)" > "${out}/build.log"
              if ! arduino-cli compile --fqbn "${fqbn}" ${boardOption} --libraries "${libs}" --build-path "${'
              mkdir -p "${out}/package/firmware"
              find "${out}/artifacts" -maxdepth 1 -type f \( -name "*.bin" -o -name "*.hex" -o -name "*.uf2" \) -exec cp {} "${out}/package/firmware/" \;
              count=${'$'}(find "${out}/package/firmware" -type f | wc -l)
              [ "${'$'}count" -gt 0 ] || { echo "No firmware artifacts produced"; exit 2; }
              printf "%s" "{\"board\":\"${board.id}\",\"fqbn\":\"${fqbn}\"}" > "${out}/package/build-manifest.json"
              cp "${out}/build.log" "${out}/package/build.log"
              cd "${out}/package"
              zip -qr "${out}/firmware.zip" firmware build-manifest.json build.log
              rm -rf "${out}/package"
              echo "Build succeeded. Firmware package: ${out}/firmware.zip"
            '
        """.trimIndent()
        val intent = Intent(TERMUX_ACTION).apply {
            component = ComponentName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "${'$'}PREFIX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "${'$'}HOME")
            putExtra("com.termux.RUN_COMMAND_LABEL", "ESP Forge offline compile")
        }
        val callback = PendingIntent.getService(
            activity,
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            Intent(activity, TermuxResultService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        activity.startService(intent)
        true
    }.getOrElse { false }
}

private fun runTermuxSetup(activity: MainActivity, board: BoardOption): Boolean {
    return runCatching {
        val fqbn = board.fqbn
        val core = board.core
        val script = """
            set -e
            if ! command -v proot-distro >/dev/null 2>&1; then pkg update -y; pkg install -y proot-distro; fi
            if [ ! -d "${'$'}PREFIX/var/lib/proot-distro/installed-rootfs/debian" ]; then proot-distro install debian; fi
            proot-distro login debian -- bash -lc 'set -e
              if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1 || ! command -v zip >/dev/null 2>&1; then
                apt-get update
                apt-get install -y curl ca-certificates unzip zip python3
              fi
              arch="${'$'}(dpkg --print-architecture)"
              if [ "${'$'}arch" = "arm64" ]; then asset="Linux_ARM64"; elif [ "${'$'}arch" = "amd64" ]; then asset="Linux_64bit"; else echo "Unsupported Debian architecture: ${'$'}arch"; exit 2; fi
              if [ ! -x /usr/local/bin/arduino-cli ]; then
                curl -fsSL "https://downloads.arduino.cc/arduino-cli/arduino-cli_1.5.1_${'$'}{asset}.tar.gz" -o /tmp/arduino-cli.tar.gz
                tar -xzf /tmp/arduino-cli.tar.gz -C /usr/local/bin arduino-cli
              fi
              mkdir -p /root/.arduino15
              cat > /root/.arduino15/arduino-cli.yaml <<EOF
board_manager:
  additional_urls:
    - https://espressif.github.io/arduino-esp32/package_esp32_index.json
    - https://arduino.esp8266.com/stable/package_esp8266com_index.json
    - https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json
    - https://github.com/openwch/board_manager_files/raw/main/package_ch32v_index.json
EOF
              if ! arduino-cli core list --format json | grep -Eq "\"id\"[[:space:]]*:[[:space:]]*\"${core}\""; then
                arduino-cli core update-index
                arduino-cli core install "${core}"
              else
                echo "Cached board core already installed: ${core}"
              fi
              echo "Local toolchain ready for ${fqbn}"
            '
        """.trimIndent()
        val intent = Intent(TERMUX_ACTION).apply {
            component = ComponentName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "${'$'}PREFIX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "${'$'}HOME")
            putExtra("com.termux.RUN_COMMAND_LABEL", "ESP Forge toolchain setup")
        }
        activity.startService(intent)
        true
    }.getOrElse { false }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) return it.getString(0) ?: ""
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: ""
}

private fun copyToSharedLibraryFolder(context: Context, source: Uri, name: String) {
    require(Build.VERSION.SDK_INT >= 29) { "Android 10 or newer is required for shared library storage." }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/ESPForge/LibraryZips/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        ?: throw IllegalStateException("Could not create shared library ZIP")
    context.contentResolver.openInputStream(source)!!.use { input ->
        context.contentResolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) }
    }
    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    context.contentResolver.update(uri, ready, null, null)
}



private fun searchArduinoLibraries(context: Context, query: String): List<ArduinoLibraryEntry> {
    require(query.length in 2..80) { "Enter at least 2 characters." }
    val cache = File(context.cacheDir, "arduino-library-index.json")
    if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > 12L * 60L * 60L * 1000L) {
        val connection = URL("https://downloads.arduino.cc/libraries/library_index.json").openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 45000
        connection.setRequestProperty("User-Agent", "ESP-Forge-IDE")
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "Arduino Library Manager returned HTTP ${connection.responseCode}." }
            val temp = File(cache.parentFile, cache.name + ".tmp")
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 80L * 1024L * 1024L) { "Library index exceeds the 80 MiB safety limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!temp.renameTo(cache)) {
                temp.copyTo(cache, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    val records = JSONObject(cache.readText(Charsets.UTF_8)).optJSONArray("libraries") ?: JSONArray()
    val latest = linkedMapOf<String, ArduinoLibraryEntry>()
    for (i in 0 until records.length()) {
        val item = records.optJSONObject(i) ?: continue
        val name = item.optString("name").trim()
        val version = item.optString("version").trim()
        val url = item.optString("url").trim()
        if (name.isBlank() || version.isBlank() || !url.startsWith("https://")) continue
        val candidate = ArduinoLibraryEntry(
            name = name,
            version = version,
            sentence = item.optString("sentence"),
            author = item.optString("author"),
            url = url
        )
        val previous = latest[name]
        if (previous == null || compareArduinoVersions(candidate.version, previous.version) > 0) {
            latest[name] = candidate
        }
    }
    return latest.values.asSequence()
        .filter { it.name.contains(query, ignoreCase = true) || it.sentence.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
        .sortedWith(compareBy<ArduinoLibraryEntry> { !it.name.startsWith(query, ignoreCase = true) }.thenBy { it.name.lowercase() })
        .take(25)
        .toList()
}

private fun compareArduinoVersions(first: String, second: String): Int {
    val a = Regex("\\d+").findAll(first).mapNotNull { it.value.toIntOrNull() }.toList()
    val b = Regex("\\d+").findAll(second).mapNotNull { it.value.toIntOrNull() }.toList()
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left.compareTo(right)
    }
    return first.compareTo(second, ignoreCase = true)
}

private fun downloadArduinoLibrary(context: Context, entry: ArduinoLibraryEntry): File {
    val safe = (entry.name + "-" + entry.version).replace(Regex("[^A-Za-z0-9_.-]"), "_")
    val directory = File(context.filesDir, "library-zips").apply { mkdirs() }
    val target = File(directory, "$safe.zip")
    if (target.isFile && target.length() > 0L) return target
    val connection = URL(entry.url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 60000
    connection.instanceFollowRedirects = true
    try {
        connection.connect()
        require(connection.responseCode in 200..299) { "Library download failed with HTTP ${connection.responseCode}." }
        val temp = File(directory, "$safe.zip.part")
        connection.inputStream.use { input ->
            temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 64L * 1024L * 1024L) { "Library archive exceeds the 64 MiB safety limit." }
                    output.write(buffer, 0, count)
                }
            }
        }
        require(temp.length() > 22L) { "Downloaded library archive is empty or invalid." }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    } finally {
        connection.disconnect()
    }
}

private fun copyLibraryFileToSharedFolder(context: Context, file: File) {
    require(Build.VERSION.SDK_INT >= 29) { "Android 10 or newer is required for local compiler library storage." }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/ESPForge/LibraryZips/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        ?: throw IllegalStateException("Could not copy the library to the compiler library folder.")
    try {
        context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: throw IllegalStateException("Could not open the compiler library destination.")
        val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, complete, null, null)
    } catch (error: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
}
}cache/build" --output-dir "${out}/artifacts" --jobs ${effectiveJobs} . >> "${out}/build.log" 2>&1; then cat "${out}/build.log"; exit 1; fi
              mkdir -p "${out}/package/firmware"
              find "${out}/artifacts" -maxdepth 1 -type f \( -name "*.bin" -o -name "*.hex" -o -name "*.uf2" \) -exec cp {} "${out}/package/firmware/" \;
              count=${'$'}(find "${out}/package/firmware" -type f | wc -l)
              [ "${'$'}count" -gt 0 ] || { echo "No firmware artifacts produced"; exit 2; }
              printf "%s" "{\"board\":\"${board.id}\",\"fqbn\":\"${fqbn}\"}" > "${out}/package/build-manifest.json"
              cp "${out}/build.log" "${out}/package/build.log"
              cd "${out}/package"
              zip -qr "${out}/firmware.zip" firmware build-manifest.json build.log
              rm -rf "${out}/package"
              echo "Build succeeded. Firmware package: ${out}/firmware.zip"
            '
        """.trimIndent()
        val intent = Intent(TERMUX_ACTION).apply {
            component = ComponentName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "${'$'}PREFIX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "${'$'}HOME")
            putExtra("com.termux.RUN_COMMAND_LABEL", "ESP Forge offline compile")
        }
        val callback = PendingIntent.getService(
            activity,
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            Intent(activity, TermuxResultService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        activity.startService(intent)
        true
    }.getOrElse { false }
}

private fun runTermuxSetup(activity: MainActivity, board: BoardOption): Boolean {
    return runCatching {
        val fqbn = board.fqbn
        val core = board.core
        val script = """
            set -e
            if ! command -v proot-distro >/dev/null 2>&1; then pkg update -y; pkg install -y proot-distro; fi
            if [ ! -d "${'$'}PREFIX/var/lib/proot-distro/installed-rootfs/debian" ]; then proot-distro install debian; fi
            proot-distro login debian -- bash -lc 'set -e
              if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1 || ! command -v zip >/dev/null 2>&1; then
                apt-get update
                apt-get install -y curl ca-certificates unzip zip python3
              fi
              arch="${'$'}(dpkg --print-architecture)"
              if [ "${'$'}arch" = "arm64" ]; then asset="Linux_ARM64"; elif [ "${'$'}arch" = "amd64" ]; then asset="Linux_64bit"; else echo "Unsupported Debian architecture: ${'$'}arch"; exit 2; fi
              if [ ! -x /usr/local/bin/arduino-cli ]; then
                curl -fsSL "https://downloads.arduino.cc/arduino-cli/arduino-cli_1.5.1_${'$'}{asset}.tar.gz" -o /tmp/arduino-cli.tar.gz
                tar -xzf /tmp/arduino-cli.tar.gz -C /usr/local/bin arduino-cli
              fi
              mkdir -p /root/.arduino15
              cat > /root/.arduino15/arduino-cli.yaml <<EOF
board_manager:
  additional_urls:
    - https://espressif.github.io/arduino-esp32/package_esp32_index.json
    - https://arduino.esp8266.com/stable/package_esp8266com_index.json
    - https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json
    - https://github.com/openwch/board_manager_files/raw/main/package_ch32v_index.json
EOF
              if ! arduino-cli core list --format json | grep -Eq "\"id\"[[:space:]]*:[[:space:]]*\"${core}\""; then
                arduino-cli core update-index
                arduino-cli core install "${core}"
              else
                echo "Cached board core already installed: ${core}"
              fi
              echo "Local toolchain ready for ${fqbn}"
            '
        """.trimIndent()
        val intent = Intent(TERMUX_ACTION).apply {
            component = ComponentName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "${'$'}PREFIX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "${'$'}HOME")
            putExtra("com.termux.RUN_COMMAND_LABEL", "ESP Forge toolchain setup")
        }
        activity.startService(intent)
        true
    }.getOrElse { false }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) return it.getString(0) ?: ""
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: ""
}

private fun copyToSharedLibraryFolder(context: Context, source: Uri, name: String) {
    require(Build.VERSION.SDK_INT >= 29) { "Android 10 or newer is required for shared library storage." }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/ESPForge/LibraryZips/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        ?: throw IllegalStateException("Could not create shared library ZIP")
    context.contentResolver.openInputStream(source)!!.use { input ->
        context.contentResolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) }
    }
    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    context.contentResolver.update(uri, ready, null, null)
}



private fun searchArduinoLibraries(context: Context, query: String): List<ArduinoLibraryEntry> {
    require(query.length in 2..80) { "Enter at least 2 characters." }
    val cache = File(context.cacheDir, "arduino-library-index.json")
    if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > 12L * 60L * 60L * 1000L) {
        val connection = URL("https://downloads.arduino.cc/libraries/library_index.json").openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 45000
        connection.setRequestProperty("User-Agent", "ESP-Forge-IDE")
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "Arduino Library Manager returned HTTP ${connection.responseCode}." }
            val temp = File(cache.parentFile, cache.name + ".tmp")
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 80L * 1024L * 1024L) { "Library index exceeds the 80 MiB safety limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!temp.renameTo(cache)) {
                temp.copyTo(cache, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    val records = JSONObject(cache.readText(Charsets.UTF_8)).optJSONArray("libraries") ?: JSONArray()
    val latest = linkedMapOf<String, ArduinoLibraryEntry>()
    for (i in 0 until records.length()) {
        val item = records.optJSONObject(i) ?: continue
        val name = item.optString("name").trim()
        val version = item.optString("version").trim()
        val url = item.optString("url").trim()
        if (name.isBlank() || version.isBlank() || !url.startsWith("https://")) continue
        val candidate = ArduinoLibraryEntry(
            name = name,
            version = version,
            sentence = item.optString("sentence"),
            author = item.optString("author"),
            url = url
        )
        val previous = latest[name]
        if (previous == null || compareArduinoVersions(candidate.version, previous.version) > 0) {
            latest[name] = candidate
        }
    }
    return latest.values.asSequence()
        .filter { it.name.contains(query, ignoreCase = true) || it.sentence.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
        .sortedWith(compareBy<ArduinoLibraryEntry> { !it.name.startsWith(query, ignoreCase = true) }.thenBy { it.name.lowercase() })
        .take(25)
        .toList()
}

private fun compareArduinoVersions(first: String, second: String): Int {
    val a = Regex("\\d+").findAll(first).mapNotNull { it.value.toIntOrNull() }.toList()
    val b = Regex("\\d+").findAll(second).mapNotNull { it.value.toIntOrNull() }.toList()
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left.compareTo(right)
    }
    return first.compareTo(second, ignoreCase = true)
}

private fun downloadArduinoLibrary(context: Context, entry: ArduinoLibraryEntry): File {
    val safe = (entry.name + "-" + entry.version).replace(Regex("[^A-Za-z0-9_.-]"), "_")
    val directory = File(context.filesDir, "library-zips").apply { mkdirs() }
    val target = File(directory, "$safe.zip")
    if (target.isFile && target.length() > 0L) return target
    val connection = URL(entry.url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 60000
    connection.instanceFollowRedirects = true
    try {
        connection.connect()
        require(connection.responseCode in 200..299) { "Library download failed with HTTP ${connection.responseCode}." }
        val temp = File(directory, "$safe.zip.part")
        connection.inputStream.use { input ->
            temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 64L * 1024L * 1024L) { "Library archive exceeds the 64 MiB safety limit." }
                    output.write(buffer, 0, count)
                }
            }
        }
        require(temp.length() > 22L) { "Downloaded library archive is empty or invalid." }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    } finally {
        connection.disconnect()
    }
}

private fun copyLibraryFileToSharedFolder(context: Context, file: File) {
    require(Build.VERSION.SDK_INT >= 29) { "Android 10 or newer is required for local compiler library storage." }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/ESPForge/LibraryZips/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        ?: throw IllegalStateException("Could not copy the library to the compiler library folder.")
    try {
        context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: throw IllegalStateException("Could not open the compiler library destination.")
        val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, complete, null, null)
    } catch (error: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
}
