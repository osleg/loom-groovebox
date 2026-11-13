@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.groovebox

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import android.os.Build
import android.content.Context
import android.os.Environment
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.groovebox.utils.*
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import com.groovebox.utils.AudioExporter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.groovebox.midi.EmpledManager
import com.groovebox.midi.MidiManager
import com.groovebox.midi.MidiRouter
import com.groovebox.midi.MidiCommand
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import com.groovebox.persistence.PersistenceManager
import com.groovebox.ui.views.SettingsScreen
import kotlin.math.abs
import android.view.KeyEvent
import java.io.File
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.EngineIcon
import com.groovebox.ui.theme.getEngineColor
import com.groovebox.ui.LocalFocusedValue
import com.groovebox.ui.LocalFocusedSetter
import com.groovebox.ui.LocalPlatformInfo
import com.groovebox.ui.PlatformInfo
import com.groovebox.ui.views.MixerView
import com.groovebox.ui.views.SequencerView
import com.groovebox.ui.views.GlobalEffectsView
import com.groovebox.ui.views.*
import com.groovebox.ui.components.NativeFileDialog
import com.groovebox.ui.components.VerticalScrollbar




class MainActivity : ComponentActivity() {
    private var cafaConnection: com.groovebox.cafa.CafaConnection? = null
    private lateinit var performanceManager: AudioPerformanceManager
    private val nativeLib = NativeLib()
    private lateinit var midiManager: MidiManager
    private lateinit var midiRouter: MidiRouter
    private lateinit var empledManager: EmpledManager
    private var grooveboxState by mutableStateOf(createInitialState())
    private var isNativeInitialized by mutableStateOf(false)
    private var splashScreenStatus by mutableStateOf("Initializing...")
    private lateinit var viewModel: GrooveboxViewModel
    private val heldKeys = mutableSetOf<Int>()


