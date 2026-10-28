package app.lector.ui

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import app.lector.ReaderUiState
import app.lector.ReaderViewModel
import app.lector.Screen
import app.lector.core.Sentence
import app.lector.data.LibraryEntry
import app.lector.speech.EngineStatus
import app.lector.speech.VoiceOption
import kotlinx.coroutines.yield

private const val MIN_SPEECH_RATE = 0.50f
private const val MAX_SPEECH_RATE = 4.00f
private const val SPEECH_RATE_STEP = 0.05f
private const val DEFAULT_SENTENCE_PAUSE_MS = 180L
private const val DEFAULT_PARAGRAPH_PAUSE_MS = 550L
private val SLEEP_OPTIONS = listOf(0, 5, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showVoices by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    val readerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.screen) {
        if (state.screen == Screen.READER) {
            readerFocusRequester.requestFocus()
        }
    }

    // Reader page state, lifted so the reading pane and the bottom controls agree.
    var pages by remember(state.document) { mutableStateOf<List<IntRange>>(emptyList()) }
    var scrub by remember(state.document) { mutableStateOf<Float?>(null) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openUri)
    }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::chooseFolder)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Reader has no top bar — its controls live at the bottom (title/Sleep/
            // Voice/Home moved there). Home/Library keep a conventional top bar.
            if (state.screen != Screen.READER) {
                TopAppBar(
                    title = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Lector+",
                                modifier = Modifier.align(Alignment.CenterStart),
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (state.screen == Screen.HOME) {
                                Text(
                                    "What shall I read to you?",
                                    modifier = Modifier.align(Alignment.Center),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    actions = {
                        // Voice selection is available in the Home action row.
                        if (state.screen != Screen.HOME) TextButton(onClick = viewModel::showHome) { Text("Home") }
                    },
                )
            }
        },
        bottomBar = {
            if (state.screen == Screen.READER) {
                ReaderControls(
                    state = state,
                    viewModel = viewModel,
                    pages = pages,
                    scrub = scrub,
                    onScrub = { scrub = it },
                    onScrubCommit = { scrub?.let { v -> viewModel.seekTo(v.toInt()) }; scrub = null },
                    onSleep = { showSleep = true },
                    onVoice = { showVoices = true },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (state.screen) {
            Screen.READER -> state.document?.let { doc ->
                PagedReader(
                    document = doc,
                    state = state,
                    scrub = scrub,
                    pages = pages,
                    onPages = { pages = it },
                    onSentenceTap = viewModel::seekTo,
                    modifier = modifier
                        .focusRequester(readerFocusRequester)
                        .focusable(),
                )
            }
            Screen.LIBRARY -> LibraryScreen(state, { viewModel.openUri(Uri.parse(it.uri)) }, { openFolder.launch(null) }, modifier)
            Screen.HOME -> HomeScreen(
                state = state,
                onListen = { text, selectionStart, selectionEnd ->
                    viewModel.setPastedText(text, selectionStart, selectionEnd)
                },
                onOpenVoices = { showVoices = true },
                onEditorChange = viewModel::updateEditorState,
                onOpenFile = { openFile.launch(arrayOf("*/*")) },
                onOpenFolder = { openFolder.launch(null) },
                onOpenLibrary = viewModel::showLibrary,
                onOpenRecent = { viewModel.openUri(Uri.parse(it.uri)) },
                modifier = modifier,
            )
        }
    }

    if (showVoices) {
        VoiceDialog(state.voices, state.selectedVoiceId, { viewModel.selectVoice(it); showVoices = false }) { showVoices = false }
    }
    if (showSleep) {
        SleepDialog(state.sleepMinutes, { viewModel.setSleepTimer(it); showSleep = false }) { showSleep = false }
    }
    state.importError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImportError,
            confirmButton = { TextButton(onClick = viewModel::dismissImportError) { Text("OK") } },
            title = { Text("Can’t open file") },
            text = { Text(message) },
        )
    }
}

// ── Paged reader ──────────────────────────────────────────────────────────────

@Composable
private fun PagedReader(
    document: app.lector.core.Document,
    state: ReaderUiState,
    scrub: Float?,
    pages: List<IntRange>,
    onPages: (List<IntRange>) -> Unit,
    onSentenceTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val heightPx = with(density) { maxHeight.toPx() }.toInt()
        // Measurement must match how each sentence actually renders, or pages pack
        // too many and a line spills onto the next page. Each sentence Text has
        // horizontal=8dp, vertical=4dp inner padding inside its Surface.
        val textWidthPx = with(density) { (maxWidth - 16.dp).toPx() }.toInt()
        val itemVPadPx = with(density) { 8.dp.toPx() } // 4dp top + 4dp bottom
        val paraGapPx = with(density) { 20.dp.toPx() }
        val gapPx = with(density) { 6.dp.toPx() }

        // Paginate off the frame: measuring a whole book can take a moment, so we
        // yield periodically to keep the UI responsive (same large-doc lesson as
        // the playback fix).
        LaunchedEffect(document, textWidthPx, heightPx) {
            if (textWidthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            onPages(paginate(document.sentences, textWidthPx, heightPx, style, measurer, itemVPadPx, paraGapPx, gapPx))
        }

        val displaySentence = (scrub?.toInt() ?: state.currentSentence)
            .coerceIn(0, document.sentences.lastIndex)
        val pageIndex = pages.indexOfLast { it.first <= displaySentence }.coerceAtLeast(0)
        val range = pages.getOrNull(pageIndex)

        if (range == null) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text("Preparing pages…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // A page normally fits; verticalScroll is a safety net for a single
            // sentence taller than the screen.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                for (i in range) {
                    val sentence = document.sentences[i]
                    val isCurrent = sentence.index == state.currentSentence
                    val bg = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
                    Surface(
                        color = bg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSentenceTap(sentence.index) },
                    ) {
                        Text(
                            text = highlighted(sentence, state, isCurrent),
                            style = style,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(if (sentence.endsParagraph) 20.dp else 6.dp))
                }
            }
        }
    }
}

