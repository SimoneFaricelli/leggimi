package it.faricelli.leggimi.ui.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.faricelli.leggimi.ui.appContainer

private val CATEGORY_LABELS = mapOf(
    "premade" to "Predefinite",
    "cloned" to "Clonate",
    "generated" to "Generate",
    "professional" to "Professionali",
    "famous" to "Famose",
    "high_quality" to "Alta qualità",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                },
            )
        },
        content = content,
    )
}

@Composable
private fun <T> LoadableContent(state: Loadable<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Loadable.Failed -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.message, textAlign = TextAlign.Center)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Riprova") }
        }
        is Loadable.Loaded -> content(state.value)
    }
}

@Composable
fun VoicePickerScreen(docId: String?, onDone: () -> Unit) {
    val container = appContainer()
    val vm: VoicePickerViewModel = viewModel(key = "voice-$docId") { VoicePickerViewModel(container, docId) }
    val voices by vm.voices.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val playing by vm.playingPreview.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    PickerScaffold(if (docId == null) "Voce predefinita" else "Voce del documento", onBack = { vm.stopPreview(); onDone() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadableContent(voices, vm::load) { all ->
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Cerca per nome, accento, genere…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val categories = all.mapNotNull { it.category }.distinct()
                if (categories.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Tutte") })
                        categories.forEach { c ->
                            FilterChip(
                                selected = category == c,
                                onClick = { category = if (category == c) null else c },
                                label = { Text(CATEGORY_LABELS[c] ?: c) },
                            )
                        }
                    }
                }
                val q = query.trim().lowercase()
                val filtered = all.filter { v ->
                    (category == null || v.category == category) &&
                        (q.isEmpty() || v.name.lowercase().contains(q) || v.tags.any { it.lowercase().contains(q) } ||
                            v.description.orEmpty().lowercase().contains(q))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (docId != null && selection?.isOverride == true) {
                        item {
                            TextButton(onClick = { vm.select(null, onDone) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Usa la voce predefinita")
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item { Text("Nessuna voce trovata.", modifier = Modifier.padding(24.dp)) }
                    }
                    items(filtered, key = { it.id }) { voice ->
                        val selected = voice.id == selection?.voiceId
                        ListItem(
                            headlineContent = { Text(voice.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val info = listOfNotNull(CATEGORY_LABELS[voice.category] ?: voice.category) + voice.tags
                                Text(info.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                if (voice.previewUrl != null) {
                                    IconButton(onClick = { vm.togglePreview(voice) }) {
                                        Icon(
                                            if (playing == voice.id) Icons.Filled.StopCircle else Icons.Filled.PlayCircle,
                                            contentDescription = "Ascolta anteprima",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "Selezionata", tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { vm.select(voice, onDone) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun ModelPickerScreen(docId: String?, onDone: () -> Unit) {
    val container = appContainer()
    val vm: ModelPickerViewModel = viewModel(key = "model-$docId") { ModelPickerViewModel(container, docId) }
    val models by vm.models.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()

    PickerScaffold(if (docId == null) "Modello predefinito" else "Modello del documento", onBack = onDone) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadableContent(models, vm::load) { all ->
                LazyColumn(Modifier.fillMaxSize()) {
                    if (docId != null && selection?.isOverride == true) {
                        item {
                            TextButton(onClick = { vm.select(null, onDone) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Usa il modello predefinito")
                            }
                        }
                    }
                    items(all, key = { it.id }) { model ->
                        val selected = model.id == selection?.modelId
                        ListItem(
                            headlineContent = { Text(model.name.ifEmpty { model.id }) },
                            supportingContent = {
                                Column {
                                    if (model.description.isNotBlank()) {
                                        Text(model.description, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    }
                                    val details = buildList {
                                        add(if (model.supportsItalian) "Italiano ✓" else "Italiano non indicato")
                                        if (model.languages.isNotEmpty()) add("${model.languages.size} lingue")
                                        model.maxTextLength?.let { add("max $it caratteri") }
                                    }
                                    Text(
                                        details.joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            },
                            trailingContent = {
                                if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "Selezionato", tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { vm.select(model, onDone) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