    private var mediaProjectionManager: MediaProjectionManager? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            // Revert recording source to MIC if denied
            grooveboxState = grooveboxState.copy(recordingSource = 0)
            nativeLib.setRecordingSource(0)
            Toast.makeText(this, "System Audio Capture Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createInitialState(): GrooveboxState {
        val tracks = List(8) { i ->
            when(i) {
                0 -> TrackState(id = i, engineType = EngineType.SUBTRACTIVE)
                1 -> {
                    val fmParams = mutableMapOf<Int, Float>()
                    // Bell defaults moved to Track 2
                    fmParams[160] = 0.8f // Op 1 Lvl
                    fmParams[166] = 0.4f // Op 2 Lvl
                    fmParams[165] = 1.0f // Op 1 Ratio
                    fmParams[171] = 2.0f // Op 2 Ratio
                    fmParams[161] = 0.01f // Op 1 Atk
                    fmParams[162] = 0.5f // Op 1 Dcy
                    fmParams[9] = 0.5f   // Center Pan
                    TrackState(id = i, engineType = EngineType.FM, parameters = fmParams, fmCarrierMask = 3, pan = 0.5f)
                }
                2 -> TrackState(id = i, engineType = EngineType.WAVETABLE)
                3 -> TrackState(id = i, engineType = EngineType.SAMPLER)
                4 -> TrackState(id = i, engineType = EngineType.GRANULAR)
                5 -> TrackState(id = i, engineType = EngineType.FM_DRUM)
                6 -> TrackState(id = i, engineType = EngineType.ANALOG_DRUM)
                7 -> TrackState(id = i, engineType = EngineType.MIDI)
                else -> TrackState(id = i, engineType = EngineType.SUBTRACTIVE)
            }
        }
        
        return GrooveboxState(tracks = tracks, tempo = 80.0f)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersistenceManager.setContext(this)
        com.groovebox.midi.MidiConfigStore.load()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Prevent Tablet Idle Crash by keeping screen on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Use WindowInsetsControllerCompat to properly hide the Android menubar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        performanceManager = AudioPerformanceManager(this)
        // DEFERRED: performanceManager.startSession() moved to lazy UI loop 
        // to prevent boot-time crash loop on Pixel 10.

        nativeLib.setAppDataDir(getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath)
        
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        // Request recording and storage permissions
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT <= 32) {
             perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
             perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        perms.add(Manifest.permission.RECORD_AUDIO)
        
        val permissionsToRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1001)
        }
        
        // Background Initialization for Assets
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { splashScreenStatus = "Copying Assets..." }
                
                try {
                    PersistenceManager.migrateToExternalStorage()
                    PersistenceManager.copyWavetablesToFilesDir()
                    PersistenceManager.copySoundFontsToFilesDir()
                    PersistenceManager.copyDefaultsToFilesDir()
                } catch (e: Exception) {
                    Log.e("Groovebox", "Persistence startup error: ${e.message}")
                }
                
                withContext(Dispatchers.Main) { splashScreenStatus = "Loading Session Data..." }
                val prefs = getSharedPreferences("GrooveboxPrefs", Context.MODE_PRIVATE)
                val crashedLastLaunch = prefs.getBoolean("crashed_on_launch", false)
                prefs.edit().putBoolean("crashed_on_launch", true).apply()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    prefs.edit().putBoolean("crashed_on_launch", false).apply()
                }, 10000)

                var loadedState: GrooveboxState? = null
                if (crashedLastLaunch) {
                    Log.e("Groovebox", "CRASH LOOP DETECTED! Resetting to Fresh State.")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(this@MainActivity, "Safe Mode: Settings Reset due to Crash", android.widget.Toast.LENGTH_LONG).show()
                    }
                    loadedState = createInitialState()
                    PersistenceManager.saveProject(loadedState, "last_session.gbx")
                } else {
                    try {
                        val initProject = PersistenceManager.loadProject("Init.gbx")
                        val lastSession = PersistenceManager.loadProject("last_session.gbx")
                        loadedState = if (initProject == null) {
                            val fresh = createInitialState()
                            PersistenceManager.saveProject(fresh, "Init.gbx")
                            fresh
                        } else {
                            initProject ?: lastSession
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        loadedState = createInitialState()
                    }
                }

                withContext(Dispatchers.Main) {
                    viewModel = GrooveboxViewModel(nativeLib, lifecycleScope)
                    loadedState?.let { viewModel.sanitizeAndSetState(it) }
                    grooveboxState = viewModel.state
                }
                withContext(Dispatchers.Main) { splashScreenStatus = "Initializing Audio Engine..." }
                nativeLib.init()
                com.groovebox.cafa.CafaConnection.applyInitialRoute(this@MainActivity, nativeLib)
                nativeLib.setAppDataDir(filesDir.absolutePath)
                
                withContext(Dispatchers.Main) { splashScreenStatus = "Restoring Sample Paths..." }
                nativeLib.loadAppState()

                var finalState = sanitizeGrooveboxState(loadedState ?: createInitialState())

                withContext(Dispatchers.Main) { splashScreenStatus = "Clearing Sequencer Buffers..." }
                for (i in 0 until 8) { nativeLib.clearSequencer(i) }

                withContext(Dispatchers.Main) { splashScreenStatus = "Syncing State to Native Engine..." }
                // CRITICAL ORDER: SoundFont loading must complete before audio streams start.
                // syncNativeState calls loadSoundFont() which holds C++ mutexes for 30MB file parses.
                // If nativeLib.start() is called first, the Oboe RT thread contends on those
                // mutexes and Android priority-inheritance escalates to ANR the main thread.
                syncNativeState(finalState, nativeLib)

                withContext(Dispatchers.Main) { splashScreenStatus = "Starting Audio Streams..." }
                nativeLib.start()

                withContext(Dispatchers.Main) { splashScreenStatus = "Finalizing..." }
                val initialTracks = finalState.tracks.mapIndexed { i, t ->
                    t.copy(lastSamplePath = nativeLib.getLastSamplePath(i))
                }
                finalState = finalState.copy(tracks = initialTracks)

                withContext(Dispatchers.Main) {
                    grooveboxState = finalState
                }
            } catch (e: Throwable) {
                Log.e("Groovebox", "FATAL: Background init failed: ${e.message}", e)
                // Even on catastrophic failure, unblock the UI with a safe fresh state
                withContext(Dispatchers.Main) {
                    splashScreenStatus = "Init Error: ${e.message?.take(80)}"
                    // Give user 3 seconds to read error then load with empty state
                    kotlinx.coroutines.delay(3000)
                    grooveboxState = createInitialState()
                }
            }
            withContext(Dispatchers.Main) {
                // A failure above may have happened before the ViewModel existed.
                if (!this@MainActivity::viewModel.isInitialized) {
                    viewModel = GrooveboxViewModel(nativeLib, lifecycleScope)
                    grooveboxState = viewModel.state
                }
                splashScreenStatus = "Connecting MIDI..."
                // Init MIDI on Main thread (required by Android MIDI API for callbacks)
                // but only AFTER the audio engine is ready so it doesn't race with IO init.
                midiRouter = viewModel.midiRouter
                midiManager = MidiManager(this@MainActivity) { message, deviceId ->
                    viewModel.processMidiMessage(message, deviceId)
                }
                viewModel.midiRouter.setMidiSender(midiManager::sendMidi)
                viewModel.attachMidiManager(midiManager)
                empledManager = EmpledManager(midiManager)
                empledManager.sendHandshake()
                cafaConnection = com.groovebox.cafa.CafaConnection(this@MainActivity, nativeLib, viewModel, midiManager)

                // KEEP STATE IN SYNC WITH VIEWMODEL
                lifecycleScope.launch {
                    snapshotFlow { viewModel.state }.collect {
                        grooveboxState = it
                    }
                }

                // Only now can the UI be composed: MainScreen reads every one of
                // these lateinit fields as arguments.
                isNativeInitialized = true
            }
        }

        setContent {
            val config = LocalConfiguration.current
            val platformInfo = remember(config) {
                PlatformInfo(
                    screenWidthDp = config.screenWidthDp,
                    screenHeightDp = config.screenHeightDp,
                    isTablet = config.screenWidthDp >= 600,
                    platform = "android"
                )
            }
            
            var splashtimeElapsed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(2000)
                splashtimeElapsed = true
            }

            CompositionLocalProvider(LocalPlatformInfo provides platformInfo) {
                GrooveboxTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isNativeInitialized) {
                            Column(Modifier.fillMaxSize()) {
                                cafaConnection?.let { com.groovebox.cafa.CafaPanel(it) }
                                Box(Modifier.weight(1f)) {
                                    MainScreen(performanceManager, empledManager, nativeLib, viewModel, midiManager, onRecordingSourceChange = ::handleRecordingSourceChange) { viewModel.onStateChange(it) }
                                }
                            }
                            
                            // RETRY HANDSHAKE after UI load
                            LaunchedEffect(Unit) {
                                delay(1000)
                                Log.e("Groovebox", "@@@ RETRY HANDSHAKE (1s delay)")
                                empledManager.sendHandshake()
                            }
                        }
                        

                        AnimatedVisibility(
                            visible = !(splashtimeElapsed && isNativeInitialized),
                            exit = fadeOut(animationSpec = tween(1000))
                        ) {
                            SplashScreen(splashScreenStatus)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure audio engine is explicitly resumed/started if it was closed or suspended
        nativeLib.start()
    }

    override fun onPause() {
        super.onPause()
        // A runtime permission dialog pauses the activity before the background
        // initialisation has assigned viewModel, so this can run very early.
        if (this::viewModel.isInitialized) {
            PersistenceManager.saveProject(viewModel.state, "last_session.gbx")
        }
    }

    private fun stopLoopbackCapture() {
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        stopService(intent)
    }

    fun handleRecordingSourceChange(source: Int) {
        if (grooveboxState.recordingSource == source) return
        
        if (source == 2) { // SYSTEM
            val intent = mediaProjectionManager?.createScreenCaptureIntent()
            if (intent != null) projectionLauncher.launch(intent)
        } else {
            if (grooveboxState.recordingSource == 2) {
                stopLoopbackCapture()
            }
        }
        grooveboxState = grooveboxState.copy(recordingSource = source)
        nativeLib.setRecordingSource(source)
    }

    override fun onDestroy() {
        cafaConnection?.close()
        stopLoopbackCapture()
        super.onDestroy()
        nativeLib.stop()
        if (this::midiManager.isInitialized) midiManager.close()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (!this::viewModel.isInitialized) return super.onKeyDown(keyCode, event)
        // ALWAYS use the ViewModel state directly for input handling to avoid stale state issues
        val currentState = viewModel.state
        if (!currentState.isKeyboardModeEnabled) return super.onKeyDown(keyCode, event)

        // Handle Metadata Shortcuts (Cmd/Ctrl + Arrows)
        val isMeta = event.isMetaPressed || event.isCtrlPressed
        if (isMeta) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                   val nextTab = (grooveboxState.selectedTab - 1 + 6) % 6
                   grooveboxState = grooveboxState.copy(selectedTab = nextTab)
                   return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                   val nextTab = (grooveboxState.selectedTab + 1) % 6
                   grooveboxState = grooveboxState.copy(selectedTab = nextTab)
                   return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                   val nextTrack = (grooveboxState.selectedTrackIndex - 1 + 8) % 8
                   grooveboxState = grooveboxState.copy(selectedTrackIndex = nextTrack)
                   return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                   val nextTrack = (grooveboxState.selectedTrackIndex + 1) % 8
                   grooveboxState = grooveboxState.copy(selectedTrackIndex = nextTrack)
                   return true
                }
            }
        }

        if (keyCode == android.view.KeyEvent.KEYCODE_MINUS) {
            val newRoot = (grooveboxState.rootNote - 12).coerceIn(0, 72)
            grooveboxState = grooveboxState.copy(rootNote = newRoot)
            nativeLib.setScaleConfig(newRoot, grooveboxState.scaleType.intervals.toIntArray())
            return true
        } else if (keyCode == android.view.KeyEvent.KEYCODE_EQUALS) {
            val newRoot = (grooveboxState.rootNote + 12).coerceIn(0, 72)
            grooveboxState = grooveboxState.copy(rootNote = newRoot)
            nativeLib.setScaleConfig(newRoot, grooveboxState.scaleType.intervals.toIntArray())
            return true
        } else if (keyCode == android.view.KeyEvent.KEYCODE_SPACE) {
            val isShift = event.isShiftPressed
            if (isShift) {
                val newState = grooveboxState.copy(isPlaying = true, isRecording = true)
                grooveboxState = newState
                nativeLib.setPlaying(true)
                nativeLib.setIsRecording(true)
            } else {
                val newPlaying = !grooveboxState.isPlaying
                grooveboxState = grooveboxState.copy(isPlaying = newPlaying)
                nativeLib.setPlaying(newPlaying)
            }
            return true
        }

        val padIndex = getPadIndexFromKeyCode(keyCode)
        if (padIndex != null) {
            if (heldKeys.add(keyCode)) {
                viewModel.triggerPad(padIndex, 100)
            }
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (!this::viewModel.isInitialized) return super.onKeyUp(keyCode, event)
        val currentState = viewModel.state
        if (!currentState.isKeyboardModeEnabled) return super.onKeyUp(keyCode, event)
        
        val padIndex = getPadIndexFromKeyCode(keyCode)
        if (padIndex != null) {
            if (heldKeys.remove(keyCode)) {
                viewModel.releasePad(padIndex)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun getPadIndexFromKeyCode(keyCode: Int): Int? {
        // Mapping matches Desktop Main.kt MAC_KEYS mapping
        return when (keyCode) {
            // Row 1 (Numbers)
            android.view.KeyEvent.KEYCODE_1 -> 0
            android.view.KeyEvent.KEYCODE_2 -> 1
            android.view.KeyEvent.KEYCODE_3 -> 2
            android.view.KeyEvent.KEYCODE_4 -> 3
            android.view.KeyEvent.KEYCODE_5 -> 4
            android.view.KeyEvent.KEYCODE_6 -> 5
            android.view.KeyEvent.KEYCODE_7 -> 6
            android.view.KeyEvent.KEYCODE_8 -> 7
            android.view.KeyEvent.KEYCODE_9 -> 8
            android.view.KeyEvent.KEYCODE_0 -> 9
            // Row 2 (QWERTY)
            android.view.KeyEvent.KEYCODE_Q -> 12
            android.view.KeyEvent.KEYCODE_W -> 13
            android.view.KeyEvent.KEYCODE_E -> 14
            android.view.KeyEvent.KEYCODE_R -> 15
            android.view.KeyEvent.KEYCODE_T -> 16
            android.view.KeyEvent.KEYCODE_Y -> 17
            android.view.KeyEvent.KEYCODE_U -> 18
            android.view.KeyEvent.KEYCODE_I -> 19
            android.view.KeyEvent.KEYCODE_O -> 20
            android.view.KeyEvent.KEYCODE_P -> 21
            android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> 22
            android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> 23
            android.view.KeyEvent.KEYCODE_BACKSLASH -> 24
            // Row 3 (ASDF)
            android.view.KeyEvent.KEYCODE_A -> 25
            android.view.KeyEvent.KEYCODE_S -> 26
            android.view.KeyEvent.KEYCODE_D -> 27
            android.view.KeyEvent.KEYCODE_F -> 28
            android.view.KeyEvent.KEYCODE_G -> 29
            android.view.KeyEvent.KEYCODE_H -> 30
            android.view.KeyEvent.KEYCODE_J -> 31
            android.view.KeyEvent.KEYCODE_K -> 32
            android.view.KeyEvent.KEYCODE_L -> 33
            android.view.KeyEvent.KEYCODE_SEMICOLON -> 34
            android.view.KeyEvent.KEYCODE_APOSTROPHE -> 35
            // Row 4 (ZXCV)
            android.view.KeyEvent.KEYCODE_Z -> 36
            android.view.KeyEvent.KEYCODE_X -> 37
            android.view.KeyEvent.KEYCODE_C -> 38
            android.view.KeyEvent.KEYCODE_V -> 39
            android.view.KeyEvent.KEYCODE_B -> 40
            android.view.KeyEvent.KEYCODE_N -> 41
            android.view.KeyEvent.KEYCODE_M -> 42
            android.view.KeyEvent.KEYCODE_COMMA -> 43
            android.view.KeyEvent.KEYCODE_PERIOD -> 44
            android.view.KeyEvent.KEYCODE_SLASH -> 45
            else -> null
        }
    }
}

@Composable
fun SplashScreen(statusText: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        val maxImgSize = (config.screenHeightDp - 180).coerceAtLeast(100).dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_icon_round),
                contentDescription = "Loom Icon",
                modifier = Modifier
                    .size(if (maxImgSize < 400.dp) maxImgSize else 400.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Fit
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "LOOM GROOVEBOX",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    color = Color.White
                )
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 2.sp,
                    color = Color.Gray
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun MainScreen(
    performanceManager: AudioPerformanceManager,
    empledManager: EmpledManager,
    nativeLib: NativeLib,
    viewModel: GrooveboxViewModel,
    midiManager: MidiManager,
    onRecordingSourceChange: (Int) -> Unit = {},
    onStateChange: (GrooveboxState) -> Unit
) {
    val state = viewModel.state
    
    var localFocusedValue by remember { mutableStateOf<String?>(null) }
    
    CompositionLocalProvider(
        LocalFocusedValue provides localFocusedValue,
        LocalFocusedSetter provides { localFocusedValue = it }
    ) {
        // Wrap children in provider
    
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (true) {
                // Poll Engine Events
                val events = nativeLib.fetchEngineEvents()
                if (events.isNotEmpty()) {
                    val pendingSaves = mutableMapOf<Int, Int>() // trackIdx -> slotIdx

                    for (i in 0 until events.size / 3) {
                        val type = events[i * 3]
                        val trackIdx = events[i * 3 + 1]
                        val slotIdx = events[i * 3 + 2]

                    }

                    // Process unique saves
                    pendingSaves.forEach { (trackIdx, slotIdx) ->
                         val track = state.tracks.getOrNull(trackIdx)
                         if (track != null) {
                             val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                             val filename = "Rec_${track.engineType.name}_T${trackIdx + 1}_S${slotIdx + 1}_$timestamp.gbp"
                             val file = File(File(PersistenceManager.getLoomFolder(), "Sequences"), filename)
                             if (!file.parentFile.exists()) file.parentFile.mkdirs()
                             
                             nativeLib.saveTrackPresetToPath(trackIdx, file.absolutePath)
                             
                             withContext(Dispatchers.Main) {
                                 // Toast.makeText(context, "Auto-Saved: $filename", Toast.LENGTH_SHORT).show()
                             }
                        }
                    }
                }
                
                delay(200)
            }
        }
    }

    // Initial state sync with Native
    // Initial state sync with Native (FULL FETCH)
    LaunchedEffect(Unit) {
        // 1. Push critical Globals to Native first
        state.tracks.forEachIndexed { i, track ->
            nativeLib.setEngineType(i, track.engineType.ordinal)
            if (track.engineType == EngineType.WAVETABLE && (track.activeWavetableName ?: "Basic") != "Basic") {
                 // Try to reload wavetable if path known
                 // nativeLib.loadWavetable(i, ...) - requires path logic, skipping for now to rely on native persistence
            }
        }
        nativeLib.setTempo(state.tempo)
        
        // 2. FETCH real state from Native (Knobs & Steps)
        val syncedTracks = state.tracks.mapIndexed { idx, track ->
            // A. Parameters
            val paramArray = nativeLib.getAllTrackParameters(idx)
            val newParams = paramArray.mapIndexed { pid, value -> pid to value }.toMap()
            
            // B. Sequencer Steps (Active State)
            val activeSteps = nativeLib.getAllStepActiveStates(idx)
            val newSteps = track.steps.mapIndexed { si, step ->
                if (si < activeSteps.size) step.copy(active = activeSteps[si]) else step
            }
            
            // C. Drum Steps (if applicable) - For now, Native only returns MAIN sequencer active states. 
            // If we have time, we should expand native-lib to handle drum voices, but checking 0-127 is a good start.
            // (Assuming activeSteps covers the main sequencer mapping).
            
            // C. FX Parameters (Sends & Mix) - CRITICAL FIX for FX Knob Sync
            val fxSends = nativeLib.getFxSends(idx).toList()
            val fxMix = nativeLib.getFxMix(idx).toList()
            
            track.copy(parameters = newParams, steps = newSteps, fxSends = fxSends, fxMix = fxMix)
        }
        
        onStateChange(state.copy(tracks = syncedTracks))
    }

    LaunchedEffect(state.isParameterLocking, state.isRecording, state.midiLearnStep, state.isSelectingSidechain) {
        if (state.isParameterLocking) {
            onStateChange(state.copy(selectedTab = 1)) // Parameters tab
        } else if (state.midiLearnStep == 1) {
            onStateChange(state.copy(selectedTab = 0)) // Playing tab
        } else if (state.midiLearnStep == 2) {
            onStateChange(state.copy(selectedTab = 1))
        } else if (state.isRecording) {
            onStateChange(state.copy(selectedTab = 0))
        }
    }

    // Update LED colors on tab/track change or hardware bank change
     LaunchedEffect(state.selectedTab, state.selectedTrackIndex, state.tracks[state.selectedTrackIndex].engineType, state.currentSequencerBank, state.currentStep, state.isPlaying, state.tracks[state.selectedTrackIndex].selectedFmDrumInstrument) {
        val track = state.tracks[state.selectedTrackIndex]
        Log.e("Groovebox", "@@@ LED LaunchedEffect fired: tab=${state.selectedTab} bank=${state.currentSequencerBank} step=${state.currentStep}")
        
        // Logical check: are we in a sequencing state?
        // Either explicitly on the Sequencing tab (Tab 2) OR on the Playing tab (Tab 0) but in a hardware sequencing bank (Bank > 0)
        val isSequencing = state.selectedTab == 2 || (state.selectedTab == 0 && state.currentSequencerBank > 0)
        
        if (isSequencing) {
            // SEQUENCER MODE LEDs (Steps + Playhead)
            val engineColor = getEngineColor(track.engineType)
            val bankOffset = if (state.selectedTab == 2 || state.currentSequencerBank > 0) {
                // Determine which bank of 16 steps to show
                // If on Tab 2, use the in-app bank selection. If on Tab 0, use the hardware bank selection (Bank B maps to steps 0-15)
                val bank = if (state.selectedTab == 0) state.currentSequencerBank - 1 else state.currentSequencerBank
                (bank * 16).coerceAtLeast(0)
            } else 0
            
            val isSamplerChops = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) > 0.6f
            val isMultiTrack = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM || isSamplerChops
            
            for (i in 0 until 16) {
                val stepIdx = bankOffset + i
                if (stepIdx > 63) {
                    empledManager.updatePadColor(i, 0, 0, 0)
                    continue
                }
                
                val isActive = if (isMultiTrack) track.drumSteps[track.selectedFmDrumInstrument][stepIdx].active else track.steps[stepIdx].active
                val isPlayhead = state.isPlaying && state.currentStep == stepIdx
                
                val color = if (isPlayhead) androidx.compose.ui.graphics.Color.White 
                            else if (isActive) engineColor 
                            else engineColor.copy(alpha = 0.1f)
                
                if (state.selectedTab == 0 && state.currentSequencerBank > 0) {
                    empledManager.updateSequencerPadColorCompose(i, color, state.currentSequencerBank)
                } else {
                    empledManager.updatePadColorCompose(i, color)
                }
            }
        } else if (state.selectedTab != 0) {
            // Theme colors for other pages
            val pageColor = when (state.selectedTab) {
                1 -> EmpledManager.PageColor.PARAMETERS
                3 -> EmpledManager.PageColor.EFFECTS
                4 -> EmpledManager.PageColor.ROUTING
                else -> EmpledManager.PageColor.SETTINGS
            }
            empledManager.updatePadColors(pageColor)
        }
        // Note: For Tab 0 / Bank 0 (Playing), the PlayingPad composables handle their own individual LED updates reactively.
    }

    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    // Persistent Strip/Knob Assignments Logic
    // 1. Save assignments when they change
    LaunchedEffect(state.stripRoutings, state.knobRoutings) {
        val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
        val currentEngine = currentTrack.engineType
        
        val savedStrips = (latestState.engineTypeStripAssignments ?: emptyMap())[currentEngine]
        val savedKnobs = (latestState.engineTypeKnobAssignments ?: emptyMap())[currentEngine]

        var newState = latestState
        var changed = false

        if (savedStrips != latestState.stripRoutings) {
            val newMap = (latestState.engineTypeStripAssignments ?: emptyMap()).toMutableMap()
            newMap[currentEngine] = latestState.stripRoutings
            newState = newState.copy(engineTypeStripAssignments = newMap)
            changed = true
        }
        if (savedKnobs != latestState.knobRoutings) {
            val newMap = (latestState.engineTypeKnobAssignments ?: emptyMap()).toMutableMap()
            newMap[currentEngine] = latestState.knobRoutings
            newState = newState.copy(engineTypeKnobAssignments = newMap)
            changed = true
        }

        if (changed) {
            // PersistenceManager.saveAssignments() removed to prevent conflict. 
            // Assignments are saved with the Project/Session.
            latestOnStateChange(newState)
        }
    }

    // 2. Load assignments when track or engine changes
    LaunchedEffect(state.selectedTrackIndex, state.tracks[state.selectedTrackIndex].engineType) {
        val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
        val currentEngine = currentTrack.engineType
        
        // Strips
        val savedStrips = (latestState.engineTypeStripAssignments ?: emptyMap())[currentEngine]
        val newStrips = savedStrips ?: List(4) { i -> StripRouting(stripIndex = i) }
        
        // Knobs
        val savedKnobs = (latestState.engineTypeKnobAssignments ?: emptyMap())[currentEngine]
        val newKnobs = savedKnobs ?: List(4) { i -> StripRouting(stripIndex = i + 4, parameterName = "Knob ${i+1}") }
        
        if (latestState.stripRoutings != newStrips || latestState.knobRoutings != newKnobs) {
             latestOnStateChange(latestState.copy(stripRoutings = newStrips, knobRoutings = newKnobs))
        }
    }
    
    // Polling for current step
    LaunchedEffect(state.isPlaying, state.selectedTrackIndex, state.tracks[state.selectedTrackIndex].selectedFmDrumInstrument) {
        if (state.isPlaying) {
            while (true) {
                val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
                val isDrum = currentTrack.engineType == EngineType.FM_DRUM || 
                             currentTrack.engineType == EngineType.ANALOG_DRUM || 
                             (currentTrack.engineType == EngineType.SAMPLER && (currentTrack.parameters[320] ?: 0f) > 0.6f)
                
                val drumIdx = if (isDrum) currentTrack.selectedFmDrumInstrument else -1
                val stepNum = nativeLib.getCurrentStep(latestState.selectedTrackIndex, drumIdx)
                
                // Poll for activity on ALL tracks for the mixer icons
                val activeMasks = (0 until 8).map { i -> nativeLib.getActiveNoteMask(i) != 0L }
                val tracksChanged = activeMasks.zip(latestState.tracks).any { (active, track) -> active != track.isAudiblyActive }

                if (stepNum != latestState.currentStep || tracksChanged) {
                    latestOnStateChange(latestState.copy(
                        currentStep = stepNum,
                        tracks = latestState.tracks.mapIndexed { i, t ->
                            if (t.isAudiblyActive != activeMasks[i]) t.copy(isAudiblyActive = activeMasks[i])
                            else t
                        }
                    ))
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    // NOTE: MIDI output is pumped by GrooveboxViewModel (attachMidiManager).
    // A second pump here used to read the same events with a stale 4-int
    // stride, duplicating and corrupting note output on Android.

    // Poll sequencer steps during recording to ensure UI sync
    LaunchedEffect(state.isRecording, state.selectedTrackIndex, state.currentSequencerBank) {
        if (state.isRecording) {
            while (true) {
                val bankOffset = latestState.currentSequencerBank * 16
                var stateNeedsUpdate = false
                val updatedTracks = latestState.tracks.mapIndexed { tIdx, t ->
                    if (tIdx == latestState.selectedTrackIndex) {
                        val isDrum = t.engineType == EngineType.FM_DRUM
                        val nativeStates = nativeLib.getAllStepActiveStates(tIdx)
                        if (nativeStates.isEmpty()) return@mapIndexed t

                        if (isDrum) {
                            val instIdx = t.selectedFmDrumInstrument
                            val newDrumStepsList = t.drumSteps.mapIndexed { di, dsteps ->
                                if (di == instIdx) {
                                    var drumChanged = false
                                    val newSteps = dsteps.mapIndexed { si, s ->
                                        if (si < nativeStates.size) {
                                            val nativeActive = nativeStates[si]
                                            if (nativeActive) {
                                                val nativeNotes = nativeLib.getStepNotes(tIdx, si, di).toList()
                                                val nativeVel = nativeLib.getStepVelocity(tIdx, si, di)
                                                
                                                if (nativeActive != s.active || nativeNotes != s.notes || Math.abs(nativeVel - s.velocity) > 0.01f) {
                                                    drumChanged = true
                                                    s.copy(active = nativeActive, notes = nativeNotes, velocity = nativeVel)
                                                } else s
                                            } else {
                                                if (s.active) {
                                                    drumChanged = true
                                                    s.copy(active = false)
                                                } else s
                                            }
                                        } else s
                                    }
                                    if (drumChanged) stateNeedsUpdate = true
                                    newSteps
                                } else dsteps
                            }
                            t.copy(drumSteps = newDrumStepsList)
                        } else {
                            var trackChanged = false
                            val newSteps = t.steps.mapIndexed { si, s ->
                                if (si < nativeStates.size) {
                                    val nativeActive = nativeStates[si]
                                    if (nativeActive) {
                                        // Pull recorded data from engine
                                        // Pull recorded data from engine
                                        val nativeNotes = nativeLib.getStepNotes(tIdx, si).toList()
                                        val nativeVel = nativeLib.getStepVelocity(tIdx, si)
                                        val nativeSub = nativeLib.getStepSubStep(tIdx, si)
                                        
                                        if (nativeActive != s.active || nativeNotes != s.notes || Math.abs(nativeVel - s.velocity) > 0.01f) {
                                            trackChanged = true
                                            s.copy(active = nativeActive, notes = nativeNotes, velocity = nativeVel, subStepOffset = nativeSub)
                                        } else s
                                    } else {
                                        if (s.active) {
                                            trackChanged = true
                                            s.copy(active = false)
                                        } else s
                                    }
                                } else s
                            }
                            if (trackChanged) stateNeedsUpdate = true
                            t.copy(steps = newSteps)
                        }
                    } else t
                }
                
                if (stateNeedsUpdate) {
                    latestOnStateChange(latestState.copy(tracks = updatedTracks))
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    // Dynamic Pattern Length Calculation
    LaunchedEffect(state.tracks, state.currentSequencerBank) {
        var maxUsedStep = 15 // Default 16 steps
        state.tracks.forEach { track ->
            track.steps.forEachIndexed { i, step ->
                if (step.active) maxUsedStep = maxUsedStep.coerceAtLeast(i)
            }
            track.drumSteps.forEach { steps ->
                steps.forEachIndexed { i, step ->
                    if (step.active) maxUsedStep = maxUsedStep.coerceAtLeast(i)
                }
            }
        }
        
        // Also consider current bank viewing
        val viewingLastStep = (state.currentSequencerBank + 1) * 16 - 1
        maxUsedStep = maxUsedStep.coerceAtLeast(viewingLastStep)
        
        val newLength = if (state.is64StepView) 64
                        else if (maxUsedStep < 16) 16
                        else if (maxUsedStep < 32) 32
                        else if (maxUsedStep < 48) 48
                        else 64
        
        if (newLength != state.patternLength) {
            onStateChange(state.copy(patternLength = newLength))
            nativeLib.setPatternLength(-1, newLength)
        }
    }

    // State for SideSheet is no longer needed
    var arpSheetOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Edge: Sidebar Mixer
            MixerView(state, onStateChange, nativeLib)

        // Center: Main Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(androidx.compose.ui.graphics.RectangleShape)
                .then(
                    if (state.isRecording && (state.selectedTab == 0 || state.selectedTab == 1 || state.selectedTab == 2 || state.selectedTab == 3)) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.1f,
                            targetValue = 0.5f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                        Modifier.border(4.dp, Color.Red.copy(alpha = alpha))
                    } else Modifier
                )
        ) {
            when (state.selectedTab) {
                0 -> PlayingScreen(state, onStateChange, nativeLib, empledManager, midiManager)
                1 -> ParametersScreen(viewModel, state.selectedTrackIndex, nativeLib, onRecordingSourceChange = onRecordingSourceChange)
                2 -> SequencerView(viewModel, nativeLib, empledManager)
                3 -> GlobalEffectsView(state, onStateChange, nativeLib)
                4 -> RoutingScreen(state, onStateChange, nativeLib)
                5 -> SettingsScreen(state, onStateChange, nativeLib, midiManager)
            }
            
            if (state.showCpuMonitor) {
                CpuMonitor(
                    nativeLib = nativeLib,
                    isPlaying = state.isPlaying,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                )
            }



            // Parameter Value Display (Bottom Right)
            val displayValue = localFocusedValue ?: state.focusedValue
            // Auto-clear focusedValue after 3 seconds
            LaunchedEffect(state.focusedValue) {
                if (state.focusedValue != null) {
                    kotlinx.coroutines.delay(3000)
                    onStateChange(state.copy(focusedValue = null))
                }
            }
            val isPhone = LocalConfiguration.current.screenWidthDp < 600
            displayValue?.let { valStr ->
                Text(
                    text = valStr,
                    style = if (isPhone) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) 
                            else MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd) // Moved to Top
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            
            // MIDI Learn Banner
            if (state.midiLearnActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Yellow),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val text = when(state.midiLearnStep) {
                                1 -> "MIDI LEARN ACTIVE: SELECT STRIP"
                                2 -> "SELECT PARAMETER TO MAP"
                                else -> "MIDI LEARN ACTIVE"
                            }
                            Text(text, color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Text("Click a UI control to map", color = Color.Black.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Parameter Lock Banner
            if (state.isParameterLocking) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 60.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Magenta),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        onClick = { onStateChange(state.copy(isParameterLocking = false)) }
                    ) {
                        Text("RECORDING PARAMETER LOCK (Tap to Exit)", modifier = Modifier.padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Right Sidebars
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Transport Column
                Box(modifier = Modifier.width(80.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.2f))) {
                    TransportControls(state, onStateChange, nativeLib)
                }
                
                // Vertical Navigation Tabs
                VerticalNavigationTabs(state.selectedTab, state.isRecording) { onStateChange(state.copy(selectedTab = it)) }
            }
        }
    }

    // Modal Side Sheet Overlay
    // EngineSideSheet call removed

    // Arp Settings Overlay
    ArpSettingsSheet(
        isOpen = arpSheetOpen,
        onDismiss = { arpSheetOpen = false },
        state = state,
        onStateChange = onStateChange,
        nativeLib = nativeLib
        )
        }
    }




@Composable
fun VerticalNavigationTabs(selectedTab: Int, isRecording: Boolean, onTabSelected: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.5f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        val tabItems = listOf("Play", "Param", "Seq", "FX", "Patch", "Settings")
        tabItems.forEachIndexed { index, title ->
            val isRecordTarget = isRecording && (index == 0 || index == 1 || index == 2)
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selectedTab == index) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .background(Color.Cyan.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .border(1.dp, Color.Cyan, RoundedCornerShape(4.dp))
                    )
                } else if (isRecordTarget) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .border(1.dp, Color.Red, RoundedCornerShape(4.dp))
                    )
                }
                
                if (index < 5) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedTab == index) Color.Cyan else if (isRecordTarget) Color.Red else Color.White,
                        modifier = Modifier.graphicsLayer { rotationZ = 90f }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = title,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 90f },
                        tint = if (selectedTab == index) Color.Cyan else Color.White
                    )
                }
            }
        }
    }
}