/**
 * Greedily pack sentences into pages that fit [maxHeightPx]; each page is a
 * sentence-index range. [textWidthPx] is the actual text layout width (page width
 * minus the sentence's inner horizontal padding) and [itemVPadPx] the sentence's
 * inner vertical padding, so the measured height matches what renders — otherwise
 * pages overflow and a line spills onto the next page.
 */
private suspend fun paginate(
    sentences: List<Sentence>,
    textWidthPx: Int,
    maxHeightPx: Int,
    style: TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer,
    itemVPadPx: Float,
    paraGapPx: Float,
    gapPx: Float,
): List<IntRange> {
    if (sentences.isEmpty()) return emptyList()
    val pages = mutableListOf<IntRange>()
    var start = 0
    var height = 0f
    var i = 0
    while (i < sentences.size) {
        val textHeight = measurer.measure(
            text = AnnotatedString(sentences[i].text),
            style = style,
            constraints = Constraints(maxWidth = textWidthPx),
        ).size.height.toFloat()
        val gap = if (sentences[i].endsParagraph) paraGapPx else gapPx
        val itemHeight = textHeight + itemVPadPx // full rendered height of this sentence
        if (height > 0f && height + itemHeight > maxHeightPx) {
            pages.add(start until i) // close the page before this sentence
            start = i
            height = 0f
        }
        height += itemHeight + gap
        i++
        if (i % 120 == 0) yield() // keep the UI thread responsive on big books
    }
    if (start < sentences.size) pages.add(start until sentences.size)
    return pages
}

