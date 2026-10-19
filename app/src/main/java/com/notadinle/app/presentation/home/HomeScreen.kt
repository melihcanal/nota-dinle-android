package com.notadinle.app.presentation.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notadinle.omr.DetectedNote
import com.notadinle.omr.noteDurationLabel
import com.notadinle.app.domain.model.Instrument
import com.notadinle.app.domain.model.ScoreDocument

private val Paper = Color(0xFFF5F7F5)
private val Ink = Color(0xFF17231E)
private val Muted = Color(0xFF6D7A72)
private val Green = Color(0xFF176B55)
private val PaleGreen = Color(0xFFE5F3EB)
private val Line = Color(0xFFE4EAE6)
private val Coral = Color(0xFFE68165)

@Composable
fun NotaDinleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green,
            onPrimary = Color.White,
            secondary = Coral,
            background = Paper,
            surface = Color.White,
            onSurface = Ink,
            error = Color(0xFFAA463A),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onCamera: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveDocument: () -> Unit,
    onPageChange: (Int) -> Unit,
    onRecognize: () -> Unit,
    onInstrumentChange: (Instrument) -> Unit,
    onTempoChange: (Int) -> Unit,
    onPlay: () -> Unit,
    onPlaySample: () -> Unit,
    onAdjustPitch: (Int, Int) -> Unit,
    onAdjustDuration: (Int, Int) -> Unit,
) {
    var editingNoteIndex by remember { mutableIntStateOf(-1) }
    val editingNote = state.notes.getOrNull(editingNoteIndex)

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 30.dp),
        ) {
            Header()
            Spacer(Modifier.height(25.dp))

            Text("Bir nota sayfası seç", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("Görüntü telefonda işlenir; dosyan dışarı gönderilmez.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(15.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SourceTile(
                    modifier = Modifier.weight(1f),
                    title = "Fotoğraf çek",
                    subtitle = "Notayı kamerayla al",
                    icon = SourceIcon.Camera,
                    onClick = onCamera,
                )
                SourceTile(
                    modifier = Modifier.weight(1f),
                    title = "Dosya seç",
                    subtitle = "Görsel veya PDF",
                    icon = SourceIcon.Document,
                    onClick = onPickFile,
                )
            }

            state.errorMessage?.let { message ->
                Spacer(Modifier.height(13.dp))
                Text(
                    text = message,
                    color = Color(0xFF9A4036),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFECE8))
                        .padding(14.dp),
                )
            }

            state.document?.let { document ->
                Spacer(Modifier.height(18.dp))
                DocumentCard(
                    document = document,
                    state = state,
                    onRemove = onRemoveDocument,
                    onPageChange = onPageChange,
                    onRecognize = onRecognize,
                )
            }

            if (state.notes.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                NotesCard(state.notes, onNoteClick = { editingNoteIndex = it })
                Spacer(Modifier.height(18.dp))
                PlaybackCard(
                    instrument = state.instrument,
                    tempo = state.tempo,
                    isPlaying = state.isPlaying,
                    onInstrumentChange = onInstrumentChange,
                    onTempoChange = onTempoChange,
                    onPlay = onPlay,
                    onPlaySample = onPlaySample,
                )
            }
        }
    }

    if (editingNote != null) {
        ModalBottomSheet(onDismissRequest = { editingNoteIndex = -1 }, containerColor = Paper) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
                Text("Nota düzelt", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Yanlış okunan perdeyi veya süreyi değiştirebilirsin.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Perde", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onAdjustPitch(editingNoteIndex, -1) }) { Text("− yarım ses") }
                        Text(editingNote.name, color = Green, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { onAdjustPitch(editingNoteIndex, 1) }) { Text("+ yarım ses") }
                    }
                }
                HorizontalDivider(color = Line)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Süre", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onAdjustDuration(editingNoteIndex, -1) }) { Text("−") }
                        Text(noteDurationLabel(editingNote.durationBeats), color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { onAdjustDuration(editingNoteIndex, 1) }) { Text("+") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { editingNoteIndex = -1 }, modifier = Modifier.fillMaxWidth()) { Text("Tamam") }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(46.dp).clip(CircleShape).background(Green),
            contentAlignment = Alignment.Center,
        ) {
            MusicMark(Modifier.size(25.dp), Color.White)
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text("NotaDinle", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Nota sayfasından sese", color = Muted, fontSize = 12.sp)
        }
    }
}

private enum class SourceIcon { Camera, Document }

