package org.civicrelay.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.civicrelay.app.storage.EvidenceViewPolicy
import org.civicrelay.app.storage.VerifiedEvidence

/** Decode bounded images only after the encrypted file has been fully authenticated. */
@Composable fun VerifiedPhotoPreview(record: VerifiedEvidence) {
    val bitmap = remember(record) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(record.bytes, 0, record.bytes.size, bounds)
            val sample = EvidenceViewPolicy.previewSampleSize(bounds.outWidth, bounds.outHeight)
            BitmapFactory.decodeByteArray(record.bytes, 0, record.bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
                ?: throw IllegalArgumentException("Photo cannot be decoded")
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Verified private evidence photo",
            modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp))
    } else Text("Verified bytes could not be decoded as a photo", color = MaterialTheme.colorScheme.error)
}

/** Android MediaPlayer seeks through already-verified bytes; no decrypted local video file is created. */
private class MemoryMediaSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = bytes.size.toLong()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= bytes.size.toLong()) return -1
        val count = minOf(size, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }
    override fun close() { /* The parent viewer owns and zeroes the byte array. */ }
}

private class MemoryVideoSurface(context: Context, private val data: ByteArray) : SurfaceView(context), SurfaceHolder.Callback {
    private var player: MediaPlayer? = null
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set

    init { holder.addCallback(this) }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            player = MediaPlayer().apply {
                setDisplay(holder)
                setDataSource(MemoryMediaSource(data))
                setOnPreparedListener { ready = true }
                setOnCompletionListener { playing = false }
                setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
                prepareAsync()
            }
        } catch (_: Exception) { failed = true; release() }
    }
    fun toggle() {
        val current = player ?: return
        if (!ready || failed) return
        if (current.isPlaying) { current.pause(); playing = false }
        else { current.start(); playing = true }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { release() }
    fun release() {
        val old = player
        player = null
        try { old?.stop() } catch (_: Exception) { /* not prepared */ }
        old?.release()
        ready = false
        playing = false
    }
}

@Composable fun VerifiedVideoPreview(record: VerifiedEvidence) {
    val context = LocalContext.current
    val view = remember(record) { MemoryVideoSurface(context, record.bytes) }
    DisposableEffect(view) { onDispose { view.release() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(240.dp))
        if (view.failed) Text("Video decoder could not open this file", color = MaterialTheme.colorScheme.error)
        Button(enabled = view.ready && !view.failed, onClick = { view.toggle() }) {
            Text(if (view.playing) "Pause private video" else "Play private video")
        }
    }
}