@Composable
private fun ReaderControls(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    pages: List<IntRange>,
    scrub: Float?,
    onScrub: (Float) -> Unit,
    onScrubCommit: () -> Unit,
    onSleep: () -> Unit,
    onVoice: () -> Unit,
) {
    val context = LocalContext.current
    val adjustments = remember(context) { loadReaderAdjustments(context) }
    val lastIndex = state.document?.sentences?.lastIndex ?: 0
    val displaySentence = (scrub?.toInt() ?: state.currentSentence).coerceIn(0, maxOf(0, lastIndex))
    val pageIndex = pages.indexOfLast { it.first <= displaySentence }.coerceAtLeast(0)

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Title left, adjustments centered, navigation right.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Lector+",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )

                Row(
                    modifier = Modifier.weight(3f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                var storedSpeed by remember { mutableStateOf(adjustments.speed) }
                var sentencePauseMs by remember { mutableStateOf(adjustments.sentencePauseMs) }
                var paragraphPauseMs by remember { mutableStateOf(adjustments.paragraphPauseMs) }

                val prefs = remember(context) {
                    context.getSharedPreferences(READER_PREFS, android.content.Context.MODE_PRIVATE)
                }
                val saveSpeed = {
                    prefs.edit().putFloat(KEY_READER_SPEED, storedSpeed).apply()
                }
                val applyStoredSpeed = {
                    try {
                        val speed = prefs.getFloat(KEY_READER_SPEED, DEFAULT_READER_SPEED)
                        viewModel.setSpeed(speed)
                    } catch (e: Exception) {
                        throw e
                    }
                    Unit
                }
                val saveSentenceAndParagraphPauses = {
                    prefs.edit()
                        .putLong(KEY_SENTENCE_PAUSE_MS, sentencePauseMs)
                        .putLong(KEY_PARAGRAPH_PAUSE_MS, paragraphPauseMs)
                        .apply()
                }
                val saveParagraphPause = {
                    prefs.edit().putLong(KEY_PARAGRAPH_PAUSE_MS, paragraphPauseMs).apply()
                }
                val paragraphMinimum = (sentencePauseMs * 1.5).toLong()

                AdjustmentControl(
                    label = "Speed",
                    value = formatSpeed(storedSpeed),
                    onDecreaseDown = {
                        storedSpeed = (storedSpeed - SPEECH_RATE_STEP)
                            .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
                        saveSpeed()
                    },
                    onDecreaseUp = applyStoredSpeed,
                    onIncreaseDown = {
                        storedSpeed = (storedSpeed + SPEECH_RATE_STEP)
                            .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
                        saveSpeed()
                    },
                    onIncreaseUp = applyStoredSpeed,
                )
                AdjustmentControl(
                    label = "Sentence pause",
                    value = "$sentencePauseMs ms",
                    onDecreaseDown = {
                        sentencePauseMs = (sentencePauseMs - 10L)
                            .coerceIn(MIN_SENTENCE_PAUSE_MS, MAX_SENTENCE_PAUSE_MS)
                        paragraphPauseMs = paragraphPauseMs.coerceAtLeast((sentencePauseMs * 1.5).toLong())
                        saveSentenceAndParagraphPauses()
                    },
                    onDecreaseUp = applyStoredSpeed,
                    onIncreaseDown = {
                        sentencePauseMs = (sentencePauseMs + 10L)
                            .coerceIn(MIN_SENTENCE_PAUSE_MS, MAX_SENTENCE_PAUSE_MS)
                        paragraphPauseMs = paragraphPauseMs.coerceAtLeast((sentencePauseMs * 1.5).toLong())
                        saveSentenceAndParagraphPauses()
                    },
                    onIncreaseUp = applyStoredSpeed,
                )
                AdjustmentControl(
                    label = "Paragraph pause",
                    value = "$paragraphPauseMs ms",
                    onDecreaseDown = {
                        paragraphPauseMs = (paragraphPauseMs - 10L)
                            .coerceIn(paragraphMinimum, MAX_PARAGRAPH_PAUSE_MS)
                        saveParagraphPause()
                    },
                    onDecreaseUp = applyStoredSpeed,
                    onIncreaseDown = {
                        paragraphPauseMs = (paragraphPauseMs + 10L)
                            .coerceIn(paragraphMinimum, MAX_PARAGRAPH_PAUSE_MS)
                        saveParagraphPause()
                    },
                    onIncreaseUp = applyStoredSpeed,
                )

                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                TextButton(
                    onClick = onSleep,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(if (state.sleepMinutes > 0) "Sleep ${state.sleepMinutes}m" else "Sleep")
                }
                if (state.voices.isNotEmpty()) {
                    TextButton(
                        onClick = onVoice,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) { Text("Voice") }
                }
                TextButton(
                    onClick = viewModel::showHome,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("Home") }
                }
            }

            // Book-length scrubber: drag to move through the whole book.
            if (pages.isNotEmpty()) {
                Text(
                    "Page ${pageIndex + 1} of ${pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = (scrub ?: state.currentSentence.toFloat()).coerceIn(0f, maxOf(1f, lastIndex.toFloat())),
                    onValueChange = onScrub,
                    onValueChangeFinished = onScrubCommit,
                    valueRange = 0f..maxOf(1f, lastIndex.toFloat()),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Transport: Back/Next flip pages; Play/Pause toggles playback.
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { pages.getOrNull(pageIndex - 1)?.let { viewModel.seekTo(it.first) } },
                    enabled = pageIndex > 0,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 30.dp),
                ) { Text("◀ Back") }
                Button(
                    onClick = viewModel::playPause,
                    enabled = state.engineReady,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 30.dp),
                ) {
                    Text(
                        when (state.engineStatus) {
                            EngineStatus.INITIALIZING -> "Preparing…"
                            EngineStatus.FAILED -> "No engine"
                            EngineStatus.READY -> if (state.isPlaying) "Pause" else "Listen"
                        },
                    )
                }
                OutlinedButton(
                    onClick = { pages.getOrNull(pageIndex + 1)?.let { viewModel.seekTo(it.first) } },
                    enabled = pageIndex < pages.lastIndex,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 30.dp),
                ) { Text("Next ▶") }
            }
        }
    }
}

