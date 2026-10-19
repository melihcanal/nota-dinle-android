package com.notadinle.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Paper = Color(0xFFF5F7F5)
private val Ink = Color(0xFF17231E)
private val Muted = Color(0xFF6D7A72)
private val Green = Color(0xFF176B55)
private val PaleGreen = Color(0xFFE5F3EB)
private val Line = Color(0xFFE4EAE6)
private val Coral = Color(0xFFE68165)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green,
                    onPrimary = Color.White,
                    secondary = Coral,
                    background = Paper,
                    surface = Color.White,
                    onSurface = Ink,
                ),
            ) {
                NotaDinleScreen()
            }
        }
    }
}

internal enum class Instrument(val label: String) {
    Piano("Akustik piyano"), Guitar("Akustik gitar")
}

private data class SelectedScore(
    val name: String,
    val mimeType: String?,
    val uri: Uri,
)

@Composable
private fun NotaDinleScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedScore by remember { mutableStateOf<SelectedScore?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isPreviewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var recognizedNotes by remember { mutableStateOf<List<DetectedNote>>(emptyList()) }
    var recognitionError by remember { mutableStateOf<String?>(null) }
    var recognitionRequestId by remember { mutableIntStateOf(0) }
    var isRecognizing by remember { mutableStateOf(false) }
    var selectedInstrument by remember { mutableStateOf(Instrument.Piano) }
    var tempo by remember { mutableFloatStateOf(92f) }
    var isPlaying by remember { mutableStateOf(false) }
    var activeNotes by remember { mutableStateOf<List<DetectedNote>?>(null) }
    var showIntro by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            isPlaying = false
            activeNotes = null
            recognitionRequestId++
            isRecognizing = false
            recognizedNotes = emptyList()
            previewBitmap = null
            previewError = null
            recognitionError = null
            isPreviewLoading = true
            selectedScore = SelectedScore(
                name = resolveDisplayName(context, uri),
                mimeType = context.contentResolver.getType(uri),
                uri = uri,
            )
            inputError = null
        }
    }
    val camera = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        val uri = pendingCameraUri
        if (captured && uri != null) {
            isPlaying = false
            activeNotes = null
            recognitionRequestId++
            isRecognizing = false
            recognizedNotes = emptyList()
            previewBitmap = null
            previewError = null
            recognitionError = null
            isPreviewLoading = true
            selectedScore = SelectedScore(
                name = "Kamera fotoğrafı",
                mimeType = "image/jpeg",
                uri = uri,
            )
            inputError = null
        } else {
            pendingCameraFile?.delete()
        }
        pendingCameraUri = null
        pendingCameraFile = null
    }
    val openCamera: () -> Unit = {
        runCatching {
            val directory = File(context.cacheDir, "scores").apply { mkdirs() }
            val file = File.createTempFile("nota_", ".jpg", directory)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraFile = file
            pendingCameraUri = uri
            camera.launch(uri)
        }.onFailure { error ->
            pendingCameraFile?.delete()
            pendingCameraFile = null
            pendingCameraUri = null
            inputError = error.message ?: "Kamera açılamadı. Bir görsel dosyası seçmeyi dene."
        }
    }

    LaunchedEffect(selectedScore) {
        recognitionRequestId++
        isRecognizing = false
        val score = selectedScore
        previewBitmap = null
        previewError = null
        recognitionError = null
        recognizedNotes = emptyList()
        if (score == null) return@LaunchedEffect

        isPreviewLoading = true
        try {
            previewBitmap = withContext(Dispatchers.IO) {
                ScoreImageLoader.load(context, score.uri, score.mimeType)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            previewError = error.message ?: "Bu dosya açılamadı. Görsel veya PDF seçmeyi dene."
        } finally {
            isPreviewLoading = false
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            try {
                val notes = activeNotes
                if (notes.isNullOrEmpty()) {
                    TonePlayer.playSample(context.applicationContext, selectedInstrument, tempo.toInt())
                } else {
                    TonePlayer.playNotes(context.applicationContext, notes, selectedInstrument, tempo.toInt())
                }
            } finally {
                isPlaying = false
            }
        }
    }

    val recognizeScore: () -> Unit = {
        val requestId = recognitionRequestId + 1
        recognitionRequestId = requestId
        isRecognizing = true
        recognitionError = null
        coroutineScope.launch {
            val score = selectedScore
            try {
                if (score == null) return@launch
                val bitmap = previewBitmap ?: withContext(Dispatchers.IO) {
                    ScoreImageLoader.load(context, score.uri, score.mimeType)
                }
                val notes = withContext(Dispatchers.Default) {
                    StaffNoteRecognizer.recognize(bitmap)
                }
                if (requestId == recognitionRequestId && selectedScore == score) {
                    previewBitmap = bitmap
                    previewError = null
                    recognizedNotes = notes
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestId == recognitionRequestId) {
                    recognitionError = error.message ?: "Notalar bu görselden okunamadı. Daha net bir fotoğraf dene."
                }
            } finally {
                if (requestId == recognitionRequestId) isRecognizing = false
            }
        }
    }

    val currentStep = when {
        recognizedNotes.isNotEmpty() -> 3
        selectedScore != null -> 2
        else -> 1
    }

    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            TopBar(onInfo = { showIntro = true })
            Spacer(Modifier.height(30.dp))

            Text(
                text = "KÂĞITTAN MÜZİĞE",
                color = Green,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.7.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Notanı hemen\ndinle.",
                color = Ink,
                fontSize = 37.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1.1).sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Bir nota sayfası ekle; akustik piyano veya gitarla çal.",
                color = Muted,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                modifier = Modifier.fillMaxWidth(0.94f),
            )

            Spacer(Modifier.height(20.dp))
            WorkflowSteps(currentStep)
            Spacer(Modifier.height(16.dp))
            ImportCard(
                onCamera = openCamera,
                onPickFile = { filePicker.launch(arrayOf("image/*", "application/pdf")) },
            )
            inputError?.let { error ->
                Text(error, color = Color(0xFF9A4637), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            }

            AnimatedVisibility(visible = selectedScore != null) {
                selectedScore?.let { score ->
                    Column {
                        Spacer(Modifier.height(14.dp))
                        SelectedScoreCard(
                            score = score,
                            preview = previewBitmap,
                            isPreviewLoading = isPreviewLoading,
                            error = recognitionError ?: previewError,
                            notes = recognizedNotes,
                            isRecognizing = isRecognizing,
                            isPlaying = isPlaying && activeNotes != null,
                            instrument = selectedInstrument,
                            onRemove = {
                                isPlaying = false
                                activeNotes = null
                                recognitionRequestId++
                                isRecognizing = false
                                selectedScore = null
                            },
                            onRecognize = recognizeScore,
                            onPlay = {
                                if (isPlaying) {
                                    isPlaying = false
                                } else {
                                    activeNotes = recognizedNotes
                                    isPlaying = true
                                }
                            },
                            onAdjustPitch = { index, delta ->
                                recognizedNotes = recognizedNotes.mapIndexed { noteIndex, note ->
                                    if (noteIndex == index) {
                                        val midi = (note.midi + delta).coerceIn(36, 96)
                                        note.copy(midi = midi, name = midiNoteName(midi))
                                    } else note
                                }
                            },
                            onAdjustDuration = { index, direction ->
                                recognizedNotes = recognizedNotes.mapIndexed { noteIndex, note ->
                                    if (noteIndex == index) {
                                        note.copy(durationBeats = adjustNoteDuration(note.durationBeats, direction))
                                    } else note
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
            SectionHeading(
                eyebrow = "ÇALMA AYARLARI",
                title = "Sesini ve tempoyu seç",
            )
            Spacer(Modifier.height(14.dp))
            SampleCard(
                selectedInstrument = selectedInstrument,
                onSelectInstrument = { selectedInstrument = it },
                tempo = tempo,
                onTempoChange = { tempo = it },
                isPlaying = isPlaying && activeNotes == null,
                onPlay = {
                    if (isPlaying) {
                        isPlaying = false
                    } else {
                        activeNotes = null
                        isPlaying = true
                    }
                },
            )

            Spacer(Modifier.height(22.dp))
            Text(
                text = "NotaDinle  ·  Akustik piyano ve gitar",
                color = Color(0xFF97A19A),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        if (showIntro) {
            IntroDialog(onDismiss = { showIntro = false })
        }
    }
}
@Composable
private fun WorkflowSteps(currentStep: Int) {
    val labels = listOf("Sayfa", "Tanı", "Dinle")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val step = index + 1
            val isCurrent = step == currentStep
            val isComplete = step < currentStep
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isCurrent) Green else if (isComplete) PaleGreen else Color.White)
                    .border(
                        width = if (isCurrent) 0.dp else 1.dp,
                        color = if (isComplete) PaleGreen else Line,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (isCurrent) Color.White.copy(alpha = 0.18f) else Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isComplete) "✓" else step.toString(),
                        color = if (isCurrent) Color.White else Green,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    label,
                    color = if (isCurrent) Color.White else Green,
                    fontSize = 10.sp,
                    fontWeight = if (isCurrent || isComplete) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TopBar(onInfo: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(CircleShape).background(Green),
            contentAlignment = Alignment.Center,
        ) {
            Text("♫", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("NotaDinle", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Nota sayfasından sese", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Line, CircleShape)
                .clickable(onClick = onInfo),
            contentAlignment = Alignment.Center,
        ) {
            Text("i", color = Green, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ImportCard(onCamera: () -> Unit, onPickFile: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(PaleGreen),
                    contentAlignment = Alignment.Center,
                ) { MusicMark(Modifier.size(23.dp), Green) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Nota sayfanı ekle", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Basılı nota • PDF veya fotoğraf", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = IconKind.Camera,
                    label = "Kamerayla tara",
                    primary = true,
                    onClick = onCamera,
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = IconKind.Folder,
                    label = "PDF / görsel seç",
                    primary = false,
                    onClick = onPickFile,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    icon: IconKind,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Green else PaleGreen,
            contentColor = if (primary) Color.White else Green,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
    ) {
        LineIcon(icon, Modifier.size(18.dp), if (primary) Color.White else Green)
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelectedScoreCard(
    score: SelectedScore,
    preview: Bitmap?,
    isPreviewLoading: Boolean,
    error: String?,
    notes: List<DetectedNote>,
    isRecognizing: Boolean,
    isPlaying: Boolean,
    instrument: Instrument,
    onRemove: () -> Unit,
    onRecognize: () -> Unit,
    onPlay: () -> Unit,
    onAdjustPitch: (Int, Int) -> Unit,
    onAdjustDuration: (Int, Int) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFCF5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF2EEE3)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview != null) {
                        androidx.compose.foundation.Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "Seçilen nota sayfasının önizlemesi",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(if (score.mimeType == "application/pdf" || score.name.endsWith(".pdf", true)) "PDF" else "♫", color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Green))
                        Spacer(Modifier.width(6.dp))
                        Text("DOSYA EKLENDİ", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(score.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = "Kaldır",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRemove).padding(8.dp),
                )
            }
            if (preview != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Nota sayfası önizlemesi",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp)).background(Color.White),
                )
            } else if (isPreviewLoading) {
                Spacer(Modifier.height(10.dp))
                Text("Sayfa hazırlanıyor…", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(13.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFECE7DA)))
            Spacer(Modifier.height(12.dp))

            if (notes.isEmpty()) {
                Text(
                    text = "Önce notaları tanı. İlk sürüm tek, yatay sol anahtarı portesi ve tek sesli melodi içindir.",
                    color = Color(0xFF806F62),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFF9A4637), fontSize = 12.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(11.dp))
                Button(
                    onClick = onRecognize,
                    enabled = !isRecognizing && !isPreviewLoading,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) {
                    Text(
                        when {
                            isRecognizing -> "Notalar okunuyor…"
                            isPreviewLoading -> "Görsel yükleniyor…"
                            else -> "Notaları tanı"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text("${notes.size} NOTA TANINDI · SÜRELERİ KONTROL ET", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(9.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    notes.forEachIndexed { index, note ->
                        Column(
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(horizontal = 7.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(note.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(noteDurationLabel(note.durationBeats), color = Green, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Text("Perde", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                PitchAdjustButton("−") { onAdjustPitch(index, -1) }
                                PitchAdjustButton("+") { onAdjustPitch(index, 1) }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text("Süre", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                PitchAdjustButton("−") { onAdjustDuration(index, -1) }
                                PitchAdjustButton("+") { onAdjustDuration(index, 1) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text("Perde ve süreyi kendi bölümündeki − / + düğmeleriyle değiştirebilirsin.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = Color(0xFF9A4637), fontSize = 12.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(11.dp))
                Button(
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
                ) {
                    Text(if (isPlaying) "Durdur" else "${notes.size} notayı ${instrument.label.lowercase()} ile çal", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onRecognize,
                    enabled = !isRecognizing,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PaleGreen, contentColor = Green),
                ) {
                    Text(if (isRecognizing) "Yeniden okunuyor…" else "Görseli yeniden tara", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PitchAdjustButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).background(PaleGreen).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Green, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable
private fun SectionHeading(eyebrow: String, title: String) {
    Column {
        Text(eyebrow, color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Spacer(Modifier.height(5.dp))
        Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    }
}

@Composable
private fun SampleCard(
    selectedInstrument: Instrument,
    onSelectInstrument: (Instrument) -> Unit,
    tempo: Float,
    onTempoChange: (Float) -> Unit,
    isPlaying: Boolean,
    onPlay: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Örnek melodi", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Enstrüman sesini burada deneyebilirsin", color = Muted, fontSize = 12.sp)
                }
                Box(Modifier.clip(CircleShape).background(Color(0xFFFFF0E8)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("ÖRNEK", color = Color(0xFFB66A51), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
            }
            Text("ENSTRÜMAN", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0xFFF2F4EF)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InstrumentOption("Akustik piyano", selectedInstrument == Instrument.Piano, Modifier.weight(1f)) { onSelectInstrument(Instrument.Piano) }
                InstrumentOption("Akustik gitar", selectedInstrument == Instrument.Guitar, Modifier.weight(1f)) { onSelectInstrument(Instrument.Guitar) }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tempo", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("${tempo.toInt()} BPM", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = tempo,
                onValueChange = onTempoChange,
                valueRange = 60f..140f,
                modifier = Modifier.height(36.dp),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onPlay,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) {
                LineIcon(if (isPlaying) IconKind.Pause else IconKind.Play, Modifier.size(17.dp), Color.White)
                Spacer(Modifier.width(8.dp))
                Text(if (isPlaying) "Örnek sesi durdur" else "Örnek sesi dinle", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun InstrumentOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Ink else Muted, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun HowItWorks() {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PaleGreen).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("♪", color = Green, fontSize = 23.sp, modifier = Modifier.padding(end = 12.dp))
        Column {
            Text("Üç adımda dinle", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Sayfayı ekle  ·  Notayı gözden geçir  ·  Çal", color = Color(0xFF5D7469), fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun IntroDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(25.dp), color = Paper) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(PaleGreen), contentAlignment = Alignment.Center) {
                    Text("♫", color = Green, fontSize = 24.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text("NotaDinle nasıl çalışacak?", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Basılı nota sayfasını çek veya PDF/görsel seç. Uygulama tek bir sol anahtarı portedeki notaları tanır; sen kontrol edip piyano ya da gitarla dinlersin.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                    Text("Anladım", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private enum class IconKind { Camera, Folder, Play, Pause }

@Composable
private fun MusicMark(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        drawCircle(color, radius = 3.5f * sx, center = androidx.compose.ui.geometry.Offset(7f * sx, 17f * sy))
        drawLine(color, androidx.compose.ui.geometry.Offset(10.5f * sx, 17f * sy), androidx.compose.ui.geometry.Offset(10.5f * sx, 4f * sy), strokeWidth = 2f * sx, cap = StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(10.5f * sx, 4f * sy), androidx.compose.ui.geometry.Offset(18f * sx, 6f * sy), strokeWidth = 2f * sx, cap = StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(18f * sx, 6f * sy), androidx.compose.ui.geometry.Offset(18f * sx, 14f * sy), strokeWidth = 2f * sx, cap = StrokeCap.Round)
        drawCircle(color, radius = 3.5f * sx, center = androidx.compose.ui.geometry.Offset(14.5f * sx, 14f * sy))
    }
}

@Composable
private fun LineIcon(kind: IconKind, modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 1.9f * sx, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        when (kind) {
            IconKind.Camera -> {
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(2f * sx, 7f * sy), size = androidx.compose.ui.geometry.Size(20f * sx, 14f * sy), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * sx), style = stroke)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(7f * sx, 4f * sy), size = androidx.compose.ui.geometry.Size(6f * sx, 4f * sy), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * sx))
                drawCircle(color, radius = 4f * sx, center = androidx.compose.ui.geometry.Offset(12f * sx, 14f * sy), style = stroke)
            }
            IconKind.Folder -> {
                val path = Path().apply {
                    moveTo(2f * sx, 6f * sy); lineTo(9f * sx, 6f * sy); lineTo(11f * sx, 8.5f * sy)
                    lineTo(22f * sx, 8.5f * sy); lineTo(20f * sx, 19f * sy); lineTo(3f * sx, 19f * sy); close()
                }
                drawPath(path, color, style = stroke)
            }
            IconKind.Play -> {
                val path = Path().apply {
                    moveTo(8f * sx, 4f * sy); lineTo(20f * sx, 12f * sy); lineTo(8f * sx, 20f * sy); close()
                }
                drawPath(path, color)
            }
            IconKind.Pause -> {
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(6f * sx, 4f * sy), size = androidx.compose.ui.geometry.Size(4f * sx, 16f * sy), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * sx))
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(14f * sx, 4f * sy), size = androidx.compose.ui.geometry.Size(4f * sx, 16f * sy), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * sx))
            }
        }
    }
}

@Composable
private fun StaffPreview(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val startX = size.width * 0.13f
            val endX = size.width * 0.93f
            val topY = size.height * 0.18f
            val gap = size.height * 0.15f
            val staffColor = Color(0xFFBCC6BB)
            repeat(5) { index ->
                val y = topY + gap * index
                drawLine(staffColor, androidx.compose.ui.geometry.Offset(startX, y), androidx.compose.ui.geometry.Offset(endX, y), strokeWidth = 1.2.dp.toPx())
            }

            val notePositions = listOf(0.34f, 0.50f, 0.66f, 0.82f)
            val noteHeights = listOf(0.76f, 0.60f, 0.44f, 0.28f)
            notePositions.forEachIndexed { index, xRatio ->
                val x = size.width * xRatio
                val y = size.height * noteHeights[index]
                val noteColor = if (index == 3) Coral else Green
                drawOval(noteColor, topLeft = androidx.compose.ui.geometry.Offset(x - 5.dp.toPx(), y - 3.5.dp.toPx()), size = androidx.compose.ui.geometry.Size(10.dp.toPx(), 7.dp.toPx()))
                drawLine(noteColor, androidx.compose.ui.geometry.Offset(x + 4.dp.toPx(), y), androidx.compose.ui.geometry.Offset(x + 4.dp.toPx(), y - 23.dp.toPx()), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("𝄞", color = Green, fontSize = 43.sp, fontWeight = FontWeight.Normal, modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp))
    }
}

private fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index)
        }
    }
    return "Nota sayfası"
}
