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

            val image = InputImage.fromBitmap(bitmapToAnalyze, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val digitsOnly = extractDigitsLeftToRight(visionText)
                    val raw = parseDigitSpeed(digitsOnly)
                    handleDetectedValue(raw)
                }
                .addOnCompleteListener {
                    analysisBusy = false
                    imageProxy.close()
                }
        } catch (e: Exception) {
            analysisBusy = false
            imageProxy.close()
        }
    }

    /**
     * Ordina i pezzi di testo trovati dall'OCR in base alla loro posizione ORIZZONTALE reale
     * sullo schermo (da sinistra a destra), invece di fidarsi dell'ordine con cui il motore
     * OCR li restituisce di default (che a volte non rispetta l'ordine visivo).
     */
    private fun extractDigitsLeftToRight(visionText: com.google.mlkit.vision.text.Text): String {
        val pieces = mutableListOf<Pair<Int, String>>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    val digitsInElement = element.text.filter { it.isDigit() }
                    if (digitsInElement.isNotEmpty()) {
                        pieces.add(box.left to digitsInElement)
                    }
                }
            }
        }
        return pieces.sortedBy { it.first }.joinToString("") { it.second }
    }

    /**
     * Il display della Cleo probabilmente NON mostra lo zero iniziale sotto i 10 km/h
     * (es. "5.4" invece di "05.4"), quindi il numero di cifre lette può essere 1, 2 o 3:
     * - 3 cifre: decine, unità, decimale (es. "206" = 20.6)
     * - 2 cifre: unità, decimale (es. "54" = 5.4, "00" = 0.0)
     * - 1 cifra: probabilmente solo il decimale letto, poco affidabile
     */
    private fun parseDigitSpeed(digits: String): Double? {
        return when (digits.length) {
            0 -> null
            1 -> digits.toIntOrNull()?.let { it / 10.0 }
            2 -> {
                val intPart = digits.substring(0, 1).toIntOrNull() ?: return null
                val decDigit = digits.substring(1, 2).toIntOrNull() ?: 0
                intPart + decDigit / 10.0
            }
            else -> {
                val first3 = digits.take(3)
                val intPart = first3.substring(0, 2).toIntOrNull() ?: return null
                val decDigit = first3.substring(2, 3).toIntOrNull() ?: 0
                intPart + decDigit / 10.0
            }
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