// ── Home / Library ────────────────────────────────────────────────────────────

@Composable
private fun HomeScreen(
    state: ReaderUiState,
    onListen: (String, Int, Int) -> Unit,
    onOpenVoices: () -> Unit,
    onEditorChange: (String, Int, Int) -> Unit,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRecent: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.editorText,
                selection = TextRange(
                    state.editorSelectionStart.coerceIn(0, state.editorText.length),
                    state.editorSelectionEnd.coerceIn(0, state.editorText.length),
                ),
            ),
        )
    }
    val clipboard = LocalClipboardManager.current
    val textFieldFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        textFieldFocusRequester.requestFocus()
    }
    fun updateInput(value: TextFieldValue) {
        input = value
        onEditorChange(value.text, value.selection.start, value.selection.end)
    }


    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = ::updateInput,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(textFieldFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            false
                        } else if (
                            event.key != Key.DirectionUp &&
                            event.key != Key.DirectionDown
                        ) {
                            false
                        } else {
                            val text = input.text
                            val caret = input.selection.start.coerceIn(0, text.length)
                            val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0))
                                .let { if (caret == 0) 0 else it + 1 }
                            val lineEndFound = text.indexOf('\n', caret)
                            val lineEnd = if (lineEndFound < 0) text.length else lineEndFound
                            val column = caret - lineStart

                            if (event.key == Key.DirectionUp) {
                                if (lineStart == 0) {
                                    true
                                } else {
                                    val previousEnd = lineStart - 1
                                    val previousStart = text.lastIndexOf(
                                        '\n',
                                        (previousEnd - 1).coerceAtLeast(0),
                                    ).let { if (previousEnd == 0) 0 else it + 1 }
                                    val target = (previousStart + column).coerceAtMost(previousEnd)
                                    updateInput(input.copy(selection = TextRange(target)))
                                    true
                                }
                            } else {
                                if (lineEnd == text.length) {
                                    true
                                } else {
                                    val nextStart = lineEnd + 1
                                    val nextEndFound = text.indexOf('\n', nextStart)
                                    val nextEnd = if (nextEndFound < 0) text.length else nextEndFound
                                    val target = (nextStart + column).coerceAtMost(nextEnd)
                                    updateInput(input.copy(selection = TextRange(target)))
                                    true
                                }
                            }
                        }
                    },
                placeholder = { Text("Paste or type here…") },
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 25,
                singleLine = false,
            )
        }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboard.getText()?.text?.let { text ->
                                    updateInput(
                                        TextFieldValue(
                                            text = text,
                                            selection = TextRange(0),
                                        )
                                    )
                                    textFieldFocusRequester.requestFocus()
                                }
                            }
                        ) {
                            Text("Paste")
                        }
                        Button(
                            onClick = { onListen(input.text, input.selection.start, input.selection.end) },
                            enabled = input.text.isNotBlank()
                        ) {
                            Text("Listen")
                        }
                    }

                    if (state.voices.isNotEmpty()) {
                        OutlinedButton(onClick = onOpenVoices) {
                            Text("Voice")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onOpenFile) {
                            Text("Open file")
                        }
                        OutlinedButton(onClick = onOpenFolder) {
                            Text("Open folder")
                        }
                        if (state.library.isNotEmpty()) {
                            OutlinedButton(onClick = onOpenLibrary) {
                                Text("Library")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LibraryScreen(
    state: ReaderUiState,
    onOpen: (LibraryEntry) -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Library (${state.library.size})", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onAddFolder) { Text("Add folder") }
        }
        state.scanMessage?.let {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.scanning) CircularProgressIndicator(Modifier.height(16.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.library.isEmpty() && !state.scanning) {
            Text(
                "Your library is empty. Tap “Add folder” to scan a folder of books; " +
                    "Lector remembers everything it finds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.library, key = { it.uri }) { entry ->
                    EntryRow(entry) { onOpen(entry) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: LibraryEntry, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            val hint = buildString {
                if (entry.format.isNotBlank()) append(entry.format.uppercase())
                if (entry.sentenceIndex > 0) { if (isNotEmpty()) append(" · "); append("resume") }
            }
            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Dialogs & shared bits ─────────────────────────────────────────────────────

@Composable
private fun VoiceDialog(voices: List<VoiceOption>, selectedId: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Voice") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "default-system") {
                    ChoiceRow("Default (system)", selectedId == null) { onSelect(null) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                // distinctBy + key: Android Voice.name is not unique (e.g. multiple
                // "zh" voices) — duplicate keys crash Compose ("Key zh was already used").
                // SystemTtsEngine already mints unique ids; this is defense in depth.
                items(voices.distinctBy { it.id }, key = { it.id }) { v ->
                    ChoiceRow(v.label, selectedId == v.id) { onSelect(v.id) }
                }
            }
        },
    )
}

@Composable
private fun SleepDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Sleep timer") },
        text = {
            Column {
                SLEEP_OPTIONS.forEach { m -> ChoiceRow(if (m == 0) "Off" else "$m minutes", current == m) { onPick(m) } }
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NoEngineNotice() {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("No speech engine found", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(
                "Lector needs a text-to-speech engine to read aloud. Install one " +
                    "(for example SherpaTTS or your device’s TTS), then reopen Lector.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Install voice data") }
        }
    }
}

private fun highlighted(sentence: Sentence, state: ReaderUiState, isCurrent: Boolean): AnnotatedString {
    val text = sentence.text
    if (!isCurrent || state.wordStart < 0 || state.wordStart >= text.length) return AnnotatedString(text)
    val end = state.wordEnd.coerceIn(state.wordStart + 1, text.length)
    return buildAnnotatedString {
        append(text.substring(0, state.wordStart))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(state.wordStart, end)) }
        append(text.substring(end))
    }
}

@Composable
private fun AdjustmentControl(
    label: String,
    value: String,
    onDecreaseDown: () -> Unit,
    onDecreaseUp: () -> Unit,
    onIncreaseDown: () -> Unit,
    onIncreaseUp: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        PressReleaseAdjustmentButton(
            text = "−",
            onDown = onDecreaseDown,
            onUp = onDecreaseUp,
        )
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                value,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        PressReleaseAdjustmentButton(
            text = "+",
            onDown = onIncreaseDown,
            onUp = onIncreaseUp,
        )
    }
}

@Composable
private fun PressReleaseAdjustmentButton(
    text: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(50),
        color = if (pressed) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .heightIn(min = 30.dp)
            .pointerInput(onDown, onUp) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    try {
                        onDown()
                    } catch (e: Exception) {
                        throw e
                    }

                    var releaseResult = "not returned"
                    try {
                        releaseResult = if (waitForUpOrCancellation() != null) "released" else "cancelled"
                    } catch (e: Exception) {
                        throw e
                    } finally {
                        pressed = false
                        try {
                            onUp()
                        } catch (e: Exception) {
                            throw e
                        }
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val READER_PREFS = "lector_prefs"
private const val KEY_READER_SPEED = "reader_speed"
private const val KEY_SENTENCE_PAUSE_MS = "reader_sentence_pause_ms"
private const val KEY_PARAGRAPH_PAUSE_MS = "reader_paragraph_pause_ms"
private const val DEFAULT_READER_SPEED = 1.0f
private const val MIN_SENTENCE_PAUSE_MS = 0L
private const val MAX_SENTENCE_PAUSE_MS = 600L
private const val MAX_PARAGRAPH_PAUSE_MS = 950L

private data class ReaderAdjustments(
    val speed: Float,
    val sentencePauseMs: Long,
    val paragraphPauseMs: Long,
)

private fun loadReaderAdjustments(context: android.content.Context): ReaderAdjustments {
    val prefs = context.getSharedPreferences(READER_PREFS, android.content.Context.MODE_PRIVATE)
    val speed = runCatching { prefs.getFloat(KEY_READER_SPEED, DEFAULT_READER_SPEED) }.getOrDefault(DEFAULT_READER_SPEED).takeIf { it.isFinite() && it in MIN_SPEECH_RATE..MAX_SPEECH_RATE } ?: DEFAULT_READER_SPEED
    val sentencePauseMs = runCatching { prefs.getLong(KEY_SENTENCE_PAUSE_MS, DEFAULT_SENTENCE_PAUSE_MS) }.getOrDefault(DEFAULT_SENTENCE_PAUSE_MS).coerceIn(MIN_SENTENCE_PAUSE_MS, MAX_SENTENCE_PAUSE_MS)
    val paragraphMinimum = (sentencePauseMs * 1.5).toLong()
    val storedParagraph = runCatching { prefs.getLong(KEY_PARAGRAPH_PAUSE_MS, DEFAULT_PARAGRAPH_PAUSE_MS) }.getOrDefault(DEFAULT_PARAGRAPH_PAUSE_MS)
    val paragraphPauseMs = storedParagraph.coerceIn(paragraphMinimum, MAX_PARAGRAPH_PAUSE_MS)
    prefs.edit().putFloat(KEY_READER_SPEED, speed).putLong(KEY_SENTENCE_PAUSE_MS, sentencePauseMs).putLong(KEY_PARAGRAPH_PAUSE_MS, paragraphPauseMs).apply()
    return ReaderAdjustments(speed, sentencePauseMs, paragraphPauseMs)
}

private fun formatSpeed(speed: Float): String =
    String.format(java.util.Locale.ROOT, "%.2f×", speed)
