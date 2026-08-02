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

/**
 * Legge una webcam USB (UVC) usando la libreria UVCAndroid (com.herohan),
 * la stessa base tecnologica dell'app "USB Camera" che già funziona su questo tablet.
 * Riusa lo stesso motore OCR + filtro anti-rumore + BLE delle altre modalità.
 */
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

        // Se un dispositivo è già collegato da prima che aprissimo questa schermata,
        // avviamo comunque la richiesta di permesso subito.
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

            val image = InputImage.fromBitmap(bitmapToAnalyze, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val cleanedText = visionText.text.replace(Regex("""\s+"""), "")
                    val match = Regex("""\d+[.,]?\d*""").find(cleanedText)
                    val rawValue = match?.value?.replace(',', '.')?.toDoubleOrNull()
                    handleDetectedValue(rawValue)
                }
                .addOnCompleteListener { analysisBusy = false }
        } catch (e: Exception) {
            logLine("ECCEZIONE in handleFrame: ${e.javaClass.simpleName}: ${e.message}")
            analysisBusy = false
        }
    }

    private fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            val jpegBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun handleDetectedValue(raw: Double?) {
        if (raw == null) return

        var candidate = raw
        if (candidate > 60.0 && candidate % 10.0 == 0.0) {
            val adjusted = candidate / 10.0
            if (adjusted in 0.0..60.0) candidate = adjusted
        }
        if (candidate !in 0.0..80.0) return

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
