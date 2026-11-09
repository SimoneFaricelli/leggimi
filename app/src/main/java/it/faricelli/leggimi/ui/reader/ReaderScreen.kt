package it.faricelli.leggimi.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.faricelli.leggimi.data.text.Block
import it.faricelli.leggimi.data.text.Segment
import it.faricelli.leggimi.player.PlayerState
import it.faricelli.leggimi.player.SleepTimer
import it.faricelli.leggimi.ui.appContainer
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    docId: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onPickVoice: () -> Unit,
    onPickModel: () -> Unit,
) {
    val container = appContainer()
    val vm: ReaderViewModel = viewModel(key = docId) { ReaderViewModel(container, docId) }
    val state by vm.player.collectAsStateWithLifecycle()
    val entity by vm.entity.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()

    val doc = state.document?.takeIf { it.entity.id == docId }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }

    val currentSegment: Segment? = doc?.segmented?.segments?.getOrNull(state.currentSegment)

    // Chi scorre a mano smette di seguire la lettura.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(currentSegment?.index, follow, doc != null) {
        val seg = currentSegment ?: return@LaunchedEffect
        if (!initialScrollDone) {
            listState.scrollToItem(seg.startBlock)
            initialScrollDone = true
            return@LaunchedEffect
        }
        if (!follow) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (visible.size < 3 || seg.startBlock !in visible.first()..visible[visible.size - 2]) {
            listState.animateScrollToItem(seg.startBlock)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.parsed?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                },
                actions = {
                    if (doc?.segmented?.chapters?.isNotEmpty() == true) {
                        IconButton(onClick = { showToc = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Indice") }
                    }
                    IconButton(onClick = { showVoice = true }) { Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Voce") }
                },
            )
        },
        bottomBar = {
            if (doc != null) {
                PlayerBar(
                    state = state,
                    total = doc.segmented.segments.size,
                    chapterTitle = doc.segmented.chapters.lastOrNull { it.index == currentSegment?.chapter }?.title,
                    onToggle = vm::togglePlay,
                    onNext = vm::next,
                    onPrevious = vm::previous,
                    onSpeed = vm::setSpeed,
                    onSleep = vm::setSleepTimer,
                    onRetry = vm::retry,
                    onSettings = onSettings,
                )
            }
        },
        floatingActionButton = {
            if (!follow && doc != null) {
                SmallFloatingActionButton(onClick = {
                    follow = true
                    currentSegment?.let { scope.launch { listState.animateScrollToItem(it.startBlock) } }
                }) { Icon(Icons.Filled.MyLocation, contentDescription = "Segui la lettura") }
            }
        },
    ) { padding ->
        when {
            found == false -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Documento non trovato.")
            }
            doc == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            ) {
                itemsIndexed(doc.blocks(), key = { i, _ -> i }) { i, block ->
                    BlockText(
                        block = block,
                        highlight = currentSegment?.rangeIn(i, block.text.length),
                        onClick = { follow = true; vm.moveToBlock(i) },
                    )
                }
            }
        }
    }

    if (showToc && doc != null) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text("Indice", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(doc.segmented.chapters) { _, ch ->
                    val active = currentSegment?.chapter == ch.index
                    ListItem(
                        headlineContent = {
                            Text(ch.title, fontWeight = if (active) FontWeight.Bold else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.clickable {
                            showToc = false
                            follow = true
                            vm.moveToBlock(ch.block)
                            scope.launch { listState.scrollToItem(ch.block) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showVoice) {
        ModalBottomSheet(onDismissRequest = { showVoice = false }) {
            val e = entity
            val s = settings
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Voce per questo documento", style = MaterialTheme.typography.titleLarge)
                val voice = e?.voiceName ?: s?.voiceName ?: "nessuna"
                val model = e?.modelName ?: s?.modelName ?: "—"
                Text("Voce: $voice" + if (e?.voiceId == null) " (predefinita)" else "")
                Text("Modello: $model" + if (e?.modelId == null) " (predefinito)" else "")
                Text(
                    "Cambiare voce o modello rigenera l'audio da qui in avanti e consuma crediti ElevenLabs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showVoice = false; onPickVoice() }) { Text("Cambia voce") }
                    OutlinedButton(onClick = { showVoice = false; onPickModel() }) { Text("Cambia modello") }
                }
                if (e?.voiceId != null || e?.modelId != null) {
                    TextButton(onClick = { vm.resetVoiceToDefault(); showVoice = false }) { Text("Usa le impostazioni predefinite") }
                }
            }
        }
    }
}

private fun it.faricelli.leggimi.data.library.LoadedDocument.blocks(): List<Block> = parsed.blocks

@Composable
private fun BlockText(block: Block, highlight: IntRange?, onClick: () -> Unit) {
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val text: AnnotatedString = remember(block.text, highlight, highlightColor) {
        buildAnnotatedString {
            append(block.text)
            if (highlight != null) {
                addStyle(SpanStyle(background = highlightColor), highlight.first, highlight.last + 1)
            }
        }
    }
    val style = when (block.heading) {
        0 -> MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15f)
        1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    }
    val top = if (block.heading in 1..2) 24.dp else 6.dp
    Text(
        text = text,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = top, bottom = 6.dp),
    )
}

@Composable
private fun PlayerBar(
    state: PlayerState,
    total: Int,
    chapterTitle: String?,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (Int?) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            state.error?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (state.errorNeedsSettings) TextButton(onClick = onSettings) { Text("Impostazioni") }
                            TextButton(onClick = onRetry) { Text("Riprova") }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chapterTitle ?: "Blocco ${state.currentSegment + 1} di $total",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${state.currentSegment + 1}/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (total <= 1) 0f else state.currentSegment.toFloat() / (total - 1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SpeedButton(state.speed, onSpeed)
                IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Precedente") }
                FilledIconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    when {
                        state.isLoading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.White)
                        state.isPlaying -> Icon(Icons.Filled.Pause, contentDescription = "Pausa", Modifier.size(36.dp))
                        else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Leggi", Modifier.size(36.dp))
                    }
                }
                IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, contentDescription = "Successivo") }
                SleepButton(state, onSleep)
            }
        }
    }
}