// Moved to com.groovebox.ui.views.SettingsScreen


@Composable
fun SoundFontCreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Credits & Privacy Policy", color = Color.White) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Loom includes curated sounds and code. We thank the following creators:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
                
                CreditItem("GeneralUser GS", "S. Christian Collins", "GNU GPL v2", "A versatile GM/GS soundset.")
                CreditItem("Chorium Rev A", "open-samples.com", "GPL", "High-quality transparent GM soundset.")
                CreditItem("Musical Artifacts", "musical-artifacts.com", "Open Community", "A fantastic resource for soundfonts and presets.")
                
                Spacer(modifier = Modifier.height(16.dp))
                Text("PRIVACY POLICY", style = MaterialTheme.typography.labelLarge, color = Color.Magenta)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Loom Groovebox does NOT collect, store, or transmit any personal data.\n\n" +
                    "AUDIO RECORDING: The app requests access to the microphone solely for the 'Sampler' feature, allowing users to record their own sounds for use within the engine. Recorded audio is processed locally and is never uploaded or shared.\n\n" +
                    "STORAGE: The app accesses its own internal storage to save projects and recorded samples. It does not access unrelated personal files.\n\n" +
                    "DATA COLLECTION: No user data, analytics, or behavioral tracking information is collected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("LICENSING", style = MaterialTheme.typography.labelLarge, color = Color.Cyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Loom Groovebox is Free Software released under the GNU GPL v3.\n\n" +
                    "Copyright © 2026 R Daniel Miller\n\n" +
                    "This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.",
                    style = MaterialTheme.typography.labelSmall, 
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Text("Note: Larger SoundFonts like VSCO 2 and SGM are not bundled to keep the app size compact. You can import them manually via the SoundFont selector.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("CLOSE") }
        },
        containerColor = Color(0xFF121212),
        tonalElevation = 8.dp
    )
}

