package com.espforge.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val boards = listOf("ESP32", "ESP32-S3", "ESP8266", "Arduino Uno", "Arduino Nano", "CH32", "STM32")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ForgeHome() } }
    }
}

@Composable
private fun ForgeHome() {
    var project by remember { mutableStateOf("MyFirmware") }
    var board by remember { mutableStateOf("ESP32") }
    var mode by remember { mutableStateOf("Online") }
    var source by remember { mutableStateOf("#include <Arduino.h>\n\nvoid setup() {\n  Serial.begin(115200);\n}\n\nvoid loop() {\n}\n") }
    var output by remember { mutableStateOf("Ready. Select a board and build mode.") }
    var tab by remember { mutableStateOf("Editor") }

    Scaffold(topBar = { TopAppBar(title = { Text("ESP Forge IDE") }) }) { insets ->
        Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(project, { project = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Target board", style = MaterialTheme.typography.titleMedium)
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(board, {}, readOnly = true, label = { Text("Board") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    boards.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { board = item; expanded = false }) }
                }
            }
            Text("Build mode", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "Online", onClick = { mode = "Online" }, label = { Text("Online") })
                FilterChip(selected = mode == "Offline", onClick = { mode = "Offline" }, label = { Text("Offline") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Editor", "Libraries", "Upload", "Serial").forEach { item ->
                    FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item) })
                }
            }
            when (tab) {
                "Editor" -> {
                    OutlinedTextField(source, { source = it }, label = { Text("main.ino / C++ source") }, minLines = 12, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { output = if (mode == "Online") "Online build requested. Connect a build service to compile and resolve libraries." else "Offline build selected. Install a compatible local toolchain and library ZIPs first." }, modifier = Modifier.fillMaxWidth()) { Text("Compile") }
                }
                "Libraries" -> {
                    Text("Online: libraries will be resolved by the remote build service from configured registries.")
                    Text("Offline: import library ZIPs into the local library store; export installed libraries as ZIPs.")
                    OutlinedButton(onClick = { output = "Library ZIP import/export UI is queued for implementation." }, modifier = Modifier.fillMaxWidth()) { Text("Manage library ZIPs") }
                }
                "Upload" -> {
                    Text("Firmware upload uses the phone's USB OTG connection after a successful build.")
                    Button(onClick = { output = "USB device detection and board-specific flashing are not wired yet." }, modifier = Modifier.fillMaxWidth()) { Text("Detect USB / Upload firmware") }
                }
                else -> {
                    Text("Serial monitor")
                    Text("USB serial transport and live log streaming are not wired yet.")
                    OutlinedButton(onClick = { output = "Serial monitor transport is queued for implementation." }, modifier = Modifier.fillMaxWidth()) { Text("Connect serial") }
                }
            }
            Text("Build / device output", style = MaterialTheme.typography.titleMedium)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(output, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
