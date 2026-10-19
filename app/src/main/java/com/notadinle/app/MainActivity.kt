package com.notadinle.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notadinle.app.domain.model.ScoreDocument
import com.notadinle.app.presentation.home.HomeScreen
import com.notadinle.app.presentation.home.HomeViewModel
import com.notadinle.app.presentation.home.NotaDinleTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels {
        (application as NotaDinleApplication).container.homeViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotaDinleTheme {
                NotaDinleRoute(viewModel)
            }
        }
    }
}

@Composable
private fun NotaDinleRoute(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = pendingCameraUri
        if (captured && uri != null) {
            viewModel.selectDocument(ScoreDocument(uri.toString(), "Kamera fotoğrafı", "image/jpeg"))
        } else {
            pendingCameraFile?.delete()
        }
        pendingCameraUri = null
        pendingCameraFile = null
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = resolveDisplayName(context, uri)
            viewModel.selectDocument(ScoreDocument(uri.toString(), displayName, context.contentResolver.getType(uri)))
        }
    }

    val openCamera: () -> Unit = {
        runCatching {
            val directory = File(context.cacheDir, "scores").apply { mkdirs() }
            val file = File.createTempFile("score_", ".jpg", directory)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraFile = file
            pendingCameraUri = uri
            camera.launch(uri)
        }.onFailure { error ->
            pendingCameraFile?.delete()
            pendingCameraFile = null
            pendingCameraUri = null
            viewModel.reportError(error.localizedMessage ?: "Kamera açılamadı. Bir görsel veya PDF seçmeyi dene.")
        }
    }

    HomeScreen(
        state = state,
        onCamera = openCamera,
        onPickFile = { filePicker.launch(arrayOf("image/*", "application/pdf")) },
        onRemoveDocument = viewModel::clearDocument,
        onPageChange = viewModel::selectPage,
        onRecognize = viewModel::recognize,
        onInstrumentChange = viewModel::selectInstrument,
        onTempoChange = viewModel::setTempo,
        onPlay = viewModel::playOrStop,
        onPlaySample = viewModel::playSample,
        onAdjustPitch = viewModel::adjustPitch,
        onAdjustDuration = viewModel::adjustDuration,
    )
}

private fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0) return cursor.getString(nameColumn)
        }
    }
    return "Nota sayfası"
}