@Composable
private fun SourceTile(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: SourceIcon,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.height(116.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(PaleGreen), contentAlignment = Alignment.Center) {
                when (icon) {
                    SourceIcon.Camera -> CameraMark(Modifier.size(21.dp), Green)
                    SourceIcon.Document -> DocumentMark(Modifier.size(21.dp), Green)
                }
            }
            Column {
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DocumentCard(
    document: ScoreDocument,
    state: HomeUiState,
    onRemove: () -> Unit,
    onPageChange: (Int) -> Unit,
    onRecognize: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(document.displayName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (state.pageCount > 1) "Sayfa ${state.pageIndex + 1} / ${state.pageCount}" else "Nota sayfası", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = onRemove) { Text("Kaldır", color = Muted) }
            }
            Spacer(Modifier.height(8.dp))
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Green)
                }
                state.preview != null -> Image(
                    bitmap = state.preview.asImageBitmap(),
                    contentDescription = "Seçilen nota sayfasının önizlemesi",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).background(Paper),
                )
            }
            if (state.pageCount > 1 && !state.isLoading) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onPageChange(state.pageIndex - 1) }, enabled = state.pageIndex > 0) { Text("‹ Önceki") }
                    Text("${state.pageIndex + 1} / ${state.pageCount}", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { onPageChange(state.pageIndex + 1) }, enabled = state.pageIndex < state.pageCount - 1) { Text("Sonraki ›") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRecognize,
                enabled = !state.isLoading && !state.isRecognizing && state.preview != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isRecognizing) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                    Text("Notalar taranıyor…")
                } else Text("Notaları tanı")
            }
        }
    }
}

@Composable
private fun NotesCard(notes: List<DetectedNote>, onNoteClick: (Int) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text("Okunan notalar", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("${notes.size} nota · düzenlemek için birine dokun", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notes.forEachIndexed { index, note ->
                    NoteChip(note, index, onClick = { onNoteClick(index) })
                }
            }
        }
    }
}

@Composable
private fun NoteChip(note: DetectedNote, index: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PaleGreen)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Nota ${index + 1}: ${note.name}, ${noteDurationLabel(note.durationBeats)}. Düzenle." }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(note.name, color = Green, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(noteDurationLabel(note.durationBeats), color = Muted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun PlaybackCard(
    instrument: Instrument,
    tempo: Int,
    isPlaying: Boolean,
    onInstrumentChange: (Instrument) -> Unit,
    onTempoChange: (Int) -> Unit,
    onPlay: () -> Unit,
    onPlaySample: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Çalma ayarları", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                Instrument.values().forEach { option ->
                    val selected = option == instrument
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onInstrumentChange(option) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) PaleGreen else Paper,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Green else Line),
                    ) {
                        Text(
                            option.displayName.removePrefix("Akustik "),
                            color = if (selected) Green else Muted,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 11.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Tempo", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("$tempo BPM", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(value = tempo.toFloat(), onValueChange = { onTempoChange(it.toInt()) }, valueRange = 50f..160f)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) { Text(if (isPlaying) "Durdur" else "Dinle") }
                OutlinedButton(onClick = onPlaySample, modifier = Modifier.weight(1f), enabled = !isPlaying) { Text("Örnek sesi dene") }
            }
        }
    }
}

@Composable
private fun MusicMark(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val stemX = size.width * 0.64f
        val noteY = size.height * 0.65f
        drawLine(color, Offset(stemX, size.height * 0.2f), Offset(stemX, noteY), strokeWidth = size.width * 0.08f, cap = StrokeCap.Round)
        drawLine(color, Offset(stemX, size.height * 0.21f), Offset(size.width * 0.88f, size.height * 0.32f), strokeWidth = size.width * 0.07f, cap = StrokeCap.Round)
        drawOval(color, topLeft = Offset(size.width * 0.18f, noteY - size.height * 0.1f), size = Size(size.width * 0.49f, size.height * 0.26f))
    }
}

@Composable
private fun CameraMark(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        drawRoundRect(color, topLeft = Offset(size.width * 0.08f, size.height * 0.25f), size = Size(size.width * 0.84f, size.height * 0.62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f), style = Stroke(width = size.width * 0.09f))
        drawRoundRect(color, topLeft = Offset(size.width * 0.28f, size.height * 0.12f), size = Size(size.width * 0.26f, size.height * 0.18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.04f))
        drawCircle(color, radius = size.width * 0.16f, center = Offset(size.width * 0.51f, size.height * 0.56f), style = Stroke(width = size.width * 0.08f))
    }
}

@Composable
private fun DocumentMark(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.08f)
            lineTo(size.width * 0.6f, size.height * 0.08f)
            lineTo(size.width * 0.82f, size.height * 0.3f)
            lineTo(size.width * 0.82f, size.height * 0.9f)
            lineTo(size.width * 0.2f, size.height * 0.9f)
            close()
        }
        drawPath(path, color, style = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round))
        drawLine(color, Offset(size.width * 0.31f, size.height * 0.49f), Offset(size.width * 0.69f, size.height * 0.49f), strokeWidth = size.width * 0.07f, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.31f, size.height * 0.66f), Offset(size.width * 0.69f, size.height * 0.66f), strokeWidth = size.width * 0.07f, cap = StrokeCap.Round)
    }
}
