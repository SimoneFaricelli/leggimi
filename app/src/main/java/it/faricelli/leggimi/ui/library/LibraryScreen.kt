package it.faricelli.leggimi.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import it.faricelli.leggimi.data.db.DocumentEntity
import it.faricelli.leggimi.data.extract.DocumentFormat
import it.faricelli.leggimi.ui.MainViewModel
import it.faricelli.leggimi.ui.appContainer
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(mainViewModel: MainViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val container = appContainer()
    val documentsFlow = remember { container.library.observeAll() }
    val documents by documentsFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val importing by mainViewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<DocumentEntity?>(null) }

    LaunchedEffect(Unit) { mainViewModel.messages.collect { snackbar.showSnackbar(it) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mainViewModel.import(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leggimi") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Impostazioni") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (importing == 0) picker.launch(DocumentFormat.pickerMimeTypes) },
                icon = {
                    if (importing > 0) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Add, contentDescription = null)
                },
                text = { Text(if (importing > 0) "Importazione…" else "Aggiungi") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val docs = documents
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (settings?.hasApiKey == false) {
                Card(Modifier.padding(16.dp).fillMaxWidth().clickable(onClick = onSettings)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Configura ElevenLabs", style = MaterialTheme.typography.titleMedium)
                        Text("Inserisci la chiave API e scegli una voce per iniziare ad ascoltare.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            when {
                docs == null -> Unit
                docs.isEmpty() -> EmptyLibrary()
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(docs, key = { it.id }) { doc ->
                        DocumentCard(doc, onClick = { onOpen(doc.id) }, onDelete = { toDelete = doc })
                    }
                }
            }
        }
    }

    toDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare il documento?") },
            text = { Text("\"${doc.title}\" e il relativo audio salvato verranno rimossi dal telefono.") },
            confirmButton = {
                TextButton(onClick = { mainViewModel.delete(doc.id); toDelete = null }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("La tua libreria è vuota", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Aggiungi un PDF, un documento Word, un EPUB o un file di testo e Leggimi te lo leggerà ad alta voce.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DocumentCard(doc: DocumentEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val minutes = (doc.charCount / 900.0).roundToInt().coerceAtLeast(1)
                Text(
                    "${doc.format} · circa ${formatDuration(minutes)} · ${(doc.progress * 100).roundToInt()}% ascoltato",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { doc.progress }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Altro") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Elimina") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** ~900 caratteri al minuto è la velocità tipica di lettura ad alta voce. */
private fun formatDuration(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
