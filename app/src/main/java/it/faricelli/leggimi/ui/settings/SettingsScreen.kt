package it.faricelli.leggimi.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.faricelli.leggimi.data.eleven.VoiceSettings
import it.faricelli.leggimi.ui.appContainer
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onPickVoice: () -> Unit, onPickModel: () -> Unit) {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keyState by vm.keyCheck.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var keyText by remember { mutableStateOf("") }
    var keyLoaded by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        keyText = vm.loadApiKey().orEmpty()
        keyLoaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            Section("Account ElevenLabs")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    enabled = keyLoaded,
                    label = { Text("Chiave API") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Mostra")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveAndVerify(keyText) }, enabled = keyLoaded && keyState !is KeyCheck.Checking) {
                        Text("Salva e verifica")
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://elevenlabs.io/app/settings/api-keys".toUri()))
                    }) { Text("Dove la trovo?") }
                }
                when (val k = keyState) {
                    KeyCheck.Idle -> if (!s.hasApiKey) Text(
                        "La chiave resta cifrata sul telefono e viene inviata solo a ElevenLabs.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    KeyCheck.Checking -> CircularProgressIndicator(Modifier.padding(4.dp))
                    is KeyCheck.Ok -> {
                        val nf = NumberFormat.getIntegerInstance(Locale.ITALY)
                        val remaining = (k.sub.characterLimit - k.sub.characterCount).coerceAtLeast(0)
                        Text(
                            "Chiave valida · piano ${k.sub.tier}\nCaratteri usati: ${nf.format(k.sub.characterCount)} su ${nf.format(k.sub.characterLimit)} (restano ${nf.format(remaining)})",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is KeyCheck.Failed -> Text(k.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Section("Voce e modello predefiniti")
            ListItem(
                headlineContent = { Text("Voce") },
                supportingContent = { Text(s.voiceName ?: "Nessuna voce scelta") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPickVoice),
            )
            ListItem(
                headlineContent = { Text("Modello") },
                supportingContent = { Text(s.modelName) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPickModel),
            )

            Section("Regolazioni della voce")
            Text(
                "Valgono per tutti i documenti. Cambiarle rigenera l'audio e consuma crediti.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            VoiceSettingsEditor(s.voiceSettings, onChange = vm::setVoiceSettings)

            var lang by remember(s.languageCode) { mutableStateOf(s.languageCode) }
            OutlinedTextField(
                value = lang,
                onValueChange = { lang = it.take(3) },
                label = { Text("Lingua del testo (opzionale)") },
                supportingText = { Text("Codice ISO, es. \"it\". Lascia vuoto per il riconoscimento automatico.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (lang.trim().lowercase() != s.languageCode) {
                TextButton(onClick = { vm.setLanguage(lang) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Salva lingua") }
            }

            Section("Audio salvato")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Occupa ${Formatter.formatShortFileSize(context, cacheBytes)}. Riascoltare un brano già generato non consuma crediti.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = vm::clearCache, enabled = cacheBytes > 0) { Text("Svuota") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun VoiceSettingsEditor(value: VoiceSettings, onChange: (VoiceSettings) -> Unit) {
    var stability by remember(value) { mutableFloatStateOf(value.stability) }
    var similarity by remember(value) { mutableFloatStateOf(value.similarityBoost) }
    var style by remember(value) { mutableFloatStateOf(value.style) }
    var speed by remember(value) { mutableFloatStateOf(value.speed) }
    fun commit(boost: Boolean = value.useSpeakerBoost) =
        onChange(VoiceSettings(stability, similarity, style, boost, speed))

    Column(Modifier.padding(horizontal = 16.dp)) {
        LabeledSlider("Stabilità", "Più bassa = più espressiva, più alta = più costante", stability, 0f..1f, { stability = it }, { commit() })
        LabeledSlider("Somiglianza", "Aderenza alla voce originale", similarity, 0f..1f, { similarity = it }, { commit() })
        LabeledSlider("Stile", "Esagera lo stile del parlato (aumenta i tempi di generazione)", style, 0f..1f, { style = it }, { commit() })
        LabeledSlider("Velocità della voce", "Generata da ElevenLabs (0,7–1,2). La velocità di ascolto si cambia nel lettore.", speed, 0.7f..1.2f, { speed = it }, { commit() }, steps = 9)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Speaker boost", style = MaterialTheme.typography.bodyLarge)
                Text("Voce più presente e simile all'originale", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = value.useSpeakerBoost, onCheckedChange = { commit(it) })
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    onDone: () -> Unit,
    steps: Int = 19,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%.2f".format(Locale.ITALY, value), style = MaterialTheme.typography.bodyMedium)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Slider(value = value, onValueChange = onValue, valueRange = range, steps = steps, onValueChangeFinished = onDone)
    }
}
