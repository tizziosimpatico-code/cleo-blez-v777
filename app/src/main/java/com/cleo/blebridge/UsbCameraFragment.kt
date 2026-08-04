package com.cleo.blebridge

import android.bluetooth.BluetoothAdapter
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cleo.blebridge.databinding.FragmentUsbCameraBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs

class UsbCameraFragment : Fragment() {

    private var mViewBinding: FragmentUsbCameraBinding? = null
    private var cameraHelper: ICameraHelper? = null
    private lateinit var peripheral: CscPeripheral
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var analysisBusy = false

    private var lastGoodSpeed: Double? = null
    private var pendingCandidate: Double? = null
    private var pendingCount = 0

    private var currentPreviewWidth = 0
    private var currentPreviewHeight = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentUsbCameraBinding.inflate(inflater, container, false)
        mViewBinding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = mViewBinding ?: return
        logLine("onViewCreated chiamato")

        peripheral = CscPeripheral(
            context = requireContext(),
            onStatus = { status -> requireActivity().runOnUiThread { binding.textBleStatus.text = status } },
            onLog = { }
        )

        binding.cameraSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                logLine("Surface pronta")
                cameraHelper?.addSurface(holder.surface, false)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraHelper?.removeSurface(holder.surface)
            }
        })

        cameraHelper = CameraHelper().apply {
            setStateCallback(stateCallback)
        }
        logLine("CameraHelper creato, in ascolto di dispositivi USB")

        registerLiveAttachReceiver()

        binding.buttonDetectUsb.setOnClickListener {
            logLine("Pulsante RILEVA WEBCAM premuto")
            recreateCameraHelper()
            val usbManager = requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val device = usbManager.deviceList.values.firstOrNull()
            if (device == null) {
                Toast.makeText(requireContext(), "Nessuna webcam USB rilevata dal sistema in questo momento.", Toast.LENGTH_LONG).show()
            } else {
                requestPermissionThenSelect(device, usbManager)
            }
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
                Toast.makeText(requireContext(), "Attiva il Bluetooth e riprova", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            peripheral.start()
        }
        binding.buttonStop.setOnClickListener { peripheral.stop() }
    }

    private val liveAttachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        logLine("🔵 EVENTO LIVE ricevuto: ${device.deviceName}, produttore=${device.manufacturerName ?: "?"} — richiedo il permesso")
                        val usbManager = requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
                        requestPermissionThenSelect(device, usbManager)
                    }
                }
                "android.hardware.usb.action.USB_DEVICE_DETACHED" -> {
                    logLine("🔴 EVENTO LIVE: dispositivo scollegato — ricreo il CameraHelper da zero")
                    currentPreviewWidth = 0
                    currentPreviewHeight = 0
                    lastGoodSpeed = null
                    pendingCandidate = null
                    pendingCount = 0
                    recreateCameraHelper()
                    activity?.runOnUiThread {
                        mViewBinding?.textUsbStatus?.text = "🔌 Webcam USB scollegata"
                        mViewBinding?.textDetectedSpeed?.text = "Velocità rilevata: --"
                        clearSurface()
                    }
                }
            }
        }
    }

    private val usbPermissionAction = "com.cleo.blebridge.USB_PERMISSION"

    private val usbPermissionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (usbPermissionAction != intent.action) return
            val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            logLine("Risposta permesso: concesso=$granted")
            if (granted && device != null) {
                logLine("✅ Permesso confermato — ora chiamo selectDevice()")
                cameraHelper?.selectDevice(device)
            } else {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Permesso negato dall'utente o dal sistema.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun recreateCameraHelper() {
        try {
            cameraHelper?.release()
        } catch (e: Exception) {
            logLine("Eccezione rilasciando il vecchio CameraHelper: ${e.message}")
        }
        cameraHelper = CameraHelper().apply {
            setStateCallback(stateCallback)
        }
        logLine("Nuovo CameraHelper creato, pronto per una connessione pulita")
    }

    private fun requestPermissionThenSelect(device: UsbDevice, usbManager: android.hardware.usb.UsbManager) {
        if (usbManager.hasPermission(device)) {
            logLine("Permesso già presente — chiamo selectDevice() subito")
            cameraHelper?.selectDevice(device)
            return
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.app.PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = android.app.PendingIntent.getBroadcast(
            requireContext(), 0, android.content.Intent(usbPermissionAction), flags
        )
        usbManager.requestPermission(device, permissionIntent)
        logLine("Richiesta permesso inviata al sistema")
    }

    private fun registerLiveAttachReceiver() {
        val attachFilter = android.content.IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
            addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
        }
        val permissionFilter = android.content.IntentFilter(usbPermissionAction)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(liveAttachReceiver, attachFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            requireContext().registerReceiver(usbPermissionReceiver, permissionFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(liveAttachReceiver, attachFilter)
            requireContext().registerReceiver(usbPermissionReceiver, permissionFilter)
        }
        logLine("In ascolto per eventi di attacco/distacco USB live")

        val usbManager = requireContext().getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val alreadyConnected = usbManager.deviceList.values.firstOrNull()
        if (alreadyConnected != null) {
            logLine("Dispositivo già collegato trovato: ${alreadyConnected.deviceName}")
            requestPermissionThenSelect(alreadyConnected, usbManager)
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            logLine("🔵 onAttach (rilevamento automatico della libreria)")
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            logLine("onDeviceOpen: permesso ottenuto (primo utilizzo=$isFirstOpen), apro la camera")
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            logLine("✅ onCameraOpen: la webcam è APERTA")
            activity?.runOnUiThread { mViewBinding?.textUsbStatus?.text = "✅ Webcam USB collegata" }

            val size = cameraHelper?.previewSize
            if (size != null) {
                currentPreviewWidth = size.width
                currentPreviewHeight = size.height
                logLine("Formato anteprima: ${size.width}x${size.height}")
            }

            cameraHelper?.startPreview()
            mViewBinding?.cameraSurfaceView?.holder?.surface?.let {
                if (it.isValid) cameraHelper?.addSurface(it, false)
            }
            cameraHelper?.setFrameCallback(
                IFrameCallback { frame -> handleFrame(frame) },
                UVCCamera.PIXEL_FORMAT_NV21
            )
        }

        override fun onCameraClose(device: UsbDevice) {
            logLine("onCameraClose")
            activity?.runOnUiThread { mViewBinding?.textUsbStatus?.text = "🔌 Webcam USB chiusa" }
        }

        override fun onDeviceClose(device: UsbDevice) {
            logLine("onDeviceClose")
        }

        override fun onDetach(device: UsbDevice) {
            logLine("🔴 onDetach: dispositivo scollegato")
            activity?.runOnUiThread { mViewBinding?.textUsbStatus?.text = "🔌 Webcam USB scollegata" }
        }

        override fun onCancel(device: UsbDevice) {
            logLine("⚠️ onCancel: permesso negato dall'utente o dal sistema")
        }
    }

    private fun handleFrame(frame: ByteBuffer) {
        if (analysisBusy || currentPreviewWidth == 0 || currentPreviewHeight == 0) return
        analysisBusy = true
        try {
            val raw = ByteArray(frame.remaining())
            frame.get(raw)
            val bitmap = nv21ToBitmap(raw, currentPreviewWidth, currentPreviewHeight)
            if (bitmap == null) {
                analysisBusy = false
                return
            }

            val roi = mViewBinding?.roiOverlay?.normalizedRect
            val bitmapToAnalyze = if (roi != null) {
                val left = (roi.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 2)
                val top = (roi.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 2)
                val w = (roi.width() * bitmap.width).toInt().coerceAtLeast(1).coerceAtMost(bitmap.width - left)
                val h = (roi.height() * bitmap.height).toInt().coerceAtLeast(1).coerceAtMost(bitmap.height - top)
                Bitmap.createBitmap(bitmap, left, top, w, h)
            } else {
                bitmap
            }

            val preprocessed = preprocessForOcr(bitmapToAnalyze)
            val rawValue = readSevenSegmentSpeed(preprocessed)
            handleDetectedValue(rawValue)
            analysisBusy = false
        } catch (e: Exception) {
            logLine("ECCEZIONE in handleFrame: ${e.javaClass.simpleName}: ${e.message}")
            analysisBusy = false
        }
    }

    private fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpegBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            null
        }
    }

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
        if (raw == null) return
        if (raw !in 0.0..99.9) return
        acceptSpeed(raw)
    }

    private fun acceptSpeed(v: Double) {
        lastGoodSpeed = v
        pendingCandidate = null
        pendingCount = 0
        activity?.runOnUiThread { mViewBinding?.textDetectedSpeed?.text = "Velocità rilevata: $v km/h" }
        peripheral.currentSpeedKmh = v
    }

    private fun clearSurface() {
        val holder = mViewBinding?.cameraSurfaceView?.holder ?: return
        if (!holder.surface.isValid) return
        try {
            val canvas = holder.lockCanvas()
            canvas?.drawColor(android.graphics.Color.BLACK)
            if (canvas != null) holder.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            logLine("Impossibile pulire la superficie: ${e.message}")
        }
    }

    private fun logLine(msg: String) {
        val time = android.text.format.DateFormat.format("HH:mm:ss", java.util.Date())
        activity?.runOnUiThread {
            mViewBinding?.textDebugLog?.append("\n[$time] $msg")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        peripheral.stop()
        try { requireContext().unregisterReceiver(liveAttachReceiver) } catch (e: Exception) { }
        try { requireContext().unregisterReceiver(usbPermissionReceiver) } catch (e: Exception) { }
        cameraHelper?.release()
        cameraHelper = null
        mViewBinding = null
    }
}
