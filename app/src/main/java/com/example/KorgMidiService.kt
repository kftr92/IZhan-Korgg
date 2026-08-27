package com.example

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class KorgConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class MidiTrafficLog(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: String,
    val direction: Direction,
    val summary: String,
    val hexDump: String
) {
    enum class Direction { IN, OUT, SYSTEM }
}

data class Esp32SlotDump(
    val slotIndex: Int,
    val triggerNote: Int,
    val isCombi: Boolean,
    val bankMSB: Int,
    val bankLSB: Int,
    val progNum: Int,
    val outChannel: Int,
    val outNote: Int,
    val outputVelocity: Int = 100,
    val buttonType: String = if (outNote < 127) "NOTE" else "PGM"
)

data class Esp32SlotNameRx(
    val slotIndex: Int,
    val name: String
)

class KorgMidiService : Service() {

    companion object {
        val BLE_MIDI_SERVICE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A028-510996E02885")
        val ESP32_BLE_MIDI_SERVICE_UUID: UUID = UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")
        val ESP32_BLE_MIDI_CHAR_UUID: UUID = UUID.fromString("7772e5db-3868-4112-a1a9-f2669d106bf3")

        // Custom SysEx Protocol Constants for ESP32-S3
        const val SYSEX_START: Byte = 0xF0.toByte()
        const val SYSEX_END: Byte = 0xF7.toByte()
        const val SYSEX_MANUFACTURER_ID: Byte = 0x7D.toByte() // Educational / Custom MIDI Manufacturer ID
        const val CMD_ESP32_SAVE_SLOT: Byte = 0x01.toByte()
        const val CMD_ESP32_DUMP_SLOT: Byte = 0x02.toByte()
        const val CMD_ESP32_REQ_DUMP: Byte = 0x03.toByte()
        const val CMD_ESP32_TRANSPOSE: Byte = 0x04.toByte()
        const val CMD_ESP32_SELECT_SLOT: Byte = 0x05.toByte()
        const val CMD_ESP32_SAVE_SLOT_NAME: Byte = 0x06.toByte()
        const val CMD_ESP32_RX_SLOT_NAME: Byte = 0x07.toByte()
    }

    private val binder = KorgMidiBinder()

    inner class KorgMidiBinder : Binder() {
        fun getService(): KorgMidiService = this@KorgMidiService
    }