@Composable
private fun SpeedButton(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(formatSpeed(speed)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(s), fontWeight = if (s == speed) FontWeight.Bold else null) },
                    onClick = { open = false; onSpeed(s) },
                )
            }
        }
    }
}

private fun formatSpeed(s: Float): String {
    val pattern = when {
        s % 1f == 0f -> "%.0f"
        (s * 10f) % 1f == 0f -> "%.1f"
        else -> "%.2f"
    }
    return pattern.format(java.util.Locale.ITALY, s) + "×"
}

@Composable
private fun SleepButton(state: PlayerState, onSleep: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Timer, contentDescription = "Timer di spegnimento", Modifier.size(20.dp))
            when (state.sleepTimer) {
                is SleepTimer.At -> {
                    val sec = state.sleepRemainingMs / 1000
                    Text(" %d:%02d".format(sec / 60, sec % 60), style = MaterialTheme.typography.labelMedium)
                }
                SleepTimer.EndOfChapter -> Text(" cap.", style = MaterialTheme.typography.labelMedium)
                SleepTimer.Off -> Unit
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(15, 30, 45, 60).forEach { m ->
                DropdownMenuItem(text = { Text("$m minuti") }, onClick = { open = false; onSleep(m) })
            }
            DropdownMenuItem(text = { Text("Fine del capitolo") }, onClick = { open = false; onSleep(-1) })
            if (state.sleepTimer != SleepTimer.Off) {
                DropdownMenuItem(text = { Text("Disattiva timer") }, onClick = { open = false; onSleep(null) })
            }
        }
    }
}