@Composable
fun CreditItem(name: String, author: String, license: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = Color.Cyan, fontWeight = FontWeight.Bold)
        Text("By $author", style = MaterialTheme.typography.bodySmall, color = Color.White)
        Text("License: $license", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        Divider(modifier = Modifier.padding(top = 8.dp), color = Color.DarkGray.copy(alpha=0.3f))
    }
}


@Composable
fun TransportControls(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)

    val isWideScreen = (LocalConfiguration.current.screenWidthDp.toFloat() / LocalConfiguration.current.screenHeightDp.toFloat()) > 1.7f
    val spacing = if (isWideScreen) 4.dp else 4.dp // Reduced vertical padding

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = spacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isWideScreen) Arrangement.spacedBy(4.dp) else Arrangement.spacedBy(4.dp) // Reduced from 16.dp to 4.dp (75% reduction)
    ) {
        // Cluster 1: BPM and Swing
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (isWideScreen) 4.dp else 2.dp)) { // Reduced from 8.dp to 2.dp
            Knob("BPM", (latestState.tempo - 12f) / 228f, -1, latestState, latestOnStateChange, nativeLib, knobSize = if (isWideScreen) 40.dp else 56.dp, 
                onValueChangeOverride = {
                    val newBpm = 12f + (it * 228f)
                    latestOnStateChange(latestState.copy(tempo = newBpm))
                    nativeLib.setTempo(newBpm)
                },
                valueFormatter = {
                    val bpm = 12f + (it * 228f)
                    "BPM: ${bpm.toInt()}"
                }
            )
            
            var tapTimes by remember { mutableStateOf(listOf<Long>()) }
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    val newTaps = tapTimes.filter { now - it < 2000 } + now
                    if (newTaps.size >= 2) {
                        val intervals = newTaps.zipWithNext { a, b -> b - a }
                        val avgInterval = intervals.average()
                        val newBpm = (60000f / avgInterval.toFloat()).coerceIn(12f, 240f)
                        latestOnStateChange(latestState.copy(tempo = newBpm))
                        nativeLib.setTempo(newBpm)
                    }
                    tapTimes = newTaps
                },
                modifier = Modifier.height(24.dp).width(50.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("Tap${latestState.tempo.toInt()}", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White)
            }
            // Removed redundant BPM Text
            
            // Removed Spacer
            
            Knob("SWNG", latestState.swing, -1, latestState, latestOnStateChange, nativeLib, knobSize = if (isWideScreen) 40.dp else 56.dp, onValueChangeOverride = {
                latestOnStateChange(latestState.copy(swing = it))
                nativeLib.setSwing(it)
            })
            if (!isWideScreen) Text("${(latestState.swing * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)

            // Removed Spacer


        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 2: Play, Record, Stop
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Record Button
            Box(
                modifier = Modifier
                    .size(if (isWideScreen) 34.dp else 44.dp)
                    .background(if (latestState.isRecording) Color.Red else Color.Red.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
                    .border(2.dp, Color.Red, RoundedCornerShape(22.dp))
                    .clickable {
                        val current = latestState
                        val newRec = !current.isRecording
                        latestOnStateChange(current.copy(isRecording = newRec, isPlaying = if (newRec) true else current.isPlaying))
                        nativeLib.setIsRecording(newRec)
                        if (newRec) nativeLib.setPlaying(true)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (latestState.isRecording) {
                    Box(modifier = Modifier.size(if (isWideScreen) 10.dp else 14.dp).background(Color.White, RoundedCornerShape(6.dp)))
                } else {
                    Box(modifier = Modifier.size(if (isWideScreen) 10.dp else 14.dp).background(Color.Red, RoundedCornerShape(6.dp)))
                }
            }

            // Play Button
            IconButton(
                onClick = { 
                    val current = latestState
                    val newPlaying = !current.isPlaying
                    latestOnStateChange(current.copy(isPlaying = newPlaying))
                    nativeLib.setPlaying(newPlaying)
                },
                modifier = Modifier.size(if (isWideScreen) 34.dp else 44.dp).background(if (latestState.isPlaying) Color.Green.copy(alpha = 0.2f) else Color.DarkGray, RoundedCornerShape(22.dp))
            ) {
                Icon(
                    Icons.Filled.PlayArrow, 
                    contentDescription = "Play", 
                    tint = if (latestState.isPlaying) Color.Green else Color.White,
                    modifier = Modifier.size(if (isWideScreen) 18.dp else 24.dp)
                )
            }
            // Stop Button
            var stopFlash by remember { mutableStateOf(false) }
            val stopColor by animateColorAsState(if (stopFlash) Color.Red else Color.Gray)
            
            Box(
                modifier = Modifier
                    .size(if (isWideScreen) 34.dp else 44.dp)
                    .background(stopColor, RoundedCornerShape(22.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            stopFlash = true
                            val current = latestState
                            latestOnStateChange(current.copy(isPlaying = false, isRecording = false))
                            nativeLib.setPlaying(false)
                            nativeLib.setIsRecording(false)
                            nativeLib.panic()
                            try { awaitRelease() } finally { stopFlash = false }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(if (isWideScreen) 14.dp else 18.dp).background(Color.White, RoundedCornerShape(2.dp)))
            }
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 3: Master Volume
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Knob(if (isWideScreen) "VOL" else "MASTER", latestState.masterVolume, -1, latestState, latestOnStateChange, nativeLib, knobSize = if (isWideScreen) 36.dp else 48.dp, onValueChangeOverride = {
                latestOnStateChange(latestState.copy(masterVolume = it))
                nativeLib.setMasterVolume(it)
            })
            if (!isWideScreen) Text("VOLUME", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 8.sp)
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 4.dp))

        // Cluster 4: Playback Order (Consolidated)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            val buttonModifier = Modifier.size(if (isWideScreen) 34.dp else 46.dp, 30.dp)
            val buttonShape = RoundedCornerShape(8.dp)
            
            // Determine current state for display
            val (label, color) = when {
                latestState.isRandomOrder -> "RND" to Color.Magenta
                latestState.playbackDirection == 2 -> "PNG" to Color.Cyan
                latestState.playbackDirection == 1 -> "REV" to Color.Red
                else -> "REG" to Color.Gray
            }
            
            Button(
                onClick = {
                    val current = latestState
                    // Cycle: REG -> REV -> PNG -> RND -> REG
                    if (!current.isRandomOrder) {
                        when (current.playbackDirection) {
                            0 -> { // REG -> REV
                                latestOnStateChange(current.copy(playbackDirection = 1))
                                nativeLib.setPlaybackDirection(current.selectedTrackIndex, 1)
                            }
                            1 -> { // REV -> PNG
                                latestOnStateChange(current.copy(playbackDirection = 2))
                                nativeLib.setPlaybackDirection(current.selectedTrackIndex, 2)
                            }
                            2 -> { // PNG -> RND
                                latestOnStateChange(current.copy(playbackDirection = 0, isRandomOrder = true))
                                nativeLib.setPlaybackDirection(current.selectedTrackIndex, 0)
                                nativeLib.setIsRandomOrder(current.selectedTrackIndex, true)
                            }
                        }
                    } else {
                        // RND -> REG
                        latestOnStateChange(current.copy(isRandomOrder = false, playbackDirection = 0))
                        nativeLib.setIsRandomOrder(current.selectedTrackIndex, false)
                        nativeLib.setPlaybackDirection(current.selectedTrackIndex, 0)
                    }
                },
                modifier = buttonModifier,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = color),
                shape = buttonShape
            ) { 
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) 
            }
            
            Text("ORDER", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 8.sp)
        }
    }
}







@Composable
fun GrooveboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFBB86FC),
            secondary = Color(0xFF03DAC6),
            background = Color(0xFF121212),
            surface = Color(0xFF121212)
        ),
// End of GrooveboxTheme
        content = content
    )
}



/**
 * The CPU readout owns its own state so a changing meter value cannot
 * invalidate the screen around it. Previously the value lived in MainScreen's
 * scope, which re-composed the whole tab a few times a second while idle.
 */
@Composable
private fun CpuMonitor(nativeLib: NativeLib, isPlaying: Boolean, modifier: Modifier = Modifier) {
    var percent by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (true) {
            val value = (nativeLib.getCpuLoad() * 100f).toInt().coerceIn(0, 999)
            percent = value
            // A meter tick costs a full frame, so keep it slow while idle and
            // only refresh quickly while the transport is running.
            kotlinx.coroutines.delay(if (isPlaying) 250L else 1000L)
        }
    }
    Text(
        text = if (percent < 0) "CPU: --" else "CPU: $percent%",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.5f),
        // Fixed width plus its own layer: a meter tick then only re-records
        // this small node instead of the whole window.
        modifier = modifier.width(64.dp).graphicsLayer()
    )
}