    private lateinit var midiManager: MidiManager
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val _connectionStatus = MutableStateFlow(KorgConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<KorgConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("MIDI Manager initialized")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<MidiDeviceInfo>> = _availableDevices.asStateFlow()

    private val _selectedInputDeviceInfo = MutableStateFlow<MidiDeviceInfo?>(null)
    val selectedInputDeviceInfo: StateFlow<MidiDeviceInfo?> = _selectedInputDeviceInfo.asStateFlow()

    private val _selectedOutputDeviceInfo = MutableStateFlow<MidiDeviceInfo?>(null)
    val selectedOutputDeviceInfo: StateFlow<MidiDeviceInfo?> = _selectedOutputDeviceInfo.asStateFlow()

    private val _selectedDeviceInfo = MutableStateFlow<MidiDeviceInfo?>(null)
    val selectedDeviceInfo: StateFlow<MidiDeviceInfo?> = _selectedDeviceInfo.asStateFlow()

    private val _currentPatchInfo = MutableStateFlow<KorgPatchInfo?>(null)
    val currentPatchInfo: StateFlow<KorgPatchInfo?> = _currentPatchInfo.asStateFlow()

    private val _trafficLogs = MutableStateFlow<List<MidiTrafficLog>>(emptyList())
    val trafficLogs: StateFlow<List<MidiTrafficLog>> = _trafficLogs.asStateFlow()

    private val _incomingMidiEvent = MutableStateFlow<IncomingMidiInputEvent?>(null)
    val incomingMidiEvent: StateFlow<IncomingMidiInputEvent?> = _incomingMidiEvent.asStateFlow()

    private val _esp32SlotDump = MutableStateFlow<Esp32SlotDump?>(null)
    val esp32SlotDump: StateFlow<Esp32SlotDump?> = _esp32SlotDump.asStateFlow()

    private val _esp32IncomingTranspose = MutableStateFlow<Int?>(null)
    val esp32IncomingTranspose: StateFlow<Int?> = _esp32IncomingTranspose.asStateFlow()

    private val _esp32IncomingSelectSlot = MutableStateFlow<Int?>(null)
    val esp32IncomingSelectSlot: StateFlow<Int?> = _esp32IncomingSelectSlot.asStateFlow()

    private val _esp32SlotNameRx = MutableStateFlow<Esp32SlotNameRx?>(null)
    val esp32SlotNameRx: StateFlow<Esp32SlotNameRx?> = _esp32SlotNameRx.asStateFlow()

    // Diagnostic State (RAW MIDI, last parsed summary, last Sysex, etc.)
    private val _lastRawMidiHex = MutableStateFlow<String>("")
    val lastRawMidiHex: StateFlow<String> = _lastRawMidiHex.asStateFlow()

    private val _lastParsedMidiSummary = MutableStateFlow<String>("")
    val lastParsedMidiSummary: StateFlow<String> = _lastParsedMidiSummary.asStateFlow()

    private val _lastSysexHex = MutableStateFlow<String>("")
    val lastSysexHex: StateFlow<String> = _lastSysexHex.asStateFlow()

    private val _lastEsp32CommandSummary = MutableStateFlow<String>("")
    val lastEsp32CommandSummary: StateFlow<String> = _lastEsp32CommandSummary.asStateFlow()

    private val _scannedBleDevices = MutableStateFlow<List<BleMidiDevice>>(emptyList())
    val scannedBleDevices: StateFlow<List<BleMidiDevice>> = _scannedBleDevices.asStateFlow()

    private val _isScanningBle = MutableStateFlow(false)
    val isScanningBle: StateFlow<Boolean> = _isScanningBle.asStateFlow()

    private val _openedBleMidiDevices = mutableMapOf<String, MidiDevice>()

    private var activeInputMidiDevice: MidiDevice? = null
    private var activeOutputMidiDevice: MidiDevice? = null
    private var inputPort: MidiInputPort? = null
    private var outputPort: MidiOutputPort? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingInitRunnable: Runnable? = null

    private var currentMsb = 0
    private var currentLsb = 0
    private var currentMode = "Prog"

    private val sysexAccumulator = ByteArrayOutputStream()
    private var inSysex = false

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = try {
                device.name ?: result.scanRecord?.deviceName ?: "BLE MIDI (${device.address.takeLast(5)})"
            } catch (e: SecurityException) {
                "BLE MIDI (${device.address.takeLast(5)})"
            }
            val isBonded = try { device.bondState == BluetoothDevice.BOND_BONDED } catch (e: SecurityException) { false }
            val isConnected = _openedBleMidiDevices.containsKey(device.address)

            val item = BleMidiDevice(
                device = device,
                name = name,
                address = device.address,
                rssi = result.rssi,
                isBonded = isBonded,
                isConnected = isConnected
            )

            val currentList = _scannedBleDevices.value.toMutableList()
            val index = currentList.indexOfFirst { it.address == device.address }
            if (index >= 0) {
                currentList[index] = item
            } else {
                currentList.add(item)
                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] DEVICE FOUND", "$name (${device.address})")
            }
            _scannedBleDevices.value = currentList
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("KorgMidiService", "BLE Scan Failed: $errorCode")
            _isScanningBle.value = false
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] SCAN FAILED", "Error code: $errorCode")
        }
    }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] DEVICE ADDED", getDeviceDisplayName(device))
            refreshDevices()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] DEVICE REMOVED", getDeviceDisplayName(device))
            if (_selectedDeviceInfo.value?.id == device.id ||
                _selectedInputDeviceInfo.value?.id == device.id ||
                _selectedOutputDeviceInfo.value?.id == device.id) {
                disconnectDevice("MIDI device disconnected: ${getDeviceDisplayName(device)}")
            }
            refreshDevices()
        }
    }

    private val midiReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
            if (msg == null || count <= 0) return
            val rawBytes = msg.copyOfRange(offset, offset + count)
            val rawHex = bytesToHex(rawBytes)
            
            // --- DIAGNOSTIC LOGS & STATE ---
            val callbackDiag = "[MIDI CALLBACK] offset=$offset count=$count timestamp=$timestamp"
            val rawDiag = "[ANDROID MIDI RAW] $rawHex"
            _lastRawMidiHex.value = rawHex
            
            Log.d("KorgMidiService", callbackDiag)
            Log.d("KorgMidiService", rawDiag)
            logTraffic(MidiTrafficLog.Direction.IN, "$callbackDiag\n$rawDiag", rawHex)
            
            parseIncomingMidiBytes(msg, offset, count)
        }
    }

    override fun onCreate() {
        super.onCreate()
        midiManager = getSystemService(Context.MIDI_SERVICE) as MidiManager
        midiManager.registerDeviceCallback(deviceCallback, mainHandler)
        refreshDevices()
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "Korg MIDI Manager Service Started", "")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun refreshDevices() {
        val devices = midiManager.devices.filter { it.inputPortCount > 0 || it.outputPortCount > 0 }
        _availableDevices.value = devices

        if (devices.isEmpty() && activeInputMidiDevice == null && activeOutputMidiDevice == null) {
            _connectionStatus.value = KorgConnectionStatus.DISCONNECTED
            _statusMessage.value = "No MIDI hardware connected"
        }
    }

    fun isKorgDevice(device: MidiDeviceInfo): Boolean {
        val properties = device.properties
        val manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: ""
        val product = properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: ""
        val combined = "$manufacturer $name $product".uppercase(Locale.US)
        return combined.contains("KORG") || combined.contains("KROME") || combined.contains("KRONOS") ||
                combined.contains("MICROKORG") || combined.contains("MINILOGUE") || combined.contains("TRITON")
    }

    fun getDeviceDisplayName(device: MidiDeviceInfo): String {
        val properties = device.properties
        val product = properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        val manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        val baseName = product ?: name ?: manufacturer ?: "MIDI Device #${device.id}"
        return if (device.type == MidiDeviceInfo.TYPE_BLUETOOTH) {
            "⚡ BLE: $baseName"
        } else {
            baseName
        }
    }

    fun startBleScan() {
        if (_isScanningBle.value) return

        if (bluetoothAdapter == null) {
            bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _statusMessage.value = "Bluetooth is disabled"
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "BLE Scan: Bluetooth is disabled on device", "")
            return
        }

        try {
            // Collect bonded/paired Bluetooth devices first
            val bonded = try {
                adapter.bondedDevices?.map { dev ->
                    val devName = try { dev.name ?: dev.address } catch (e: SecurityException) { dev.address }
                    BleMidiDevice(
                        device = dev,
                        name = devName,
                        address = dev.address,
                        rssi = -50,
                        isBonded = true,
                        isConnected = _openedBleMidiDevices.containsKey(dev.address)
                    )
                } ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            }

            _scannedBleDevices.value = bonded
            _isScanningBle.value = true
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] SCAN START", "Starting low-latency BLE scan...")

            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                val filters = listOf(
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_MIDI_SERVICE_UUID)).build(),
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(ESP32_BLE_MIDI_SERVICE_UUID)).build()
                )
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

                try {
                    scanner.startScan(filters, settings, bleScanCallback)
                } catch (e: Exception) {
                    scanner.startScan(bleScanCallback)
                }

                // Auto stop scan after 12 seconds to save battery
                mainHandler.postDelayed({
                    stopBleScan()
                }, 12000)
            } else {
                _isScanningBle.value = false
                logTraffic(MidiTrafficLog.Direction.SYSTEM, "BLE Scanner is unavailable", "")
            }
        } catch (e: SecurityException) {
            Log.e("KorgMidiService", "SecurityException during BLE scan", e)
            _isScanningBle.value = false
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "BLE Scan permission required", e.message ?: "")
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error starting BLE scan", e)
            _isScanningBle.value = false
        }
    }

    fun stopBleScan() {
        if (!_isScanningBle.value) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        } catch (e: Exception) {
            // Ignore
        }
        _isScanningBle.value = false
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] SCAN STOPPED", "")
    }

    fun connectBleDevice(bluetoothDevice: BluetoothDevice, asInput: Boolean = true, asOutput: Boolean = true) {
        val devName = try { bluetoothDevice.name ?: bluetoothDevice.address } catch (e: SecurityException) { bluetoothDevice.address }
        
        // Clean disconnect any stale session prior to connecting
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] RECONNECT", "Disconnecting previous session before opening $devName")
        disconnectDevice("Preparing connection to $devName")

        _statusMessage.value = "Connecting to BLE MIDI: $devName..."
        _connectionStatus.value = KorgConnectionStatus.CONNECTING
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] DEVICE OPEN", "Connecting to $devName (${bluetoothDevice.address})")

        try {
            midiManager.openBluetoothDevice(bluetoothDevice, { midiDevice ->
                if (midiDevice != null) {
                    logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] DEVICE OPEN", "Success: $devName (id=${midiDevice.info.id})")
                    _openedBleMidiDevices[bluetoothDevice.address] = midiDevice
                    activeInputMidiDevice = midiDevice
                    activeOutputMidiDevice = midiDevice

                    // Open Input Receiver Port (reads incoming MIDI from peripheral)
                    if (asInput && midiDevice.info.outputPortCount > 0) {
                        try {
                            outputPort = midiDevice.openOutputPort(0)
                            if (outputPort != null) {
                                outputPort?.connect(midiReceiver)
                                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] INPUT PORT OPEN", "Port 0 opened")
                                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] RECEIVER ATTACHED", "MidiReceiver connected to outputPort 0")
                            } else {
                                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR", "Failed to open outputPort (Input Receiver)")
                            }
                        } catch (e: Exception) {
                            Log.e("KorgMidiService", "Error opening outputPort", e)
                            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR", "outputPort exception: ${e.localizedMessage}")
                        }
                    }

                    // Open Output Sender Port (transmits outgoing MIDI to peripheral)
                    if (asOutput && midiDevice.info.inputPortCount > 0) {
                        try {
                            inputPort = midiDevice.openInputPort(0)
                            if (inputPort != null) {
                                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] OUTPUT PORT OPEN", "Port 0 opened")
                            } else {
                                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR", "Failed to open inputPort (Output Sender)")
                            }
                        } catch (e: Exception) {
                            Log.e("KorgMidiService", "Error opening inputPort", e)
                            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR", "inputPort exception: ${e.localizedMessage}")
                        }
                    }

                    val isInputReady = !asInput || (outputPort != null)
                    val isOutputReady = !asOutput || (inputPort != null)

                    if (isInputReady && isOutputReady) {
                        _selectedDeviceInfo.value = midiDevice.info
                        _selectedInputDeviceInfo.value = if (asInput) midiDevice.info else null
                        _selectedOutputDeviceInfo.value = if (asOutput) midiDevice.info else null
                        _connectionStatus.value = KorgConnectionStatus.CONNECTED
                        _statusMessage.value = "Connected to $devName"
                        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] MIDI READY", "Input & Output ports active and verified")
                        logTraffic(MidiTrafficLog.Direction.SYSTEM, "BLE CONNECTED", devName)
                        logTraffic(MidiTrafficLog.Direction.SYSTEM, "ESP32 READY", "Ports active")

                        _scannedBleDevices.value = _scannedBleDevices.value.map {
                            if (it.address == bluetoothDevice.address) it.copy(isConnected = true) else it
                        }

                        // Schedule deferred patch request only after full readiness
                        pendingInitRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingInitRunnable = Runnable {
                            if (_connectionStatus.value == KorgConnectionStatus.CONNECTED && inputPort != null) {
                                requestCurrentSoundInfo(0)
                            }
                        }
                        mainHandler.postDelayed(pendingInitRunnable!!, 350)
                    } else {
                        _connectionStatus.value = KorgConnectionStatus.ERROR
                        _statusMessage.value = "Failed to initialize ports on $devName"
                        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR: Ports not ready", "InputReady=$isInputReady OutputReady=$isOutputReady")
                    }
                } else {
                    _connectionStatus.value = KorgConnectionStatus.ERROR
                    _statusMessage.value = "Failed to connect BLE MIDI: $devName"
                    logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR", "openBluetoothDevice returned null for $devName")
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error opening BLE MIDI device", e)
            _connectionStatus.value = KorgConnectionStatus.ERROR
            _statusMessage.value = "BLE Connection Error: ${e.localizedMessage}"
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR", e.localizedMessage ?: "Unknown connection error")
        }
    }

    fun disconnectBleDevice(bluetoothDevice: BluetoothDevice) {
        disconnectDevice("BLE Device Disconnected: ${bluetoothDevice.address}")
    }

    fun connectInputDevice(deviceInfo: MidiDeviceInfo?) {
        if (deviceInfo == null) {
            if (outputPort != null) {
                try {
                    outputPort?.disconnect(midiReceiver)
                    outputPort?.close()
                } catch (e: Exception) {}
                outputPort = null
                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] INPUT PORT CLOSED", "")
                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] RECEIVER DETACHED", "")
            }
            if (activeInputMidiDevice != null && activeInputMidiDevice != activeOutputMidiDevice) {
                try { activeInputMidiDevice?.close() } catch (e: Exception) {}
            }
            activeInputMidiDevice = null
            _selectedInputDeviceInfo.value = null
            if (inputPort == null) {
                _connectionStatus.value = KorgConnectionStatus.DISCONNECTED
            }
            return
        }

        if (_selectedInputDeviceInfo.value?.id == deviceInfo.id && activeInputMidiDevice != null && outputPort != null) {
            return
        }

        val name = getDeviceDisplayName(deviceInfo)
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] INPUT CONNECT", "Opening Input on $name")

        if (activeOutputMidiDevice != null && _selectedOutputDeviceInfo.value?.id == deviceInfo.id) {
            activeInputMidiDevice = activeOutputMidiDevice
            _selectedInputDeviceInfo.value = deviceInfo
            if (deviceInfo.outputPortCount > 0) {
                try {
                    outputPort = activeInputMidiDevice?.openOutputPort(0)
                    outputPort?.connect(midiReceiver)
                    logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] INPUT PORT OPEN", "Port 0")
                    logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] RECEIVER ATTACHED", "")
                } catch (e: Exception) {
                    Log.e("KorgMidiService", "Error opening outputPort", e)
                }
            }
            checkMidiReady(name)
        } else {
            midiManager.openDevice(deviceInfo, { device ->
                if (device != null) {
                    activeInputMidiDevice = device
                    _selectedInputDeviceInfo.value = deviceInfo
                    if (deviceInfo.outputPortCount > 0) {
                        try {
                            outputPort = device.openOutputPort(0)
                            outputPort?.connect(midiReceiver)
                            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] INPUT PORT OPEN", "Port 0")
                            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] RECEIVER ATTACHED", "")
                        } catch (e: Exception) {
                            Log.e("KorgMidiService", "Error opening outputPort", e)
                        }
                    }
                    checkMidiReady(name)
                } else {
                    logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR", "Failed to open device for Input: $name")
                }
            }, mainHandler)
        }
    }

    fun connectOutputDevice(deviceInfo: MidiDeviceInfo?) {
        if (deviceInfo == null) {
            if (inputPort != null) {
                try { inputPort?.close() } catch (e: Exception) {}
                inputPort = null
                logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] OUTPUT PORT CLOSED", "")
            }
            if (activeOutputMidiDevice != null && activeOutputMidiDevice != activeInputMidiDevice) {
                try { activeOutputMidiDevice?.close() } catch (e: Exception) {}
            }
            activeOutputMidiDevice = null
            _selectedOutputDeviceInfo.value = null
            _selectedDeviceInfo.value = null
            if (outputPort == null) {
                _connectionStatus.value = KorgConnectionStatus.DISCONNECTED
            }
            return
        }

        if (_selectedOutputDeviceInfo.value?.id == deviceInfo.id && activeOutputMidiDevice != null && inputPort != null) {
            return
        }

        val name = getDeviceDisplayName(deviceInfo)
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] OUTPUT CONNECT", "Opening Output on $name")

        if (activeInputMidiDevice != null && _selectedInputDeviceInfo.value?.id == deviceInfo.id) {
            activeOutputMidiDevice = activeInputMidiDevice
            _selectedOutputDeviceInfo.value = deviceInfo
            _selectedDeviceInfo.value = deviceInfo
            if (deviceInfo.inputPortCount > 0) {
                try {
                    inputPort = activeOutputMidiDevice?.openInputPort(0)
                    logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] OUTPUT PORT OPEN", "Port 0")
                } catch (e: Exception) {
                    Log.e("KorgMidiService", "Error opening inputPort", e)
                }
            }
            checkMidiReady(name)
        } else {
            midiManager.openDevice(deviceInfo, { device ->
                if (device != null) {
                    activeOutputMidiDevice = device
                    _selectedOutputDeviceInfo.value = deviceInfo
                    _selectedDeviceInfo.value = deviceInfo
                    if (deviceInfo.inputPortCount > 0) {
                        try {
                            inputPort = device.openInputPort(0)
                            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] OUTPUT PORT OPEN", "Port 0")
                        } catch (e: Exception) {
                            Log.e("KorgMidiService", "Error opening inputPort", e)
                        }
                    }
                    checkMidiReady(name)
                } else {
                    _connectionStatus.value = KorgConnectionStatus.ERROR
                    _statusMessage.value = "Failed to open $name"
                    logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] ERROR", "Failed to open device for Output: $name")
                }
            }, mainHandler)
        }
    }

    private fun checkMidiReady(deviceName: String) {
        val isInputConnected = (outputPort != null) || (_selectedInputDeviceInfo.value == null)
        val isOutputConnected = (inputPort != null) || (_selectedOutputDeviceInfo.value == null)

        if (isInputConnected && isOutputConnected && (inputPort != null || outputPort != null)) {
            _connectionStatus.value = KorgConnectionStatus.CONNECTED
            _statusMessage.value = "Connected to $deviceName"
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] MIDI READY", "Ports active")
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "BLE CONNECTED", deviceName)

            if (inputPort != null) {
                logTraffic(MidiTrafficLog.Direction.SYSTEM, "ESP32 READY", "Ports active")
                pendingInitRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingInitRunnable = Runnable {
                    if (_connectionStatus.value == KorgConnectionStatus.CONNECTED && inputPort != null) {
                        requestCurrentSoundInfo(0)
                    }
                }
                mainHandler.postDelayed(pendingInitRunnable!!, 350)
            }
        }
    }

    fun connectDevice(deviceInfo: MidiDeviceInfo) {
        connectInputDevice(deviceInfo)
        connectOutputDevice(deviceInfo)
    }

    fun disconnectDevice(reason: String = "User requested disconnect") {
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] DISCONNECT", reason)

        pendingInitRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingInitRunnable = null
        }

        if (outputPort != null) {
            try {
                outputPort?.disconnect(midiReceiver)
                outputPort?.close()
            } catch (e: Exception) {
                Log.e("KorgMidiService", "Error closing outputPort", e)
            }
            outputPort = null
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] INPUT PORT CLOSED", "")
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] RECEIVER DETACHED", "")
        }

        if (inputPort != null) {
            try {
                inputPort?.close()
            } catch (e: Exception) {
                Log.e("KorgMidiService", "Error closing inputPort", e)
            }
            inputPort = null
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] OUTPUT PORT CLOSED", "")
        }

        val devicesToClose = mutableSetOf<MidiDevice>()
        activeInputMidiDevice?.let { devicesToClose.add(it) }
        activeOutputMidiDevice?.let { devicesToClose.add(it) }
        _openedBleMidiDevices.values.forEach { devicesToClose.add(it) }

        devicesToClose.forEach { dev ->
            try {
                dev.close()
            } catch (e: Exception) {
                Log.e("KorgMidiService", "Error closing MidiDevice", e)
            }
        }
        _openedBleMidiDevices.clear()
        activeInputMidiDevice = null
        activeOutputMidiDevice = null
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[BLE LIFECYCLE] DEVICE CLOSED", "")

        _selectedInputDeviceInfo.value = null
        _selectedOutputDeviceInfo.value = null
        _selectedDeviceInfo.value = null
        _connectionStatus.value = KorgConnectionStatus.DISCONNECTED
        _statusMessage.value = reason
        inSysex = false
        sysexAccumulator.reset()

        _scannedBleDevices.value = _scannedBleDevices.value.map { it.copy(isConnected = false) }
        logTraffic(MidiTrafficLog.Direction.SYSTEM, "Disconnected", reason)
    }

    // --- MIDI TRANSMISSION COMMANDS ---

    fun sendProgramChange(channel: Int, msb: Int, lsb: Int, program: Int) {
        val port = inputPort ?: run {
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "TX Skipped: Port Closed", "Attempted PC $program")
            return
        }
        try {
            val ch = channel.coerceIn(0, 15)

            // Bank Select MSB (CC 0)
            val msbBuffer = byteArrayOf((0xB0 or ch).toByte(), 0x00, msb.toByte())
            port.send(msbBuffer, 0, 3)

            // Bank Select LSB (CC 32)
            val lsbBuffer = byteArrayOf((0xB0 or ch).toByte(), 0x20, lsb.toByte())
            port.send(lsbBuffer, 0, 3)

            // Program Change
            val pcBuffer = byteArrayOf((0xC0 or ch).toByte(), program.toByte())
            port.send(pcBuffer, 0, 2)

            logTraffic(
                MidiTrafficLog.Direction.OUT,
                "TX: Bank MSB $msb LSB $lsb, PC $program (Ch ${ch + 1})",
                "${bytesToHex(msbBuffer)} ${bytesToHex(lsbBuffer)} ${bytesToHex(pcBuffer)}"
            )
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending Program Change", e)
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "TX Error: Program Change", e.localizedMessage ?: "Unknown error")
        }
    }

    fun sendNoteOn(channel: Int, note: Int, velocity: Int) {
        val port = inputPort ?: return
        try {
            val ch = channel.coerceIn(0, 15)
            val buffer = byteArrayOf((0x90 or ch).toByte(), note.coerceIn(0, 127).toByte(), velocity.coerceIn(0, 127).toByte())
            port.send(buffer, 0, 3)
            logTraffic(MidiTrafficLog.Direction.OUT, "TX: Note On $note Vel $velocity (Ch ${ch + 1})", bytesToHex(buffer))
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending Note On", e)
        }
    }

    fun sendNoteOff(channel: Int, note: Int) {
        val port = inputPort ?: return
        try {
            val ch = channel.coerceIn(0, 15)
            val buffer = byteArrayOf((0x80 or ch).toByte(), note.coerceIn(0, 127).toByte(), 0x00)
            port.send(buffer, 0, 3)
            logTraffic(MidiTrafficLog.Direction.OUT, "TX: Note Off $note (Ch ${ch + 1})", bytesToHex(buffer))
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending Note Off", e)
        }
    }

    fun sendPitchBend(channel: Int, value: Int) {
        val port = inputPort ?: return
        try {
            val ch = channel.coerceIn(0, 15)
            val clamped = value.coerceIn(0, 16383)
            val lsb = (clamped and 0x7F).toByte()
            val msb = ((clamped shr 7) and 0x7F).toByte()
            val buffer = byteArrayOf((0xE0 or ch).toByte(), lsb, msb)
            port.send(buffer, 0, 3)
            logTraffic(MidiTrafficLog.Direction.OUT, "TX: Pitch Bend $clamped (Ch ${ch + 1})", bytesToHex(buffer))
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending Pitch Bend", e)
        }
    }

    fun sendMasterCoarseTune(channel: Int, transpose: Int) {
        val port = inputPort ?: return
        try {
            val ch = channel.coerceIn(0, 15)
            val mm = (64 + transpose.coerceIn(-12, 12)).toByte()
            val sysex = byteArrayOf(
                0xF0.toByte(), 0x7F.toByte(), ch.toByte(), 0x04.toByte(),
                0x04.toByte(), 0x00.toByte(), mm, 0xF7.toByte()
            )
            port.send(sysex, 0, sysex.size)
            logTraffic(MidiTrafficLog.Direction.OUT, "TX SysEx: Master Transpose ${if (transpose >= 0) "+$transpose" else "$transpose"} semitones", bytesToHex(sysex))
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending Transpose SysEx", e)
        }
    }

    fun sendModeChange(channel: Int, mode: Int) {
        val port = inputPort ?: return
        try {
            val ch = channel.coerceIn(0, 15)
            val modeName = if (mode == 0) "Combi" else if (mode == 2) "Prog" else "Seq"
            val sysex = byteArrayOf(
                0xF0.toByte(), 0x42.toByte(), (0x30 or ch).toByte(), 0x00.toByte(),
                0x01.toByte(), 0x15.toByte(), 0x4E.toByte(), mode.toByte(), 0xF7.toByte()
            )
            port.send(sysex, 0, sysex.size)
            logTraffic(MidiTrafficLog.Direction.OUT, "TX SysEx: Korg Mode Switch to $modeName", bytesToHex(sysex))
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending Mode Change SysEx", e)
        }
    }

    fun requestCurrentSoundInfo(channel: Int = 0) {
        val port = inputPort ?: return
        try {
            val ch = (0x30 or (channel and 0x0F)).toByte()

            val modeReq = byteArrayOf(0xF0.toByte(), 0x42.toByte(), ch, 0x00, 0x01, 0x15, 0x12.toByte(), 0xF7.toByte())
            port.send(modeReq, 0, modeReq.size)

            val progReq = byteArrayOf(0xF0.toByte(), 0x42.toByte(), ch, 0x00, 0x01, 0x15, 0x10.toByte(), 0xF7.toByte())
            port.send(progReq, 0, progReq.size)

            val currentReq = byteArrayOf(0xF0.toByte(), 0x42.toByte(), ch, 0x00, 0x01, 0x15, 0x1C.toByte(), 0xF7.toByte())
            port.send(currentReq, 0, currentReq.size)

            val idReq = byteArrayOf(0xF0.toByte(), 0x7E.toByte(), 0x7F.toByte(), 0x06.toByte(), 0x01.toByte(), 0xF7.toByte())
            port.send(idReq, 0, idReq.size)

            logTraffic(MidiTrafficLog.Direction.OUT, "TX SysEx: Sound & Parameter Info Requests Sent to Korg Hardware", bytesToHex(modeReq))
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error requesting sound info SysEx", e)
        }
    }

    fun sendEsp32SlotConfig(
        slotIndex: Int,
        triggerNote: Int,
        isCombi: Int,
        bankMSB: Int,
        bankLSB: Int,
        progNum: Int,
        outChannel: Int,
        outNote: Int,
        outputVelocity: Int = 100,
        buttonTypeCode: Int = 0
    ) {
        val port = inputPort ?: run {
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "ESP32 Save Skipped: Port Closed", "Slot $slotIndex")
            return
        }
        try {
            val sIdx = slotIndex.coerceIn(0, 127).toByte()
            val tNote = triggerNote.coerceIn(0, 127).toByte()
            val combi = (if (isCombi != 0) 1 else 0).toByte()
            val msb = bankMSB.coerceIn(0, 127).toByte()
            val lsb = bankLSB.coerceIn(0, 127).toByte()
            val prog = progNum.coerceIn(0, 127).toByte()
            val ch = outChannel.coerceIn(0, 15).toByte()
            val oNote = outNote.coerceIn(0, 127).toByte()
            val vel = outputVelocity.coerceIn(1, 127).toByte()
            val btnCode = buttonTypeCode.coerceIn(0, 127).toByte()

            val sysex = byteArrayOf(
                SYSEX_START,
                SYSEX_MANUFACTURER_ID,
                CMD_ESP32_SAVE_SLOT,
                sIdx,
                tNote,
                combi,
                msb,
                lsb,
                prog,
                ch,
                oNote,
                vel,
                btnCode,
                SYSEX_END
            )
            port.send(sysex, 0, sysex.size)
            val btnStr = when (buttonTypeCode) {
                1 -> "NOTE"
                2 -> "CC"
                3 -> "SX"
                4 -> "CUST"
                else -> "PGM"
            }
            logTraffic(
                MidiTrafficLog.Direction.OUT,
                "[ESP32 TX SLOT] index=$slotIndex slot=${slotIndex + 1} type=$btnStr trig=$triggerNote outNote=$outNote vel=$outputVelocity",
                bytesToHex(sysex)
            )
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending ESP32 Save Slot SysEx", e)
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "TX Error: ESP32 Save Slot", e.localizedMessage ?: "Unknown error")
        }
    }

    fun sendEsp32SlotName(slotIndex: Int, name: String?) {
        val port = inputPort ?: run {
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "ESP32 Slot Name Skipped: Port Closed", "Slot $slotIndex")
            return
        }
        try {
            val sIdx = slotIndex.coerceIn(0, 127).toByte()
            val safeName = name ?: ""
            val rawBytes = safeName.toByteArray(Charsets.UTF_8)
            val nameBytes = if (rawBytes.size > 24) rawBytes.copyOfRange(0, 24) else rawBytes
            val nameLen = nameBytes.size.toByte()

            val sysex = ByteArray(5 + nameBytes.size + 1)
            sysex[0] = SYSEX_START
            sysex[1] = SYSEX_MANUFACTURER_ID
            sysex[2] = CMD_ESP32_SAVE_SLOT_NAME
            sysex[3] = sIdx
            sysex[4] = nameLen
            if (nameBytes.isNotEmpty()) {
                System.arraycopy(nameBytes, 0, sysex, 5, nameBytes.size)
            }
            sysex[sysex.size - 1] = SYSEX_END

            port.send(sysex, 0, sysex.size)
            val hexDump = bytesToHex(sysex)
            logTraffic(
                MidiTrafficLog.Direction.OUT,
                "[NAME SYNC] SLOT=${slotIndex + 1} NAME=\"$safeName\"",
                "[NAME SYNC HEX] $hexDump"
            )
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending ESP32 Slot Name SysEx", e)
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "TX Error: ESP32 Slot Name", e.localizedMessage ?: "Unknown error")
        }
    }

    fun sendEsp32Transpose(transpose: Int) {
        val port = inputPort ?: run {
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "ESP32 Transpose Skipped: Port Closed", "Val: $transpose")
            return
        }
        try {
            val clamped = transpose.coerceIn(-12, 12)
            val encoded = (clamped + 12).toByte()
            val sysex = byteArrayOf(
                SYSEX_START,
                SYSEX_MANUFACTURER_ID,
                CMD_ESP32_TRANSPOSE,
                encoded,
                SYSEX_END
            )
            port.send(sysex, 0, sysex.size)
            val signStr = if (clamped > 0) "+$clamped" else "$clamped"
            logTraffic(
                MidiTrafficLog.Direction.OUT,
                "TX ESP32 TRANSPOSE: $signStr",
                bytesToHex(sysex)
            )
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending ESP32 Transpose SysEx", e)
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "TX Error: ESP32 Transpose", e.localizedMessage ?: "Unknown error")
        }
    }

    fun sendEsp32SelectSlot(slotIndex: Int) {
        val port = inputPort ?: run {
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "ESP32 Select Slot Skipped: Port Closed", "Slot $slotIndex")
            return
        }
        try {
            val slotIdx = slotIndex.coerceIn(0, 127).toByte()
            val sysex = byteArrayOf(
                SYSEX_START,
                SYSEX_MANUFACTURER_ID,
                CMD_ESP32_SELECT_SLOT,
                slotIdx,
                SYSEX_END
            )
            port.send(sysex, 0, sysex.size)
            logTraffic(
                MidiTrafficLog.Direction.OUT,
                "[ESP32 TX SELECT] index=$slotIndex slot=${slotIndex + 1}",
                bytesToHex(sysex)
            )
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending ESP32 Select Slot SysEx", e)
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "TX Error: ESP32 Select Slot", e.localizedMessage ?: "Unknown error")
        }
    }

    fun sendSysexHex(hexString: String) {
        val port = inputPort ?: return
        try {
            val cleanHex = hexString.replace(" ", "").replace("0x", "", ignoreCase = true)
            if (cleanHex.length % 2 != 0 || cleanHex.isEmpty()) return
            val byteArray = ByteArray(cleanHex.length / 2)
            for (i in byteArray.indices) {
                val index = i * 2
                byteArray[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
            }
            port.send(byteArray, 0, byteArray.size)
            logTraffic(MidiTrafficLog.Direction.OUT, "TX Custom SysEx (${byteArray.size} bytes)", bytesToHex(byteArray))
        } catch (e: Exception) {
            Log.e("KorgMidiService", "Error sending custom SysEx", e)
        }
    }

    fun updatePatchStateLocally(msb: Int, lsb: Int, program: Int, mode: String, customName: String? = null) {
        currentMsb = msb
        currentLsb = lsb
        currentMode = mode
        _currentPatchInfo.value = KorgPatchInfo(msb, lsb, program, mode, customName)
    }

    // --- INCOMING MIDI PARSER ---

    private fun parseIncomingMidiBytes(msg: ByteArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count

        // BLE-MIDI Header stripping:
        // Header byte has bit 7=1, bit 6=0 (0x80..0xBF)
        // Timestamp byte has bit 7=1 (0x80..0xFF)
        if (!inSysex && count >= 2) {
            val b0 = msg[offset].toInt() and 0xFF
            val b1 = msg[offset + 1].toInt() and 0xFF
            if ((b0 and 0xC0) == 0x80 && (b1 and 0x80) != 0 && b1 != 0xF7 && b1 != 0xF0) {
                // Strip BLE-MIDI header and timestamp byte
                i = offset + 2
            } else if ((b0 and 0xC0) == 0x80 && (b1 == 0xF0 || (b1 and 0x80) != 0)) {
                i = offset + 1
            }
        } else if (inSysex && count >= 1) {
            val b0 = msg[offset].toInt() and 0xFF
            if ((b0 and 0xC0) == 0x80) {
                if (count >= 2) {
                    val b1 = msg[offset + 1].toInt() and 0xFF
                    if ((b1 and 0x80) != 0 && b1 != 0xF7) {
                        i = offset + 2
                    } else {
                        i = offset + 1
                    }
                } else {
                    i = offset + 1
                }
            }
        }

        while (i < end) {
            val b = msg[i].toInt() and 0xFF

            if (inSysex) {
                if (b == 0xF7) {
                    sysexAccumulator.write(0xF7)
                    inSysex = false
                    val sysexBytes = sysexAccumulator.toByteArray()
                    val sysexHex = bytesToHex(sysexBytes)
                    logTraffic(MidiTrafficLog.Direction.IN, "[BLE MIDI DECODE] $sysexHex", "")
                    Log.d("KorgMidiService", "[BLE MIDI DECODE] $sysexHex")
                    processReceivedSysex(sysexBytes)
                    sysexAccumulator.reset()
                } else if (b == 0xF0) {
                    sysexAccumulator.reset()
                    sysexAccumulator.write(0xF0)
                } else if (b < 0x80) {
                    // Valid 7-bit SysEx payload byte
                    sysexAccumulator.write(b)

                    // Special check for ESP32 custom command: F0 7D 05 <slotIndex>
                    // Custom CMD 05 has a fixed 4-byte format and completes without requiring trailing 0xF7
                    if (sysexAccumulator.size() == 4) {
                        val currentBuf = sysexAccumulator.toByteArray()
                        if ((currentBuf[0].toInt() and 0xFF) == 0xF0 &&
                            (currentBuf[1].toInt() and 0xFF) == 0x7D &&
                            (currentBuf[2].toInt() and 0xFF) == 0x05) {

                            inSysex = false
                            val sysexHex = bytesToHex(currentBuf)
                            logTraffic(MidiTrafficLog.Direction.IN, "[BLE MIDI DECODE] $sysexHex", "")
                            Log.d("KorgMidiService", "[BLE MIDI DECODE] $sysexHex")
                            processReceivedSysex(currentBuf)
                            sysexAccumulator.reset()
                        }
                    }
                } else {
                    // Skip intermediate BLE-MIDI timestamp / realtime header bytes inside SysEx stream
                }
                i++
                continue
            }

            if (b == 0xF0) {
                inSysex = true
                sysexAccumulator.reset()
                sysexAccumulator.write(b)
                i++
                continue
            }

            // In BLE-MIDI stream, an intermediate timestamp byte (>= 0x80) can precede a status byte (>= 0x80)
            if (i + 1 < end && b >= 0x80 && (msg[i + 1].toInt() and 0x80) != 0 && (msg[i + 1].toInt() and 0xFF) != 0xF7) {
                i++
                continue
            }

            if (b >= 0x80) { // Status byte
                val type = b and 0xF0
                val ch = b and 0x0F
                val chDisplay = ch + 1

                if (type == 0x90) { // Note On (or Note Off if vel == 0)
                    if (i + 2 < end) {
                        val note = msg[i + 1].toInt() and 0x7F
                        val vel = msg[i + 2].toInt() and 0x7F
                        val isNoteOn = (vel > 0)
                        val eventType = if (isNoteOn) MidiEventType.NOTE_ON else MidiEventType.NOTE_OFF

                        val midiHex = bytesToHex(byteArrayOf(b.toByte(), note.toByte(), vel.toByte()))
                        logTraffic(MidiTrafficLog.Direction.IN, "[BLE MIDI DECODE] $midiHex", "")
                        Log.d("KorgMidiService", "[BLE MIDI DECODE] $midiHex")

                        val summary = if (isNoteOn) {
                            "[BLE MIDI RX] NOTE ON ch=$chDisplay note=$note velocity=$vel"
                        } else {
                            "[BLE MIDI RX] NOTE OFF ch=$chDisplay note=$note velocity=$vel"
                        }
                        val diagParsed = "[MIDI PARSED] type=${if (isNoteOn) "NOTE_ON" else "NOTE_OFF"} ch=$chDisplay note=$note velocity=$vel"
                        _lastParsedMidiSummary.value = diagParsed
                        Log.d("KorgMidiService", diagParsed)
                        logTraffic(MidiTrafficLog.Direction.IN, "$summary\n$diagParsed", "")
                        Log.d("KorgMidiService", summary)

                        _incomingMidiEvent.value = null
                        _incomingMidiEvent.value = IncomingMidiInputEvent(ch, eventType, note, vel)
                        i += 3
                    } else {
                        i = end
                    }
                } else if (type == 0x80) { // Note Off
                    if (i + 2 < end) {
                        val note = msg[i + 1].toInt() and 0x7F
                        val vel = msg[i + 2].toInt() and 0x7F

                        val midiHex = bytesToHex(byteArrayOf(b.toByte(), note.toByte(), vel.toByte()))
                        logTraffic(MidiTrafficLog.Direction.IN, "[BLE MIDI DECODE] $midiHex", "")
                        Log.d("KorgMidiService", "[BLE MIDI DECODE] $midiHex")

                        val summary = "[BLE MIDI RX] NOTE OFF ch=$chDisplay note=$note velocity=$vel"
                        val diagParsed = "[MIDI PARSED] type=NOTE_OFF ch=$chDisplay note=$note velocity=$vel"
                        _lastParsedMidiSummary.value = diagParsed
                        Log.d("KorgMidiService", diagParsed)
                        logTraffic(MidiTrafficLog.Direction.IN, "$summary\n$diagParsed", "")
                        Log.d("KorgMidiService", summary)

                        _incomingMidiEvent.value = null
                        _incomingMidiEvent.value = IncomingMidiInputEvent(ch, MidiEventType.NOTE_OFF, note, vel)
                        i += 3
                    } else {
                        i = end
                    }
                } else if (type == 0xB0) { // Control Change
                    if (i + 2 < end) {
                        val cc = msg[i + 1].toInt() and 0x7F
                        val value = msg[i + 2].toInt() and 0x7F
                        if (cc == 0) currentMsb = value
                        else if (cc == 32) currentLsb = value

                        val midiHex = bytesToHex(byteArrayOf(b.toByte(), cc.toByte(), value.toByte()))
                        logTraffic(MidiTrafficLog.Direction.IN, "[BLE MIDI DECODE] $midiHex", "")
                        Log.d("KorgMidiService", "[BLE MIDI DECODE] $midiHex")

                        val summary = "[BLE MIDI RX] CC $cc = $value (ch=$chDisplay)"
                        logTraffic(MidiTrafficLog.Direction.IN, summary, "")
                        Log.d("KorgMidiService", summary)

                        _incomingMidiEvent.value = IncomingMidiInputEvent(ch, MidiEventType.CONTROL_CHANGE, cc, value)

                        // Direct forward CC / Modulation to MIDI Output
                        val port = inputPort
                        if (port != null) {
                            try {
                                val ccBuffer = byteArrayOf((0xB0 or ch).toByte(), cc.toByte(), value.toByte())
                                port.send(ccBuffer, 0, 3)
                                if (cc == 1) {
                                    logTraffic(MidiTrafficLog.Direction.OUT, "Direct Forward Modulation (CC 1=$value, Ch $chDisplay)", bytesToHex(ccBuffer))
                                }
                            } catch (e: Exception) {
                                Log.e("KorgMidiService", "Error forwarding CC", e)
                            }
                        }

                        i += 3
                    } else break
                } else if (type == 0xC0) { // Program Change
                    if (i + 1 < end) {
                        val pc = msg[i + 1].toInt() and 0x7F
                        val prevName = _currentPatchInfo.value?.customName
                        _currentPatchInfo.value = KorgPatchInfo(currentMsb, currentLsb, pc, currentMode, prevName)

                        val midiHex = bytesToHex(byteArrayOf(b.toByte(), pc.toByte()))
                        logTraffic(MidiTrafficLog.Direction.IN, "[BLE MIDI DECODE] $midiHex", "")
                        Log.d("KorgMidiService", "[BLE MIDI DECODE] $midiHex")

                        val summary = "[BLE MIDI RX] PROGRAM CHANGE $pc (ch=$chDisplay)"
                        logTraffic(MidiTrafficLog.Direction.IN, summary, "")
                        Log.d("KorgMidiService", summary)

                        _incomingMidiEvent.value = IncomingMidiInputEvent(ch, MidiEventType.PROGRAM_CHANGE, pc, 0)
                        i += 2
                        requestCurrentSoundInfo(0)
                    } else break
                } else if (type == 0xA0 || type == 0xE0) {
                    if (type == 0xE0 && i + 2 < end) {
                        val lsb = msg[i + 1].toInt() and 0x7F
                        val msb = msg[i + 2].toInt() and 0x7F
                        val pbValue = (msb shl 7) or lsb

                        val midiHex = bytesToHex(byteArrayOf(b.toByte(), lsb.toByte(), msb.toByte()))
                        logTraffic(MidiTrafficLog.Direction.IN, "[BLE MIDI DECODE] $midiHex", "")
                        Log.d("KorgMidiService", "[BLE MIDI DECODE] $midiHex")

                        val summary = "[BLE MIDI RX] Pitch Bend $pbValue (ch=$chDisplay)"
                        logTraffic(MidiTrafficLog.Direction.IN, summary, "")
                        Log.d("KorgMidiService", summary)

                        _incomingMidiEvent.value = IncomingMidiInputEvent(ch, MidiEventType.PITCH_BEND, pbValue, 0)

                        // Direct forward Pitch Bend to MIDI Output
                        val port = inputPort
                        if (port != null) {
                            try {
                                val pbBuffer = byteArrayOf((0xE0 or ch).toByte(), lsb.toByte(), msb.toByte())
                                port.send(pbBuffer, 0, 3)
                                logTraffic(MidiTrafficLog.Direction.OUT, "Direct Forward Pitch Bend $pbValue (Ch $chDisplay)", bytesToHex(pbBuffer))
                            } catch (e: Exception) {
                                Log.e("KorgMidiService", "Error forwarding Pitch Bend", e)
                            }
                        }

                        i += 3
                    } else {
                        i += 3
                    }
                } else if (type == 0xD0) {
                    i += 2
                } else {
                    i++
                }
            } else {
                i++
            }
        }
    }

    private fun processReceivedSysex(sysexBytes: ByteArray) {
        if (sysexBytes.size < 4) {
            logTraffic(MidiTrafficLog.Direction.SYSTEM, "[ESP32 BLE RX ERROR]\nMalformed SysEx (size < 4)", bytesToHex(sysexBytes))
            return
        }

        val sysexHexStr = bytesToHex(sysexBytes)
        _lastSysexHex.value = sysexHexStr

        val b0 = sysexBytes[0].toInt() and 0xFF
        val b1 = sysexBytes[1].toInt() and 0xFF

        // Check for ESP32 Custom SysEx Protocol (Manufacturer ID 0x7D)
        if (b0 == 0xF0 && b1 == 0x7D) {
            val cmd = if (sysexBytes.size > 2) sysexBytes[2].toInt() and 0xFF else 0
            when (cmd) {
                0x01, 0x02 -> { // SAVE_SLOT (0x01) or SLOT_DUMP (0x02)
                    if (sysexBytes.size >= 12) {
                        val slotIndex = sysexBytes[3].toInt() and 0x7F
                        val triggerNote = sysexBytes[4].toInt() and 0x7F
                        val isCombi = (sysexBytes[5].toInt() and 0x7F) == 1
                        val bankMSB = sysexBytes[6].toInt() and 0x7F
                        val bankLSB = sysexBytes[7].toInt() and 0x7F
                        val progNum = sysexBytes[8].toInt() and 0x7F
                        val outChannel = sysexBytes[9].toInt() and 0x0F
                        val outNote = sysexBytes[10].toInt() and 0x7F
                        
                        // Support extended payload (size >= 14 with velocity and buttonType)
                        val velocity = if (sysexBytes.size >= 14) (sysexBytes[11].toInt() and 0x7F).coerceIn(1, 127) else 100
                        val btnCode = if (sysexBytes.size >= 14) (sysexBytes[12].toInt() and 0x7F) else (if (outNote < 127) 1 else 0)
                        val buttonTypeStr = when (btnCode) {
                            0 -> "PGM"
                            1 -> "NOTE"
                            2 -> "CC"
                            3 -> "SX"
                            4 -> "CUST"
                            else -> if (outNote < 127) "NOTE" else "PGM"
                        }

                        val dump = Esp32SlotDump(
                            slotIndex = slotIndex,
                            triggerNote = triggerNote,
                            isCombi = isCombi,
                            bankMSB = bankMSB,
                            bankLSB = bankLSB,
                            progNum = progNum,
                            outChannel = outChannel,
                            outNote = outNote,
                            outputVelocity = velocity,
                            buttonType = buttonTypeStr
                        )
                        _esp32SlotDump.value = dump
                        val slotNumber = slotIndex + 1
                        val summary = "[ESP32 RX] CMD=0x${cmd.toString(16).uppercase()} DUMP index=$slotIndex slot=$slotNumber\nTrigger=$triggerNote\nType=$buttonTypeStr\nCombi=${if (isCombi) 1 else 0}\nBankMSB=$bankMSB\nBankLSB=$bankLSB\nProgram=$progNum\nChannel=${outChannel + 1}\nOutNote=$outNote\nVelocity=$velocity"
                        _lastEsp32CommandSummary.value = "CMD 02 (Dump Slot $slotNumber)"
                        logTraffic(MidiTrafficLog.Direction.IN, summary, bytesToHex(sysexBytes))
                        Log.d("KorgMidiService", summary)
                    } else {
                        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[ESP32 BLE RX ERROR]\nInvalid Slot Packet size: ${sysexBytes.size} bytes", bytesToHex(sysexBytes))
                    }
                }
                0x04 -> { // TRANSPOSE (0x04)
                    if (sysexBytes.size >= 5) {
                        val encoded = sysexBytes[3].toInt() and 0xFF
                        val rxTranspose = (encoded - 12).coerceIn(-12, 12)
                        _esp32IncomingTranspose.value = rxTranspose
                        val signStr = if (rxTranspose > 0) "+$rxTranspose" else "$rxTranspose"
                        val summary = "[ESP32 RX] CMD=04 TRANSPOSE $signStr"
                        _lastEsp32CommandSummary.value = "CMD 04 (Transpose $signStr)"
                        logTraffic(MidiTrafficLog.Direction.IN, summary, bytesToHex(sysexBytes))
                        Log.d("KorgMidiService", summary)
                    } else {
                        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[ESP32 BLE RX ERROR]\nInvalid Transpose Packet size: ${sysexBytes.size} bytes", bytesToHex(sysexBytes))
                    }
                }
                0x05 -> { // SELECT_SLOT (0x05)
                    if (sysexBytes.size >= 4) {
                        val slot = sysexBytes[3].toInt() and 0x7F
                        val slotNumber = slot + 1
                        val summary = "[ESP32 RX] CMD=05 SLOT_INDEX=$slot SLOT=$slotNumber"
                        val diagCmd = "[ESP32 CMD RECEIVED] CMD=05 SLOT_INDEX=$slot SLOT=$slotNumber"
                        _lastEsp32CommandSummary.value = "CMD 05 (Select Slot $slotNumber, Index $slot)"
                        Log.d("KorgMidiService", diagCmd)
                        logTraffic(MidiTrafficLog.Direction.IN, "$summary\n$diagCmd", bytesToHex(sysexBytes))
                        Log.d("KorgMidiService", summary)
                        _esp32IncomingSelectSlot.value = null
                        _esp32IncomingSelectSlot.value = slot
                    } else {
                        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[ESP32 BLE RX ERROR]\nInvalid Select Slot Packet size: ${sysexBytes.size} bytes", bytesToHex(sysexBytes))
                    }
                }
                0x07 -> { // RX_SLOT_NAME (0x07)
                    if (sysexBytes.size >= 5) {
                        val slotIndex = sysexBytes[3].toInt() and 0x7F
                        val nameLength = (sysexBytes[4].toInt() and 0x7F)
                        val maxPayloadBytes = if (sysexBytes.last() == 0xF7.toByte()) sysexBytes.size - 6 else sysexBytes.size - 5
                        val actualLen = minOf(nameLength, maxOf(0, maxPayloadBytes), 24)
                        val rawNameBytes = if (actualLen > 0) {
                            sysexBytes.copyOfRange(5, 5 + actualLen)
                        } else {
                            ByteArray(0)
                        }
                        val decodedName = if (rawNameBytes.isNotEmpty()) String(rawNameBytes, Charsets.UTF_8).trim() else ""
                        val slotNumber = slotIndex + 1
                        val hexDump = bytesToHex(sysexBytes)
                        val summary = "[ESP32 RX NAME]\nSLOT=$slotNumber\nNAME=\"$decodedName\""
                        _lastEsp32CommandSummary.value = "CMD 07 (Slot $slotNumber Name: \"$decodedName\")"
                        logTraffic(
                            MidiTrafficLog.Direction.IN,
                            summary,
                            "[ESP32 RX NAME HEX]\n$hexDump"
                        )
                        Log.d("KorgMidiService", "[ESP32 RX NAME] SLOT=$slotNumber NAME=\"$decodedName\"")
                        _esp32SlotNameRx.value = null
                        _esp32SlotNameRx.value = Esp32SlotNameRx(slotIndex, decodedName)
                    } else {
                        logTraffic(MidiTrafficLog.Direction.SYSTEM, "[ESP32 BLE RX ERROR]\nInvalid Slot Name Packet size: ${sysexBytes.size} bytes", bytesToHex(sysexBytes))
                    }
                }
                else -> {
                    val summary = "[ESP32 RX] CMD=0x${cmd.toString(16).uppercase()}"
                    _lastEsp32CommandSummary.value = "CMD 0x${cmd.toString(16).uppercase()}"
                    logTraffic(MidiTrafficLog.Direction.IN, summary, bytesToHex(sysexBytes))
                    Log.d("KorgMidiService", summary)
                }
            }
            return
        }

        if (b0 == 0xF0 && b1 == 0x42) {
            var funcId = 0
            if (sysexBytes.size > 6) {
                funcId = sysexBytes[6].toInt() and 0xFF
            }

            if (funcId == 0x42 || funcId == 0x4E || funcId == 0x40 || funcId == 0x4C || funcId == 0x68) {
                if (sysexBytes.size > 7) {
                    val mVal = sysexBytes[7].toInt() and 0xFF
                    currentMode = if (mVal == 0) "Combi" else "Prog"
                }
            }

            val extractedName = extractAsciiNameFromSysex(sysexBytes)
            if (!extractedName.isNullOrBlank()) {
                val current = _currentPatchInfo.value
                _currentPatchInfo.value = KorgPatchInfo(
                    msb = current?.msb ?: currentMsb,
                    lsb = current?.lsb ?: currentLsb,
                    program = current?.program ?: 0,
                    mode = currentMode,
                    customName = extractedName
                )
                logTraffic(MidiTrafficLog.Direction.IN, "RX Korg SysEx: Extracted Sound Name '$extractedName' [$currentMode]", bytesToHex(sysexBytes))
            }
        }
    }

    private fun extractAsciiNameFromSysex(raw: ByteArray): String? {
        if (raw.size < 8) return null

        for (offset in listOf(7, 6, 5, 4)) {
            if (raw.size > offset + 4) {
                val unpacked = unpackKorg7BitPayload(raw, startOffset = offset, endOffset = raw.size - 1)
                if (unpacked.size >= 16) {
                    val nameBytes = unpacked.copyOfRange(0, 16)
                    val candidate = String(nameBytes, Charsets.US_ASCII).trim()
                    if (isValidSoundName(candidate) && candidate.length >= 2) {
                        return candidate
                    }
                }
                val scanned = findPrintableAsciiString(unpacked)
                if (!scanned.isNullOrBlank()) return scanned
            }
        }

        return findPrintableAsciiString(raw)
    }

    private fun findPrintableAsciiString(bytes: ByteArray): String? {
        var start = -1
        var len = 0
        var bestString: String? = null

        for (idx in bytes.indices) {
            val b = bytes[idx].toInt() and 0xFF
            if (b in 32..126) {
                if (start == -1) start = idx
                len++
            } else {
                if (len >= 2 && start != -1) {
                    val str = String(bytes, start, len, Charsets.US_ASCII).trim()
                    if (isValidSoundName(str)) {
                        bestString = str
                        break
                    }
                }
                start = -1
                len = 0
            }
        }
        if (bestString == null && len >= 2 && start != -1) {
            val str = String(bytes, start, len, Charsets.US_ASCII).trim()
            if (isValidSoundName(str)) {
                bestString = str
            }
        }
        return bestString
    }

    private fun isValidSoundName(s: String): Boolean {
        if (s.length < 2) return false
        if (s.all { !it.isLetterOrDigit() && it != ' ' && it != '-' && it != '+' && it != '/' && it != '.' }) return false
        val uppercase = s.uppercase(Locale.US)
        if (uppercase.contains("KORG") || uppercase.contains("SYSEX") || uppercase.contains("HEADER")) return false
        return true
    }

    private fun unpackKorg7BitPayload(raw: ByteArray, startOffset: Int, endOffset: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var i = startOffset
        while (i < endOffset) {
            val controlByte = raw[i].toInt() and 0xFF
            i++
            for (bit in 0..6) {
                if (i >= endOffset) break
                val dataByte = raw[i].toInt() and 0xFF
                i++
                val msb = (controlByte shr bit) and 0x01
                val fullByte = (msb shl 7) or dataByte
                out.write(fullByte)
            }
        }
        return out.toByteArray()
    }

    fun logTraffic(direction: MidiTrafficLog.Direction, summary: String, hexDump: String) {
        val timestamp = timeFormatter.format(Date())
        val newLog = MidiTrafficLog(timestamp = timestamp, direction = direction, summary = summary, hexDump = hexDump)

        val currentList = _trafficLogs.value.toMutableList()
        if (currentList.size >= 100) {
            currentList.removeAt(0)
        }
        currentList.add(newLog)
        _trafficLogs.value = currentList
    }

    fun clearTrafficLogs() {
        _trafficLogs.value = emptyList()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X ", b))
        }
        return sb.toString().trim()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        _openedBleMidiDevices.values.forEach {
            try { it.close() } catch (e: Exception) {}
        }
        _openedBleMidiDevices.clear()
        disconnectDevice("Service destroyed")
        try {
            midiManager.unregisterDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
