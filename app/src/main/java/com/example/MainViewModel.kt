package com.example

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SoundPreset(
    val name: String,
    val msb: Int,
    val lsb: Int,
    val program: Int,
    val sysexHex: String = "",
    val mode: String = "Prog",
    val triggerNote: Int = -1,
    val outputNote: Int = -1,
    val outputVelocity: Int = 100,
    val buttonType: String = "PGM",
    val midiChannel: Int = -1
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val midiManager = application.getSystemService(Context.MIDI_SERVICE) as MidiManager
    val midiController = KorgMidiController(midiManager, application)
    val selectedInputDevice: StateFlow<MidiDeviceInfo?> = midiController.selectedInputDevice
    val selectedOutputDevice: StateFlow<MidiDeviceInfo?> = midiController.selectedOutputDevice
    val scannedBleDevices: StateFlow<List<BleMidiDevice>> = midiController.scannedBleDevices
    val isScanningBle: StateFlow<Boolean> = midiController.isScanningBle

    fun startBleScan() {
        midiController.startBleScan()
    }

    fun stopBleScan() {
        midiController.stopBleScan()
    }

    fun connectBleDevice(device: android.bluetooth.BluetoothDevice, asInput: Boolean = true, asOutput: Boolean = true) {
        midiController.connectBleDevice(device, asInput, asOutput)
    }

    fun disconnectBleDevice(device: android.bluetooth.BluetoothDevice) {
        midiController.disconnectBleDevice(device)
    }

    private val prefs: SharedPreferences = application.getSharedPreferences("korg_midi_prefs", Context.MODE_PRIVATE)

    private val _transpose = MutableStateFlow(0)
    val transpose: StateFlow<Int> = _transpose.asStateFlow()

    private val _channel = MutableStateFlow(0) // 0-15
    val channel: StateFlow<Int> = _channel.asStateFlow()

    private val defaultPresets = listOf(
        SoundPreset("German Monolithic Grand", 0, 0, 0, "", "Prog"),
        SoundPreset("Stereo Strings Session", 0, 0, 25, "", "Prog"),
        SoundPreset("Krome Stereo EP", 0, 0, 5, "", "Prog"),
        SoundPreset("Solo Trumpet Vibrato", 0, 0, 29, "", "Prog"),
        SoundPreset("CX-3 B3 Jazz Organ", 0, 0, 12, "", "Prog"),
        SoundPreset("Pop Brass Section", 0, 0, 28, "", "Prog"),
        SoundPreset("Acoustic Steel Guitar", 0, 0, 16, "", "Prog"),
        SoundPreset("Slap Bass Mark II", 0, 0, 23, "", "Prog"),
        SoundPreset("Analog Lead Saw", 0, 0, 35, "", "Prog"),
        SoundPreset("Synth Pad Warmth", 0, 0, 48, "", "Prog"),
        SoundPreset("Bell Tines DX", 0, 0, 60, "", "Prog"),
        SoundPreset("Power Drum Kit", 0, 0, 115, "", "Prog")
    )

    private val _soundPresets = MutableStateFlow(defaultPresets)
    val soundPresets: StateFlow<List<SoundPreset>> = _soundPresets.asStateFlow()

    private val _selectedPresetIndex = MutableStateFlow(0)
    val selectedPresetIndex: StateFlow<Int> = _selectedPresetIndex.asStateFlow()

    private val _savedConfigurations = MutableStateFlow<List<String>>(emptyList())
    val savedConfigurations: StateFlow<List<String>> = _savedConfigurations.asStateFlow()

    private val _currentConfigName = MutableStateFlow("Default Setup")
    val currentConfigName: StateFlow<String> = _currentConfigName.asStateFlow()

    private val _activeInputTriggerPadIndex = MutableStateFlow<Int?>(null)
    val activeInputTriggerPadIndex: StateFlow<Int?> = _activeInputTriggerPadIndex.asStateFlow()

    private val _isSyncingFromEsp32 = MutableStateFlow(false)
    val isSyncingFromEsp32: StateFlow<Boolean> = _isSyncingFromEsp32.asStateFlow()

    private val _lastMidiInputInfo = MutableStateFlow<String?>(null)
    val lastMidiInputInfo: StateFlow<String?> = _lastMidiInputInfo.asStateFlow()

    private val _midiLearningPadIndex = MutableStateFlow<Int?>(null)
    val midiLearningPadIndex: StateFlow<Int?> = _midiLearningPadIndex.asStateFlow()

    private var esp32DumpJob: kotlinx.coroutines.Job? = null

    init {
        refreshSavedConfigurations()
        loadConfiguration("Default Setup")

        viewModelScope.launch {
            midiController.incomingMidiEvent.collect { event ->
                if (event != null) {
                    handleIncomingMidi(event)
                }
            }
        }

        viewModelScope.launch {
            midiController.esp32SlotDump.collect { dump ->
                if (dump != null) {
                    handleEsp32SlotDump(dump)
                }
            }
        }

        viewModelScope.launch {
            midiController.esp32IncomingTranspose.collect { rxTranspose ->
                if (rxTranspose != null) {
                    _transpose.value = rxTranspose
                    midiController.sendMasterCoarseTune(_channel.value, rxTranspose)
                    val signStr = if (rxTranspose > 0) "+$rxTranspose" else "$rxTranspose"
                    _lastMidiInputInfo.value = "ESP32 Transpose: $signStr"
                }
            }
        }

        viewModelScope.launch {
            midiController.esp32IncomingSelectSlot.collect { rxSlot ->
                if (rxSlot != null && rxSlot in 0..127) {
                    val currentList = _soundPresets.value.toMutableList()
                    while (currentList.size <= rxSlot) {
                        currentList.add(SoundPreset("Sound ${currentList.size + 1}", 0, 0, currentList.size, "", "Prog"))
                    }
                    if (currentList.size != _soundPresets.value.size) {
                        _soundPresets.value = currentList
                        saveConfiguration(_currentConfigName.value)
                    }

                    _selectedPresetIndex.value = rxSlot
                    _activeInputTriggerPadIndex.value = rxSlot
                    val slotNumber = rxSlot + 1
                    val uiLog = "[UI] ACTIVE SLOT=$slotNumber"
                    Log.d("MainViewModel", uiLog)
                    midiController.logTraffic(MidiTrafficLog.Direction.SYSTEM, uiLog, "")

                    val targetPreset = _soundPresets.value[rxSlot]
                    midiController.updateCurrentPatch(
                        msb = targetPreset.msb,
                        lsb = targetPreset.lsb,
                        program = targetPreset.program,
                        mode = targetPreset.mode,
                        customName = targetPreset.name
                    )
                    // NOTE: Do NOT call triggerSlotToKorg(rxSlot) here.
                    // ESP32 has already executed the MIDI action on Korg Krome.
                    // CMD 05 is strictly UI feedback / slot selection.
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(350)
                        if (_activeInputTriggerPadIndex.value == rxSlot) {
                            _activeInputTriggerPadIndex.value = null
                        }
                    }
                    _lastMidiInputInfo.value = "Selected Slot $slotNumber from ESP32"
                }
            }
        }
    }

    private fun handleEsp32SlotDump(dump: Esp32SlotDump) {
        val slotIdx = dump.slotIndex
        if (slotIdx !in 0..127) return
        _isSyncingFromEsp32.value = true

        val currentList = _soundPresets.value.toMutableList()
        while (currentList.size <= slotIdx) {
            currentList.add(SoundPreset("Sound ${currentList.size + 1}", 0, 0, currentList.size, "", "Prog"))
        }

        val currentPreset = currentList[slotIdx]
        val modeStr = if (dump.isCombi) "Combi" else "Prog"
        val resolvedName = KorgKromeSoundBank.getSoundName(dump.bankMSB, dump.bankLSB, dump.progNum, modeStr)
        val nameToUse = if (currentPreset.name.isNotBlank() && !currentPreset.name.startsWith("Sound ") && !currentPreset.name.startsWith("Prog P") && !currentPreset.name.startsWith("Combi P")) {
            currentPreset.name
        } else if (resolvedName.isNotBlank() && !resolvedName.startsWith("PC ")) {
            resolvedName
        } else {
            "$modeStr P${dump.progNum}"
        }

        val btnType = if (dump.buttonType.isNotBlank()) dump.buttonType else (if (dump.outNote < 127) "NOTE" else "PGM")
        val outNoteVal = if (btnType.equals("NOTE", ignoreCase = true)) {
            if (dump.outNote in 0..126) dump.outNote else 60
        } else {
            -1
        }

        val updatedPreset = currentPreset.copy(
            name = nameToUse,
            msb = dump.bankMSB,
            lsb = dump.bankLSB,
            program = dump.progNum,
            mode = modeStr,
            triggerNote = dump.triggerNote,
            outputNote = outNoteVal,
            outputVelocity = dump.outputVelocity.coerceIn(1, 127),
            buttonType = btnType,
            midiChannel = dump.outChannel
        )
        currentList[slotIdx] = updatedPreset
        _soundPresets.value = currentList
        _lastMidiInputInfo.value = "Synced Slot ${slotIdx + 1} from ESP32: $nameToUse"

        // Debounce dump completion to batch session
        scheduleDumpCompletionDebounce()
    }

    private fun scheduleDumpCompletionDebounce() {
        esp32DumpJob?.cancel()
        esp32DumpJob = viewModelScope.launch {
            kotlinx.coroutines.delay(750)
            _isSyncingFromEsp32.value = false
            saveConfiguration(_currentConfigName.value)
            midiController.logTraffic(
                MidiTrafficLog.Direction.SYSTEM,
                "[ESP32 PULL] COMPLETE count=${_soundPresets.value.size}",
                ""
            )
            _lastMidiInputInfo.value = "ESP32 Dump Complete (${_soundPresets.value.size} slots)"
        }
    }

    fun startMidiLearn(padIndex: Int) {
        _midiLearningPadIndex.value = padIndex
    }

    fun cancelMidiLearn() {
        _midiLearningPadIndex.value = null
    }

    private fun handleIncomingMidi(event: IncomingMidiInputEvent) {
        val presets = _soundPresets.value
        if (presets.isEmpty()) return

        // Check if MIDI Learn is currently listening for a pad
        val learningPadIdx = _midiLearningPadIndex.value
        if (learningPadIdx != null && learningPadIdx in presets.indices) {
            if (event.type == MidiEventType.NOTE_ON && event.velocityOrVal > 0) {
                val learnedNote = event.noteOrPc
                val targetPreset = presets[learningPadIdx]
                val updated = targetPreset.copy(triggerNote = learnedNote)
                updatePreset(learningPadIdx, updated)
                val slotNumber = learningPadIdx + 1
                Log.d("MainViewModel", "[MIDI LEARN] NOTE=$learnedNote")
                Log.d("MainViewModel", "[MIDI LEARN] SLOT=$slotNumber")
                midiController.logTraffic(MidiTrafficLog.Direction.SYSTEM, "[MIDI LEARN] NOTE=$learnedNote\n[MIDI LEARN] SLOT=$slotNumber", "")
                _lastMidiInputInfo.value = "Learned Note $learnedNote for Slot $slotNumber"
                _midiLearningPadIndex.value = null
                return
            } else if (event.type == MidiEventType.CONTROL_CHANGE && event.velocityOrVal > 0) {
                val learnedCc = event.noteOrPc
                val targetPreset = presets[learningPadIdx]
                val updated = targetPreset.copy(triggerNote = learnedCc)
                updatePreset(learningPadIdx, updated)
                val slotNumber = learningPadIdx + 1
                Log.d("MainViewModel", "[MIDI LEARN] CC=$learnedCc")
                Log.d("MainViewModel", "[MIDI LEARN] SLOT=$slotNumber")
                midiController.logTraffic(MidiTrafficLog.Direction.SYSTEM, "[MIDI LEARN] CC=$learnedCc\n[MIDI LEARN] SLOT=$slotNumber", "")
                _lastMidiInputInfo.value = "Learned CC $learnedCc for Slot $slotNumber"
                _midiLearningPadIndex.value = null
                return
            }
        }

        when (event.type) {
            MidiEventType.NOTE_ON -> {
                val note = event.noteOrPc
                _lastMidiInputInfo.value = "RX Note $note (Vel ${event.velocityOrVal}, Ch ${event.channel + 1})"

                var matchedIndex = -1
                for (i in presets.indices) {
                    val p = presets[i]
                    if (p.triggerNote >= 0 && p.triggerNote == note) {
                        matchedIndex = i
                        break
                    }
                    if (p.triggerNote < 0) {
                        val def1 = 36 + i
                        val def2 = 60 + i
                        if (note == def1 || note == def2) {
                            matchedIndex = i
                            break
                        }
                    }
                }

                if (matchedIndex == -1) {
                    val padIndexByPc = presets.indexOfFirst { it.program == note }
                    if (padIndexByPc != -1) {
                        matchedIndex = padIndexByPc
                    }
                }

                if (matchedIndex != -1) {
                    _activeInputTriggerPadIndex.value = matchedIndex
                    _selectedPresetIndex.value = matchedIndex
                    val slotNumber = matchedIndex + 1
                    val uiLog = "[UI] ACTIVE SLOT=$slotNumber"
                    Log.d("MainViewModel", uiLog)
                    midiController.logTraffic(MidiTrafficLog.Direction.SYSTEM, uiLog, "")

                    val targetPreset = presets[matchedIndex]
                    midiController.updateCurrentPatch(
                        msb = targetPreset.msb,
                        lsb = targetPreset.lsb,
                        program = targetPreset.program,
                        mode = targetPreset.mode,
                        customName = targetPreset.name
                    )
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(350)
                        if (_activeInputTriggerPadIndex.value == matchedIndex) {
                            _activeInputTriggerPadIndex.value = null
                        }
                    }
                }
            }

            MidiEventType.PROGRAM_CHANGE -> {
                val pc = event.noteOrPc
                _lastMidiInputInfo.value = "RX PC $pc (Ch ${event.channel + 1})"

                var matchedIndex = pc
                if (matchedIndex !in presets.indices) {
                    matchedIndex = presets.indexOfFirst { it.program == pc }
                }

                if (matchedIndex in presets.indices) {
                    _activeInputTriggerPadIndex.value = matchedIndex
                    _selectedPresetIndex.value = matchedIndex
                    val targetPreset = presets[matchedIndex]
                    midiController.updateCurrentPatch(
                        msb = targetPreset.msb,
                        lsb = targetPreset.lsb,
                        program = targetPreset.program,
                        mode = targetPreset.mode,
                        customName = targetPreset.name
                    )
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(350)
                        if (_activeInputTriggerPadIndex.value == matchedIndex) {
                            _activeInputTriggerPadIndex.value = null
                        }
                    }
                }
            }

            MidiEventType.NOTE_OFF -> {
                val note = event.noteOrPc
                _lastMidiInputInfo.value = "RX Note Off $note (Ch ${event.channel + 1})"

                val matchedIndices = mutableSetOf<Int>()
                for (i in presets.indices) {
                    val p = presets[i]
                    if (p.triggerNote >= 0 && p.triggerNote == note) {
                        matchedIndices.add(i)
                    } else if (p.triggerNote < 0) {
                        val def1 = 36 + i
                        val def2 = 60 + i
                        if (note == def1 || note == def2) {
                            matchedIndices.add(i)
                        }
                    } else if (p.program == note) {
                        matchedIndices.add(i)
                    }
                }

                val currentTriggered = _activeInputTriggerPadIndex.value
                if (currentTriggered != null) {
                    matchedIndices.add(currentTriggered)
                }

                for (idx in matchedIndices) {
                    onPadRelease(idx)
                    if (_activeInputTriggerPadIndex.value == idx) {
                        _activeInputTriggerPadIndex.value = null
                    }
                }
            }

            MidiEventType.CONTROL_CHANGE -> {
                _lastMidiInputInfo.value = "RX CC ${event.noteOrPc} = ${event.velocityOrVal} (Ch ${event.channel + 1})"
                val cc = event.noteOrPc
                val val7 = event.velocityOrVal
                val matchedCcIndex = presets.indexOfFirst {
                    (it.triggerNote >= 0 && it.triggerNote == cc)
                }
                if (matchedCcIndex != -1) {
                    if (val7 > 0) {
                        _activeInputTriggerPadIndex.value = matchedCcIndex
                        onPadPress(matchedCcIndex)
                    } else {
                        onPadRelease(matchedCcIndex)
                        if (_activeInputTriggerPadIndex.value == matchedCcIndex) {
                            _activeInputTriggerPadIndex.value = null
                        }
                    }
                }
            }

            MidiEventType.PITCH_BEND -> {
                _lastMidiInputInfo.value = "RX Pitch Bend ${event.noteOrPc} (Ch ${event.channel + 1})"
            }
        }
    }

    fun addSoundPreset() {
        val current = _soundPresets.value.toMutableList()
        val newIndex = current.size
        current.add(SoundPreset("Sound ${newIndex + 1}", 0, 0, newIndex, "", "Prog"))
        _soundPresets.value = current
        saveConfiguration(_currentConfigName.value)
    }

    fun deleteSoundPreset(index: Int) {
        val current = _soundPresets.value.toMutableList()
        if (current.size <= 1) return
        current.removeAt(index)
        _soundPresets.value = current
        saveConfiguration(_currentConfigName.value)
    }

    fun sendPitchBend(midiValue: Int) {
        val clamped = midiValue.coerceIn(0, 16383)
        midiController.sendPitchBend(_channel.value, clamped)
    }

    fun sendPitchBendNormalized(fraction: Float) {
        val normalized = fraction.coerceIn(-1.0f, 1.0f)
        val midiVal = (8192 + (normalized * 8191f)).toInt().coerceIn(0, 16383)
        midiController.sendPitchBend(_channel.value, midiVal)
    }

    fun setTranspose(value: Int) {
        val newTranspose = value.coerceIn(-12, 12)
        _transpose.value = newTranspose
        // Tetap kirim ke KORG
        midiController.sendMasterCoarseTune(_channel.value, newTranspose)
        // Kirim state transpose ke ESP32
        midiController.sendEsp32Transpose(newTranspose)
    }

    fun updatePreset(index: Int, preset: SoundPreset, syncToEsp32: Boolean = true) {
        val current = _soundPresets.value.toMutableList()
        if (index in current.indices) {
            current[index] = preset
            _soundPresets.value = current
            saveConfiguration(_currentConfigName.value)
            if (syncToEsp32) {
                sendEsp32SlotConfig(index, preset)
            }
        }
    }

    fun sendEsp32SlotConfig(slotIndex: Int, preset: SoundPreset) {
        val triggerNote = if (preset.triggerNote >= 0) preset.triggerNote else (36 + slotIndex).coerceIn(0, 127)
        val isCombi = if (preset.mode.equals("Combi", ignoreCase = true)) 1 else 0
        val bankMSB = preset.msb.coerceIn(0, 127)
        val bankLSB = preset.lsb.coerceIn(0, 127)
        val progNum = preset.program.coerceIn(0, 127)
        val outChannel = (if (preset.midiChannel in 0..15) preset.midiChannel else _channel.value).coerceIn(0, 15)
        
        // Strict Mode rules for ESP32 bridge:
        // When NOTE mode: send outNote (0..126).
        // When PGM mode: send 127 to indicate Program Change only (no note output).
        val isNoteMode = preset.buttonType.equals("NOTE", ignoreCase = true) || (preset.buttonType.isBlank() && preset.outputNote in 0..126)
        val outNote = if (isNoteMode) {
            if (preset.outputNote in 0..126) preset.outputNote else (60 + slotIndex).coerceIn(0, 126)
        } else {
            127
        }

        val velocity = preset.outputVelocity.coerceIn(1, 127)
        val buttonTypeCode = when {
            preset.buttonType.equals("NOTE", ignoreCase = true) -> 1
            preset.buttonType.equals("CC", ignoreCase = true) -> 2
            preset.buttonType.equals("SX", ignoreCase = true) -> 3
            preset.buttonType.equals("CUST", ignoreCase = true) -> 4
            else -> 0 // "PGM" / default
        }

        midiController.sendEsp32SlotConfig(
            slotIndex = slotIndex,
            triggerNote = triggerNote,
            isCombi = isCombi,
            bankMSB = bankMSB,
            bankLSB = bankLSB,
            progNum = progNum,
            outChannel = outChannel,
            outNote = outNote,
            outputVelocity = velocity,
            buttonTypeCode = buttonTypeCode
        )
    }

    fun requestEsp32Dump() {
        _isSyncingFromEsp32.value = true
        midiController.sendEsp32DumpRequest()
        scheduleDumpCompletionDebounce()
    }

    fun syncAllSlotsToEsp32() {
        val presets = _soundPresets.value
        midiController.logTraffic(
            MidiTrafficLog.Direction.SYSTEM,
            "[ESP32 PUSH ALL] count=${presets.size}",
            ""
        )
        presets.forEachIndexed { idx, preset ->
            midiController.logTraffic(
                MidiTrafficLog.Direction.SYSTEM,
                "[ESP32 PUSH] slot=${idx + 1} index=$idx type=${preset.buttonType}",
                ""
            )
            sendEsp32SlotConfig(idx, preset)
        }
    }

    private val activePadNotes = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Int>>()

    fun onPadPress(index: Int) {
        // Send SELECT SLOT (F0 7D 05 SLOT F7) to ESP32
        midiController.sendEsp32SelectSlot(index)
        triggerSlotToKorg(index)
    }

    fun triggerSlotToKorg(index: Int) {
        val preset = _soundPresets.value.getOrNull(index) ?: return
        _selectedPresetIndex.value = index

        val targetChannel = if (preset.midiChannel in 0..15) preset.midiChannel else _channel.value
        val isNoteMode = preset.buttonType.equals("NOTE", ignoreCase = true) || (preset.buttonType.isBlank() && preset.outputNote in 0..126)

        if (isNoteMode) {
            // MODE NOTE MIDI: ONLY send Note On. NO Program Change or Mode Change!
            val outNoteToSend = if (preset.outputNote in 0..126) {
                preset.outputNote
            } else {
                (60 + index).coerceIn(0, 126)
            }
            val transposedNote = (outNoteToSend - _transpose.value).coerceIn(0, 127)
            val velocity = preset.outputVelocity.coerceIn(1, 127)
            activePadNotes[index]?.let { (prevNote, prevChan) ->
                midiController.sendNoteOff(prevChan, prevNote)
            }
            activePadNotes[index] = Pair(transposedNote, targetChannel)
            midiController.sendNoteOn(targetChannel, transposedNote, velocity)
        } else {
            // MODE PROGRAM CHANGE (PGM / DEFAULT / SX / CUST): ONLY send Bank/Program/Mode. NO Note On!
            midiController.updateCurrentPatch(
                msb = preset.msb,
                lsb = preset.lsb,
                program = preset.program,
                mode = preset.mode,
                customName = if (preset.name.startsWith("Sound ") && preset.name.length <= 8) null else preset.name
            )

            // Switch Korg mode (0 = Combi, 2 = Prog) before sending PC/CC
            if (preset.mode.equals("Combi", ignoreCase = true)) {
                midiController.sendModeChange(targetChannel, 0)
            } else if (preset.mode.equals("Prog", ignoreCase = true)) {
                midiController.sendModeChange(targetChannel, 2)
            }

            midiController.sendProgramChange(
                channel = targetChannel,
                msb = preset.msb,
                lsb = preset.lsb,
                program = preset.program
            )

            if (preset.sysexHex.isNotBlank()) {
                midiController.sendSysexHex(preset.sysexHex)
            }

            // Request live active program name from connected Korg hardware
            midiController.requestCurrentSoundInfo(targetChannel)
        }
    }

    fun onPadRelease(index: Int) {
        val activeInfo = activePadNotes.remove(index)
        if (activeInfo != null) {
            midiController.sendNoteOff(activeInfo.second, activeInfo.first)
        }
    }

    fun playPreset(index: Int) {
        onPadPress(index)
        val preset = _soundPresets.value.getOrNull(index) ?: return
        val isNoteMode = preset.buttonType.equals("NOTE", ignoreCase = true) || (preset.buttonType.isBlank() && preset.outputNote in 0..126)
        if (isNoteMode) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                onPadRelease(index)
            }
        }
    }

    fun playTestNote(baseNote: Int = 60) {
        val transposedNote = (baseNote + _transpose.value).coerceIn(0, 127)
        midiController.sendNoteOn(_channel.value, transposedNote, 100)
        
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            midiController.sendNoteOff(_channel.value, transposedNote)
        }
    }

    private fun refreshSavedConfigurations() {
        val configs = prefs.getStringSet("config_list", setOf("Default Setup")) ?: setOf("Default Setup")
        _savedConfigurations.value = configs.toList().sorted()
    }

    fun saveConfiguration(configName: String) {
        val name = configName.ifBlank { "Default Setup" }
        val array = JSONArray()
        _soundPresets.value.forEach { preset ->
            val obj = JSONObject().apply {
                put("name", preset.name)
                put("msb", preset.msb)
                put("lsb", preset.lsb)
                put("program", preset.program)
                put("sysexHex", preset.sysexHex)
                put("mode", preset.mode)
                put("triggerNote", preset.triggerNote)
                put("outputNote", preset.outputNote)
                put("outputVelocity", preset.outputVelocity)
                put("buttonType", preset.buttonType)
                put("midiChannel", preset.midiChannel)
            }
            array.put(obj)
        }
        
        prefs.edit().putString("config_$name", array.toString()).apply()
        
        val currentSet = prefs.getStringSet("config_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(name)
        prefs.edit().putStringSet("config_list", currentSet).apply()

        _currentConfigName.value = name
        refreshSavedConfigurations()
    }

    fun loadConfiguration(configName: String) {
        val jsonStr = prefs.getString("config_$configName", null)
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                val newList = mutableListOf<SoundPreset>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    newList.add(
                        SoundPreset(
                            name = obj.optString("name", "Sound ${i + 1}"),
                            msb = obj.optInt("msb", 0),
                            lsb = obj.optInt("lsb", 0),
                            program = obj.optInt("program", i),
                            sysexHex = obj.optString("sysexHex", ""),
                            mode = obj.optString("mode", "Prog"),
                            triggerNote = obj.optInt("triggerNote", -1),
                            outputNote = obj.optInt("outputNote", -1),
                            outputVelocity = obj.optInt("outputVelocity", 100),
                            buttonType = obj.optString("buttonType", "PGM"),
                            midiChannel = obj.optInt("midiChannel", -1)
                        )
                    )
                }
                if (newList.isEmpty()) {
                    newList.addAll(defaultPresets)
                }
                _soundPresets.value = newList
                _currentConfigName.value = configName
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (configName == "Default Setup") {
            _soundPresets.value = defaultPresets
            _currentConfigName.value = configName
        }
    }

    fun deleteConfiguration(configName: String) {
        if (configName == "Default Setup") return
        prefs.edit().remove("config_$configName").apply()
        val currentSet = prefs.getStringSet("config_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.remove(configName)
        prefs.edit().putStringSet("config_list", currentSet).apply()
        refreshSavedConfigurations()
        
        if (_currentConfigName.value == configName) {
            loadConfiguration("Default Setup")
        }
    }

    fun exportConfigJson(configName: String = _currentConfigName.value): String {
        val jsonArray = JSONArray()
        val presets = if (configName == _currentConfigName.value) {
            _soundPresets.value
        } else {
            getPresetsForConfig(configName)
        }
        presets.forEach { preset ->
            val obj = JSONObject().apply {
                put("name", preset.name)
                put("msb", preset.msb)
                put("lsb", preset.lsb)
                put("program", preset.program)
                put("sysexHex", preset.sysexHex)
                put("mode", preset.mode)
                put("triggerNote", preset.triggerNote)
                put("outputNote", preset.outputNote)
                put("outputVelocity", preset.outputVelocity)
                put("buttonType", preset.buttonType)
                put("midiChannel", preset.midiChannel)
            }
            jsonArray.put(obj)
        }
        val root = JSONObject().apply {
            put("setupName", configName)
            put("presets", jsonArray)
        }
        return root.toString(2)
    }

    private fun getPresetsForConfig(configName: String): List<SoundPreset> {
        val jsonStr = prefs.getString("config_$configName", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<SoundPreset>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SoundPreset(
                        name = obj.optString("name", "Sound ${i + 1}"),
                        msb = obj.optInt("msb", 0),
                        lsb = obj.optInt("lsb", 0),
                        program = obj.optInt("program", i),
                        sysexHex = obj.optString("sysexHex", ""),
                        mode = obj.optString("mode", "Prog"),
                        triggerNote = obj.optInt("triggerNote", -1),
                        outputNote = obj.optInt("outputNote", -1),
                        outputVelocity = obj.optInt("outputVelocity", 100),
                        buttonType = obj.optString("buttonType", "PGM"),
                        midiChannel = obj.optInt("midiChannel", -1)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun importConfigFromJson(jsonStr: String): String? {
        return try {
            val trimmed = jsonStr.trim()
            var importedName = "Imported Setup"
            val presetsList = mutableListOf<SoundPreset>()

            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    presetsList.add(
                        SoundPreset(
                            name = obj.optString("name", "Sound ${i + 1}"),
                            msb = obj.optInt("msb", 0),
                            lsb = obj.optInt("lsb", 0),
                            program = obj.optInt("program", i),
                            sysexHex = obj.optString("sysexHex", ""),
                            mode = obj.optString("mode", "Prog"),
                            triggerNote = obj.optInt("triggerNote", -1),
                            outputNote = obj.optInt("outputNote", -1),
                            outputVelocity = obj.optInt("outputVelocity", 100),
                            buttonType = obj.optString("buttonType", "PGM"),
                            midiChannel = obj.optInt("midiChannel", -1)
                        )
                    )
                }
            } else {
                val root = JSONObject(trimmed)
                if (root.has("presets")) {
                    importedName = root.optString("setupName", "Imported Setup")
                    val array = root.getJSONArray("presets")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        presetsList.add(
                            SoundPreset(
                                name = obj.optString("name", "Sound ${i + 1}"),
                                msb = obj.optInt("msb", 0),
                                lsb = obj.optInt("lsb", 0),
                                program = obj.optInt("program", i),
                                sysexHex = obj.optString("sysexHex", ""),
                                mode = obj.optString("mode", "Prog"),
                                triggerNote = obj.optInt("triggerNote", -1),
                                outputNote = obj.optInt("outputNote", -1),
                                outputVelocity = obj.optInt("outputVelocity", 100),
                                buttonType = obj.optString("buttonType", "PGM"),
                                midiChannel = obj.optInt("midiChannel", -1)
                            )
                        )
                    }
                } else if (root.has("configs")) {
                    val configs = root.getJSONObject("configs")
                    var lastLoaded: String? = null
                    configs.keys().forEach { key ->
                        val arr = configs.getJSONArray(key)
                        val list = mutableListOf<SoundPreset>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            list.add(
                                SoundPreset(
                                    name = obj.optString("name", "Sound ${i + 1}"),
                                    msb = obj.optInt("msb", 0),
                                    lsb = obj.optInt("lsb", 0),
                                    program = obj.optInt("program", i),
                                    sysexHex = obj.optString("sysexHex", ""),
                                    mode = obj.optString("mode", "Prog"),
                                    triggerNote = obj.optInt("triggerNote", -1),
                                    outputNote = obj.optInt("outputNote", -1),
                                    outputVelocity = obj.optInt("outputVelocity", 100),
                                    buttonType = obj.optString("buttonType", "PGM"),
                                    midiChannel = obj.optInt("midiChannel", -1)
                                )
                            )
                        }
                        savePresetsToConfig(key, list)
                        lastLoaded = key
                    }
                    if (lastLoaded != null) {
                        loadConfiguration(lastLoaded)
                    }
                    return lastLoaded
                }
            }

            if (presetsList.isNotEmpty()) {
                savePresetsToConfig(importedName, presetsList)
                loadConfiguration(importedName)
                return importedName
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun savePresetsToConfig(name: String, presets: List<SoundPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            val obj = JSONObject().apply {
                put("name", preset.name)
                put("msb", preset.msb)
                put("lsb", preset.lsb)
                put("program", preset.program)
                put("sysexHex", preset.sysexHex)
                put("mode", preset.mode)
                put("triggerNote", preset.triggerNote)
                put("outputNote", preset.outputNote)
                put("outputVelocity", preset.outputVelocity)
                put("buttonType", preset.buttonType)
                put("midiChannel", preset.midiChannel)
            }
            array.put(obj)
        }
        prefs.edit().putString("config_$name", array.toString()).apply()

        val currentSet = prefs.getStringSet("config_list", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(name)
        prefs.edit().putStringSet("config_list", currentSet).apply()

        refreshSavedConfigurations()
    }

    override fun onCleared() {
        super.onCleared()
        midiController.close()
    }
}
