package com.cleo.blebridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cleo.blebridge.databinding.ActivityCameraModeBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class CameraModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraModeBinding
    private lateinit var peripheral: CscPeripheral
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var analysisBusy = false

    // Filtro anti-rumore: teniamo l'ultimo valore buono invece di seguire ogni lettura singola
    private var lastGoodSpeed: Double? = null
    private var pendingCandidate: Double? = null
    private var pendingCount = 0
    private var usingBackCamera = true

    private val permissionsNeeded: Array<String>
        get() {
            val list = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return list.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startCamera()
        } else {
            Toast.makeText(this, "Servono i permessi fotocamera e Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peripheral = CscPeripheral(
            context = this,
            onStatus = { status -> runOnUiThread { binding.textBleStatus.text = status } },
            onLog = { }
        )

        binding.buttonSwitchCamera.setOnClickListener {
            usingBackCamera = !usingBackCamera
            lastGoodSpeed = null
            pendingCandidate = null
            pendingCount = 0
            startCamera()
        }

        binding.buttonResetRoi.setOnClickListener {
            binding.roiOverlay.reset()
            lastGoodSpeed = null
            pendingCandidate = null
            pendingCount = 0
        }

        binding.buttonStart.setOnClickListener {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, "Attiva il Bluetooth e riprova", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            peripheral.start()
        }
        binding.buttonStop.setOnClickListener { peripheral.stop() }

        if (hasAllPermissions()) startCamera() else permissionLauncher.launch(permissionsNeeded)
    }

    private fun hasAllPermissions(): Boolean =
        permissionsNeeded.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            val selector = if (usingBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Converte il fotogramma YUV della fotocamera in un Bitmap normale, già ruotato correttamente. */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 85, out)
        val jpegBytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (analysisBusy) {
            imageProxy.close()
            return
        }
        analysisBusy = true

        try {
            val fullBitmap = imageProxyToBitmap(imageProxy)
            val roi = binding.roiOverlay.normalizedRect

            val bitmapToAnalyze = if (roi != null) {
                val left = (roi.left * fullBitmap.width).toInt().coerceIn(0, fullBitmap.width - 2)
                val top = (roi.top * fullBitmap.height).toInt().coerceIn(0, fullBitmap.height - 2)
                val w = (roi.width() * fullBitmap.width).toInt().coerceAtLeast(1).coerceAtMost(fullBitmap.width - left)
                val h = (roi.height() * fullBitmap.height).toInt().coerceAtLeast(1).coerceAtMost(fullBitmap.height - top)
                Bitmap.createBitmap(fullBitmap, left, top, w, h)
            } else {
                fullBitmap
            }

            val preprocessed = preprocessForOcr(bitmapToAnalyze)
            val raw = readSevenSegmentSpeed(preprocessed)
            handleDetectedValue(raw)
            analysisBusy = false
            imageProxy.close()
        } catch (e: Exception) {
            analysisBusy = false
            imageProxy.close()
        }
    }

    /**
     * Prepara l'immagine per l'OCR: la ingrandisce e la converte in bianco/nero netto
     * (senza sfumature/rumore), tecnica standard per migliorare la lettura di display digitali
     * a segmenti, che l'OCR "generico" fa fatica a leggere così come sono.
     */
    private fun preprocessForOcr(source: Bitmap): Bitmap {
        val scaleFactor = 3
        val scaled = Bitmap.createScaledBitmap(source, source.width * scaleFactor, source.height * scaleFactor, true)
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminances = IntArray(pixels.size)
        var sum = 0L
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r + g + b) / 3
            luminances[i] = lum
            sum += lum
        }
        val mean = (sum / pixels.size).toInt()
        val threshold = (mean * 0.75).toInt()

        for (i in pixels.indices) {
            pixels[i] = if (luminances[i] < threshold) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Legge la velocità analizzando direttamente quali "barrette" del display sono accese,
     * invece di affidarsi al riconoscimento OCR generico (che si è dimostrato inaffidabile
     * su questo tipo di display a 7 segmenti).
     */
    private fun readSevenSegmentSpeed(binary: Bitmap): Double? {
        val width = binary.width
        val height = binary.height
        val pixels = IntArray(width * height)
        binary.getPixels(pixels, 0, width, 0, 0, width, height)

        val colDark = IntArray(width)
        for (x in 0 until width) {
            var count = 0
            for (y in 0 until height) {
                if (pixels[y * width + x] == android.graphics.Color.BLACK) count++
            }
            colDark[x] = count
        }

        val minDarkForColumn = (height * 0.05).toInt().coerceAtLeast(1)
        val blobs = mutableListOf<IntRange>()
        var blobStart = -1
        for (x in 0 until width) {
            val hasInk = colDark[x] >= minDarkForColumn
            if (hasInk && blobStart == -1) {
                blobStart = x
            } else if (!hasInk && blobStart != -1) {
                blobs.add(blobStart until x)
                blobStart = -1
            }
        }
        if (blobStart != -1) blobs.add(blobStart until width)

        val minBlobWidth = (width * 0.03).toInt().coerceAtLeast(2)
        val digitBlobs = blobs.filter { (it.last - it.first) >= minBlobWidth }
        if (digitBlobs.isEmpty()) return null

        val digits = digitBlobs.mapNotNull { range ->
            decodeSevenSegmentDigit(pixels, width, height, range.first, range.last)
        }
        if (digits.isEmpty()) return null

        // Formato fisso: l'ultima cifra letta è sempre il decimale
        return when (digits.size) {
            1 -> digits[0] / 10.0
            2 -> digits[0] + digits[1] / 10.0
            else -> {
                val intPart = digits.dropLast(1).joinToString("") { it.toString() }.toIntOrNull() ?: return null
                intPart + digits.last() / 10.0
            }
        }
    }

    private fun decodeSevenSegmentDigit(pixels: IntArray, width: Int, height: Int, x0: Int, x1: Int): Int? {
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            var hasInk = false
            for (x in x0..x1) {
                if (pixels[y * width + x] == android.graphics.Color.BLACK) { hasInk = true; break }
            }
            if (hasInk) {
                if (top == -1) top = y
                bottom = y
            }
        }
        if (top == -1 || bottom - top < 4) return null

        val h = (bottom - top).toFloat()
        val w = (x1 - x0).toFloat()

        fun zoneDark(fx0: Float, fx1: Float, fy0: Float, fy1: Float): Boolean {
            val px0 = (x0 + fx0 * w).toInt().coerceIn(x0, x1)
            val px1 = (x0 + fx1 * w).toInt().coerceIn(x0, x1)
            val py0 = (top + fy0 * h).toInt().coerceIn(top, bottom)
            val py1 = (top + fy1 * h).toInt().coerceIn(top, bottom)
            var dark = 0
            var total = 0
            for (y in py0..py1) {
                for (x in px0..px1) {
                    total++
                    if (pixels[y * width + x] == android.graphics.Color.BLACK) dark++
                }
            }
            return total > 0 && dark.toFloat() / total > 0.35f
        }

        val segA = zoneDark(0.2f, 0.8f, 0.0f, 0.15f)
        val segB = zoneDark(0.7f, 1.0f, 0.1f, 0.45f)
        val segC = zoneDark(0.7f, 1.0f, 0.55f, 0.9f)
        val segD = zoneDark(0.2f, 0.8f, 0.85f, 1.0f)
        val segE = zoneDark(0.0f, 0.3f, 0.55f, 0.9f)
        val segF = zoneDark(0.0f, 0.3f, 0.1f, 0.45f)
        val segG = zoneDark(0.2f, 0.8f, 0.42f, 0.58f)

        return when {
            segA && segB && segC && segD && segE && segF && !segG -> 0
            !segA && segB && segC && !segD && !segE && !segF && !segG -> 1
            segA && segB && !segC && segD && segE && !segF && segG -> 2
            segA && segB && segC && segD && !segE && !segF && segG -> 3
            !segA && segB && segC && !segD && !segE && segF && segG -> 4
            segA && !segB && segC && segD && !segE && segF && segG -> 5
            segA && !segB && segC && segD && segE && segF && segG -> 6
            segA && segB && segC && !segD && !segE && !segF && !segG -> 7
            segA && segB && segC && segD && segE && segF && segG -> 8
            segA && segB && segC && segD && !segE && segF && segG -> 9
            else -> null
        }
    }

    private fun handleDetectedValue(raw: Double?) {
        if (raw == null) return // nessuna cifra letta in questo fotogramma: teniamo l'ultimo valore buono
        if (raw !in 0.0..99.9) return // fuori range plausibile, scartiamo
        acceptSpeed(raw)
    }

    private fun acceptSpeed(v: Double) {
        lastGoodSpeed = v
        pendingCandidate = null
        pendingCount = 0
        runOnUiThread { binding.textDetectedSpeed.text = "Velocità rilevata: $v km/h" }
        peripheral.currentSpeedKmh = v
    }

    override fun onDestroy() {
        super.onDestroy()
        peripheral.stop()
    }
}
