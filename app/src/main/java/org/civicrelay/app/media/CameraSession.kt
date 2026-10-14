package org.civicrelay.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.io.File
import java.util.UUID

/** Visible-activity CameraX only: plaintext temporary media exists until encryption completes. */
class CameraSession(private val context: Context, private val onMedia: (File, String, String) -> Unit,
                    private val onStatus: (String) -> Unit) {
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val tempDir = File(context.cacheDir, "capture").apply { mkdirs() }
    // Cancel asynchronous camera/OCR callbacks when the vault locks or the screen is disposed.
    // This is best-effort cleanup, NOT secure deletion on flash storage.
    @Volatile private var closed = false
    private val outstanding = mutableSetOf<File>()
    private fun track(file: File): File = file.also { outstanding.add(it) }
    private fun discard(file: File) { outstanding.remove(file); file.delete() }
    private fun deliver(file: File, kind: String, ocr: String) {
        if (closed) { discard(file); return }
        outstanding.remove(file)
        try { onMedia(file, kind, ocr) } catch (_: Exception) { file.delete() }
    }

    fun attach(previewView: PreviewView, owner: LifecycleOwner) {
        if (closed) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                if (closed) return@addListener
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val photo = ImageCapture.Builder().build()
                val video = VideoCapture.withOutput(Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD)).build())
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, photo, video)
                imageCapture = photo; videoCapture = video
                onStatus("Camera ready. Temporary capture is not encrypted until processing finishes.")
            } catch (e: Exception) { onStatus("Camera unavailable: ${e.localizedMessage}") }
        }, main)
    }
    fun photo() {
        if (closed) return
        val capture = imageCapture ?: return onStatus("Camera not initialized")
        val file = track(File(tempDir, "${UUID.randomUUID()}.jpg"))
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), main,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (closed) discard(file) else recognizeBadgeText(file)
                }
                override fun onError(error: ImageCaptureException) {
                    discard(file)
                    if (!closed) onStatus("Photo failed: ${error.message}")
                }
            })
    }
    private fun recognizeBadgeText(file: File) {
        if (closed) { discard(file); return }
        // OCR is a suggestion, NOT a reliable verification of officer identity.
        val input = try { InputImage.fromFilePath(context, Uri.fromFile(file)) }
        catch (_: Exception) { deliver(file, "photo", ""); return }
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        latin.process(input).addOnCompleteListener { result ->
            val latinText = if (result.isSuccessful) result.result.text else ""
            latin.close()
            if (closed) { discard(file); return@addOnCompleteListener }
            val hindi = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            hindi.process(input).addOnCompleteListener { second ->
                val hindiText = if (second.isSuccessful) second.result.text else ""
                hindi.close()
                deliver(file, "photo", (latinText + "\n" + hindiText).trim().take(1200))
            }
        }
    }
    fun toggleVideo() {
        if (closed) return
        val existing = activeRecording
        if (existing != null) {
            existing.stop()
            activeRecording = null
            onStatus("Finalizing video...")
            return
        }
        val video = videoCapture ?: return onStatus("Camera not initialized")
        val file = track(File(tempDir, "${UUID.randomUUID()}.mp4"))
        try {
            var pending: PendingRecording = video.output.prepareRecording(context,
                FileOutputOptions.Builder(file).build())
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                pending = pending.withAudioEnabled()
            activeRecording = pending.start(main) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    activeRecording = null
                    if (!closed && !event.hasError() && file.exists()) deliver(file, "video", "")
                    else {
                        discard(file)
                        if (!closed) onStatus("Video capture failed: ${event.error}")
                    }
                }
            }
            onStatus("Recording. Maximum 30 seconds; tap Stop to finish.")
            handler.postDelayed({ if (activeRecording != null && !closed) toggleVideo() }, 30_000)
        } catch (error: Exception) {
            discard(file)
            if (!closed) onStatus("Video could not start: ${error.localizedMessage}")
        }
    }
    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        outstanding.toList().forEach(::discard)
    }
}
