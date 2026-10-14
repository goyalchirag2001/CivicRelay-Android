package org.civicrelay.app.media

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.util.concurrent.Executors
import android.content.Context

@Composable fun ContactQr(code: String, modifier: Modifier = Modifier) {
    val bitmap = remember(code) {
        val bits: BitMatrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 700, 700)
        val pixels = IntArray(700 * 700) { index ->
            if (bits[index % 700, index / 700]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, 700, 700, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, 700, 0, 0, 700, 700)
        }
    }
    Image(bitmap.asImageBitmap(), contentDescription = "Your public contact QR code", modifier = modifier)
}

/** One-shot bundled ML Kit QR scanner; no server or network. */
class ContactScanner(private val context: Context, private val onResult: (String) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    private var provider: ProcessCameraProvider? = null
    private var completed = false
    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun attach(view: PreviewView, owner: LifecycleOwner) {
        val task = ProcessCameraProvider.getInstance(context)
        task.addListener({
            try {
                val p = task.get(); provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val image = proxy.image
                    if (image == null || completed) { proxy.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
                    scanner.process(input).addOnSuccessListener { codes ->
                        val text = codes.firstOrNull { it.rawValue?.startsWith("CRCONTACT1:") == true }?.rawValue
                        if (text != null && !completed) {
                            completed = true
                            onResult(text)
                        }
                    }.addOnCompleteListener { proxy.close() }
                }
                p.unbindAll(); p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { /* Display the manual paste fallback */ }
        }, ContextCompat.getMainExecutor(context))
    }
    fun close() { provider?.unbindAll(); scanner.close(); executor.shutdown() }
}
