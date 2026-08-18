package fr.mamieturbo.ui

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import fr.mamieturbo.BuildConfig
import fr.mamieturbo.R
import fr.mamieturbo.audio.AudioRecorder
import fr.mamieturbo.kiosk.KioskManager
import fr.mamieturbo.kiosk.PowerWakePolicy
import fr.mamieturbo.transcription.LocalTranscriptionSettings
import fr.mamieturbo.transcription.OpenAIRealtimeTranscriptionService
import fr.mamieturbo.transcription.RealtimeTranscriptionService
import fr.mamieturbo.transcription.SherpaOnnxTranscriptionService
import fr.mamieturbo.transcription.SpeechSensitivity
import fr.mamieturbo.transcription.TranscriptionEvent
import fr.mamieturbo.transcription.TranscriptionMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFERENCES = "mamieturbo_settings"
        private const val PREF_SILENCE_FILTER = "silence_filter_enabled"
        private const val PREF_SENSITIVITY = "speech_sensitivity"
        private const val PREF_TRANSCRIPTION_MODE = "transcription_mode"
        private const val PREF_OPENAI_API_KEY = "openai_api_key"
        private const val PREF_OPENAI_LANGUAGE = "openai_language"
        private const val PREF_LOCAL_THREADS = "local_threads"
        private const val PREF_LOCAL_ENDPOINT = "local_endpoint_seconds"
        private const val PREF_POWER_STATE_INITIALIZED = "power_state_initialized"
        private const val PREF_WAS_PLUGGED = "was_plugged"
    }

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var recorder: AudioRecorder
    private lateinit var openAiService: OpenAIRealtimeTranscriptionService
    private lateinit var localService: SherpaOnnxTranscriptionService
    private lateinit var activeService: RealtimeTranscriptionService
    private lateinit var kiosk: KioskManager
    private var activeMode = TranscriptionMode.OPENAI
    private var serviceJob: Job? = null
    private var maintenanceDialog: AlertDialog? = null
    private val finals = ArrayDeque<String>()
    private val partials = linkedMapOf<String, StringBuilder>()
    private var active = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && active) startPipeline() else showStatus("🔴 Micro indisponible")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        transcript = findViewById(R.id.transcriptText)
        scroll = findViewById(R.id.transcriptScroll)
        recorder = AudioRecorder(this)
        openAiService = OpenAIRealtimeTranscriptionService()
        localService = SherpaOnnxTranscriptionService(this)
        applyOpenAiSettings()
        applyLocalSettings()
        activeMode = readTranscriptionMode()
        activeService = serviceFor(activeMode)
        kiosk = KioskManager(this)
        status.setOnLongClickListener { showAdminPinDialog(); true }
        lifecycleScope.launch { openAiService.events.collect { if (activeService === openAiService) handleEvent(it) } }
        lifecycleScope.launch { localService.events.collect { if (activeService === localService) handleEvent(it) } }
    }

    override fun onResume() {
        super.onResume()
        if (shouldReturnToSleepAfterChargingWake()) {
            active = false
            window.decorView.post { kiosk.returnDeviceToSleep() }
            return
        }
        active = true
        kiosk.provisionDedicatedDevice()
        kiosk.enterLockTaskIfPermitted()
        kiosk.applyImmersiveMode(BuildConfig.IMMERSIVE_MODE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startPipeline()
        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onPause() {
        active = false
        rememberPowerState()
        maintenanceDialog?.dismiss()
        stopPipeline()
        clearTranscript()
        super.onPause()
    }

    private fun startPipeline() {
        if (serviceJob != null) return
        val service = activeService
        serviceJob = lifecycleScope.launch { service.start() }
        recorder.start(lifecycleScope, { audio, length -> service.sendAudio(audio, length) }) { message ->
            runOnUiThread { showStatus("🔴 $message") }
        }
    }

    private fun stopPipeline() {
        val service = activeService
        recorder.stop()
        val job = serviceJob
        serviceJob = null
        lifecycleScope.launch {
            job?.cancelAndJoin()
            service.stop()
        }
    }

    private fun restartPipeline() {
        val service = activeService
        val job = serviceJob
        recorder.stop()
        serviceJob = null
        clearTranscript()
        lifecycleScope.launch {
            job?.cancelAndJoin()
            service.stop()
            if (active) startPipeline()
        }
    }

    private fun switchMode(mode: TranscriptionMode) {
        if (mode == activeMode) return
        val previousService = activeService
        val previousJob = serviceJob
        recorder.stop()
        serviceJob = null
        activeMode = mode
        activeService = serviceFor(mode)
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(PREF_TRANSCRIPTION_MODE, mode.name)
            .apply()
        clearTranscript()
        lifecycleScope.launch {
            previousJob?.cancelAndJoin()
            previousService.stop()
            if (active) startPipeline()
        }
    }

    private fun serviceFor(mode: TranscriptionMode): RealtimeTranscriptionService = when (mode) {
        TranscriptionMode.OPENAI -> openAiService
        TranscriptionMode.LOCAL -> localService
    }

    private fun handleEvent(event: TranscriptionEvent) = when (event) {
        TranscriptionEvent.Connecting -> showStatus("🟠 ${if (activeMode == TranscriptionMode.LOCAL) "Chargement du mode local..." else "Connexion..."}")
        TranscriptionEvent.Connected -> showStatus("🟠 Préparation...")
        TranscriptionEvent.Listening -> showStatus("🟢 J'écoute${if (activeMode == TranscriptionMode.LOCAL) " — local" else ""}...")
        is TranscriptionEvent.Disconnected -> showStatus("🔴 Connexion interrompue : ${event.reason ?: "réessai"}")
        is TranscriptionEvent.Error -> showStatus("🔴 ${event.message}")
        is TranscriptionEvent.PartialTranscript -> {
            val partial = partials.getOrPut(event.itemId) { StringBuilder() }
            if (event.replacesPrevious) partial.setLength(0)
            partial.append(event.text)
            renderTranscript()
        }
        is TranscriptionEvent.FinalTranscript -> {
            partials.remove(event.itemId)
            if (event.text.isNotBlank()) finals.addLast(event.text.trim())
            while (finals.size > 30) finals.removeFirst()
            renderTranscript()
        }
    }

    private fun showStatus(text: String) {
        val color = when {
            text.startsWith("🟢") -> 0xff4caf50.toInt()
            text.startsWith("🔴") -> 0xfff44336.toInt()
            else -> 0xffff9800.toInt()
        }
        (statusDot.background.mutate() as? GradientDrawable)?.setColor(color)
        status.text = text.removePrefix("🟢").removePrefix("🟠").removePrefix("🔴").trimStart()
    }

    private fun renderTranscript() {
        val text = buildString {
            finals.forEach { append(it).append("\n\n") }
            partials.values.forEach(::append)
        }
        transcript.text = text.ifEmpty { "Parlez, les sous-titres apparaîtront ici." }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun clearTranscript() {
        finals.clear()
        partials.clear()
        transcript.text = "Parlez, les sous-titres apparaîtront ici."
        scroll.scrollTo(0, 0)
    }

    private fun showAdminPinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN administrateur"
        }
        AlertDialog.Builder(this)
            .setTitle("Accès administrateur")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Ouvrir") { _, _ ->
                if (input.text.toString() == BuildConfig.ADMIN_PIN) showMaintenanceMenu()
                else showStatus("🔴 PIN incorrect")
            }
            .show()
    }

    private fun showMaintenanceMenu() {
        maintenanceDialog?.dismiss()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        columns.addView(openAiColumn(), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        columns.addView(View(this).apply { setBackgroundColor(0xff555555.toInt()) }, LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(10); marginEnd = dp(10)
        })
        columns.addView(localColumn(), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(columns)

        root.addView(TextView(this).apply {
            text = "APPAREIL"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(14), 0, dp(4))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(adminButton("Wi-Fi") { maintenanceDialog?.dismiss(); openMaintenanceSettings(Settings.ACTION_WIFI_SETTINGS) }, weightedButtonParams())
        actions.addView(adminButton("Paramètres") { maintenanceDialog?.dismiss(); openMaintenanceSettings(Settings.ACTION_SETTINGS) }, weightedButtonParams())
        actions.addView(adminButton("Suspendre le kiosque") {
            maintenanceDialog?.dismiss(); kiosk.exitLockTask(); showStatus("🟠 Kiosque suspendu jusqu'au prochain retour")
        }, weightedButtonParams())
        root.addView(actions)

        maintenanceDialog = AlertDialog.Builder(this)
            .setTitle("Maintenance — moteurs de transcription")
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton("Fermer", null)
            .create()
        maintenanceDialog?.show()
    }

    private fun openAiColumn() = adminColumn(
        title = "OPENAI — EN LIGNE",
        activeColumn = activeMode == TranscriptionMode.OPENAI,
        details = "Qualité maximale · Internet requis"
    ).apply {
        addView(adminButton(if (activeMode == TranscriptionMode.OPENAI) "Mode actif" else "Utiliser OpenAI") {
            switchMode(TranscriptionMode.OPENAI); showMaintenanceMenu()
        }.apply { isEnabled = activeMode != TranscriptionMode.OPENAI })
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val hasApiKey = preferences.getString(PREF_OPENAI_API_KEY, "").orEmpty().isNotBlank()
        addView(adminButton("Clé API : ${if (hasApiKey) "configurée" else "à renseigner"}") { showApiKeyDialog() })
        addView(adminButton("Langue : ${languageLabel(readOpenAiLanguage())}") { showLanguageDialog() })
        val filtering = preferences.getBoolean(PREF_SILENCE_FILTER, true)
        addView(adminButton("Silences : ${if (filtering) "filtrés" else "tout envoyer"}") {
            preferences.edit().putBoolean(PREF_SILENCE_FILTER, !filtering).apply()
            applyOpenAiSettings(); showMaintenanceMenu()
        })
        addView(adminButton("Sensibilité : ${sensitivityLabel(readSensitivity())}") { showSensitivityDialog() })
        addView(adminButton("Économie audio : ${openAiService.audioGateStats().savedPercent}%") { showAudioSavingsDialog() })
    }

    private fun localColumn() = adminColumn(
        title = "SHERPA — HORS LIGNE",
        activeColumn = activeMode == TranscriptionMode.LOCAL,
        details = if (localService.isModelAvailable()) "Expérimental · aucune donnée envoyée" else "Non inclus · voir le README"
    ).apply {
        val localAvailable = localService.isModelAvailable()
        addView(adminButton(when {
            activeMode == TranscriptionMode.LOCAL -> "Mode actif"
            localAvailable -> "Tester le mode local"
            else -> "Modèle local non installé"
        }) {
            switchMode(TranscriptionMode.LOCAL); showMaintenanceMenu()
        }.apply { isEnabled = localAvailable && activeMode != TranscriptionMode.LOCAL })
        val settings = readLocalSettings()
        addView(adminButton("Processeur : ${settings.numThreads} thread${if (settings.numThreads > 1) "s" else ""}") { showLocalThreadsDialog() })
        addView(adminButton("Fin de phrase : ${formatDecimal(settings.endpointSilenceSeconds)} s") { showLocalEndpointDialog() })
        addView(adminButton("Performances locales") { showLocalStatsDialog() })
    }

    private fun adminColumn(title: String, activeColumn: Boolean, details: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(if (activeColumn) 0xff81c784.toInt() else Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = details
            setTextColor(Color.LTGRAY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        })
    }

    private fun adminButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun showSensitivityDialog() {
        maintenanceDialog?.dismiss()
        val levels = SpeechSensitivity.entries.toTypedArray()
        val labels = arrayOf("Faible — moins de bruit", "Normale — recommandée", "Élevée — voix faibles")
        AlertDialog.Builder(this)
            .setTitle("Sensibilité OpenAI")
            .setSingleChoiceItems(labels, levels.indexOf(readSensitivity())) { dialog, which ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(PREF_SENSITIVITY, levels[which].name).apply()
                applyOpenAiSettings(); dialog.dismiss(); showMaintenanceMenu()
            }
            .setNegativeButton("Annuler") { _, _ -> showMaintenanceMenu() }
            .show()
    }

    private fun showApiKeyDialog() {
        maintenanceDialog?.dismiss()
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-..."
            setText(preferences.getString(PREF_OPENAI_API_KEY, ""))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Clé API OpenAI")
            .setMessage("La clé reste dans le stockage privé de l'application et n'est jamais incluse dans l'APK.")
            .setView(input)
            .setNegativeButton("Annuler") { _, _ -> showMaintenanceMenu() }
            .setNeutralButton("Effacer") { _, _ ->
                preferences.edit().remove(PREF_OPENAI_API_KEY).apply()
                applyOpenAiSettings()
                restartOpenAiIfActive()
                showMaintenanceMenu()
            }
            .setPositiveButton("Enregistrer") { _, _ ->
                preferences.edit().putString(PREF_OPENAI_API_KEY, input.text.toString().trim()).apply()
                applyOpenAiSettings()
                restartOpenAiIfActive()
                showMaintenanceMenu()
            }
            .show()
    }

    private fun showLanguageDialog() {
        maintenanceDialog?.dismiss()
        val codes = arrayOf("fr", "en", "de", "es", "it", "pt", "nl")
        val labels = codes.map(::languageLabel).toTypedArray()
        val current = codes.indexOf(readOpenAiLanguage()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Langue de transcription")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(PREF_OPENAI_LANGUAGE, codes[which]).apply()
                applyOpenAiSettings()
                dialog.dismiss()
                restartOpenAiIfActive()
                showMaintenanceMenu()
            }
            .setNegativeButton("Annuler") { _, _ -> showMaintenanceMenu() }
            .show()
    }

    private fun showLocalThreadsDialog() {
        maintenanceDialog?.dismiss()
        val values = intArrayOf(1, 2, 4)
        val labels = arrayOf("1 — tablette très lente", "2 — recommandé", "4 — tablette puissante")
        val current = values.indexOf(readLocalSettings().numThreads).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Threads du moteur local")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putInt(PREF_LOCAL_THREADS, values[which]).apply()
                applyLocalSettings(); dialog.dismiss(); restartLocalIfActive(); showMaintenanceMenu()
            }
            .setNegativeButton("Annuler") { _, _ -> showMaintenanceMenu() }.show()
    }

    private fun showLocalEndpointDialog() {
        maintenanceDialog?.dismiss()
        val values = floatArrayOf(0.8f, 1.2f, 1.8f)
        val labels = arrayOf("0,8 s — paragraphes rapides", "1,2 s — recommandé", "1,8 s — longues pauses")
        val current = values.indexOfFirst { it == readLocalSettings().endpointSilenceSeconds }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Silence de fin de phrase")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putFloat(PREF_LOCAL_ENDPOINT, values[which]).apply()
                applyLocalSettings(); dialog.dismiss(); restartLocalIfActive(); showMaintenanceMenu()
            }
            .setNegativeButton("Annuler") { _, _ -> showMaintenanceMenu() }.show()
    }

    private fun showAudioSavingsDialog() {
        maintenanceDialog?.dismiss()
        val stats = openAiService.audioGateStats()
        AlertDialog.Builder(this).setTitle("Économie OpenAI")
            .setMessage("Audio entendu : ${formatDuration(stats.capturedSeconds)}\nAudio envoyé : ${formatDuration(stats.transmittedSeconds)}\nAudio évité : ${stats.savedPercent}%\n\nMesure depuis le dernier déverrouillage.")
            .setPositiveButton("OK") { _, _ -> showMaintenanceMenu() }.show()
    }

    private fun showLocalStatsDialog() {
        maintenanceDialog?.dismiss()
        val stats = localService.engineStats()
        val speed = when {
            stats.processedAudioMillis == 0L -> "pas encore mesurée"
            stats.realtimeFactor <= 1.0 -> "temps réel (${formatDecimal(stats.realtimeFactor.toFloat())}×)"
            else -> "trop lent (${formatDecimal(stats.realtimeFactor.toFloat())}×)"
        }
        AlertDialog.Builder(this).setTitle("Performances sherpa-onnx")
            .setMessage("Chargement du modèle : ${stats.initializationMillis / 1000.0} s\nVitesse : $speed\nAudio traité : ${formatDuration(stats.processedAudioMillis / 1000)}\nTampons perdus : ${stats.droppedChunks}\n\nUne valeur inférieure ou égale à 1× signifie que la tablette suit la conversation en direct.")
            .setPositiveButton("OK") { _, _ -> showMaintenanceMenu() }.show()
    }

    private fun restartLocalIfActive() { if (activeMode == TranscriptionMode.LOCAL) restartPipeline() }

    private fun applyOpenAiSettings() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        openAiService.configureCredentials(
            preferences.getString(PREF_OPENAI_API_KEY, "").orEmpty(),
            readOpenAiLanguage()
        )
        openAiService.configureSilenceFiltering(preferences.getBoolean(PREF_SILENCE_FILTER, true), readSensitivity())
    }

    private fun applyLocalSettings() { localService.configure(readLocalSettings()) }

    private fun readSensitivity(): SpeechSensitivity {
        val stored = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(PREF_SENSITIVITY, SpeechSensitivity.NORMAL.name)
        return runCatching { SpeechSensitivity.valueOf(stored.orEmpty()) }.getOrDefault(SpeechSensitivity.NORMAL)
    }

    private fun readTranscriptionMode(): TranscriptionMode {
        val stored = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(PREF_TRANSCRIPTION_MODE, TranscriptionMode.OPENAI.name)
        val requested = runCatching { TranscriptionMode.valueOf(stored.orEmpty()) }.getOrDefault(TranscriptionMode.OPENAI)
        return if (requested == TranscriptionMode.LOCAL && !localService.isModelAvailable()) TranscriptionMode.OPENAI else requested
    }

    private fun readOpenAiLanguage() = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        .getString(PREF_OPENAI_LANGUAGE, "fr").orEmpty().ifBlank { "fr" }

    private fun languageLabel(code: String) = when (code) {
        "fr" -> "Français"
        "en" -> "English"
        "de" -> "Deutsch"
        "es" -> "Español"
        "it" -> "Italiano"
        "pt" -> "Português"
        "nl" -> "Nederlands"
        else -> code
    }

    private fun restartOpenAiIfActive() { if (activeMode == TranscriptionMode.OPENAI) restartPipeline() }

    private fun readLocalSettings(): LocalTranscriptionSettings {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        return LocalTranscriptionSettings(
            numThreads = preferences.getInt(PREF_LOCAL_THREADS, 2),
            endpointSilenceSeconds = preferences.getFloat(PREF_LOCAL_ENDPOINT, 1.2f)
        )
    }

    private fun sensitivityLabel(value: SpeechSensitivity) = when (value) {
        SpeechSensitivity.LOW -> "faible"
        SpeechSensitivity.NORMAL -> "normale"
        SpeechSensitivity.HIGH -> "élevée"
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remaining = seconds % 60
        return if (minutes == 0L) "${remaining} s" else "${minutes} min ${remaining} s"
    }

    private fun formatDecimal(value: Float) = String.format(Locale.FRANCE, "%.1f", value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun shouldReturnToSleepAfterChargingWake(): Boolean {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val plugged = isPowerConnected()
        val shouldSleep = kiosk.isDeviceOwner() && PowerWakePolicy.shouldReturnToSleep(
            preferences.getBoolean(PREF_POWER_STATE_INITIALIZED, false),
            preferences.getBoolean(PREF_WAS_PLUGGED, plugged),
            plugged
        )
        preferences.edit().putBoolean(PREF_POWER_STATE_INITIALIZED, true).putBoolean(PREF_WAS_PLUGGED, plugged).apply()
        return shouldSleep
    }

    private fun rememberPowerState() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(PREF_POWER_STATE_INITIALIZED, true)
            .putBoolean(PREF_WAS_PLUGGED, isPowerConnected())
            .apply()
    }

    private fun isPowerConnected(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun openMaintenanceSettings(action: String) {
        kiosk.exitLockTask()
        runCatching { startActivity(Intent(action)) }.onFailure { showStatus("🔴 Paramètres indisponibles") }
    }
}
