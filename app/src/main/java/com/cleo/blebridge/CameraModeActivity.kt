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
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
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
                    val digitsOnly = visionText.text.filter { it.isDigit() }
                    val raw = parseThreeDigitSpeed(digitsOnly)
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

    private fun parseThreeDigitSpeed(digits: String): Double? {
        if (digits.isEmpty()) return null
        if (digits.length < 3) return 10.0
        val first3 = digits.take(3)
        if (first3 == "000") return 0.0
        val intPart = first3.substring(0, 2).toIntOrNull() ?: return 10.0
        val decDigit = first3.substring(2, 3).toIntOrNull() ?: 0
        return intPart + decDigit / 10.0
    }

    private fun handleDetectedValue(raw: Double?) {
        if (raw == null) return

        var candidate = raw
        if (candidate != 0.0) {
            candidate = candidate.coerceIn(10.0, 40.0)
        }

        val last = lastGoodSpeed
        if (last == null || abs(candidate - last) <= 5.0) {
            acceptSpeed(candidate)
        } else {
            if (pendingCandidate != null && abs(candidate - pendingCandidate!!) <= 2.0) {
                pendingCount++
            } else {
                pendingCandidate = candidate
                pendingCount = 1
            }
            if (pendingCount >= 2) {
                acceptSpeed(candidate)
            }
        }
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
