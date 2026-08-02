package com.cleo.blebridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.cleo.blebridge.databinding.ActivityTestModeBinding

class TestModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestModeBinding
    private lateinit var peripheral: CscPeripheral

    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBridge()
        } else {
            Toast.makeText(this, "Servono i permessi Bluetooth per funzionare", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peripheral = CscPeripheral(
            context = this,
            onStatus = { status -> runOnUiThread { binding.textBleStatus.text = status } },
            onLog = { line -> runOnUiThread { binding.textLog.append("$line\n") } }
        )

        binding.buttonStart.setOnClickListener {
            if (hasAllPermissions()) startBridge() else permissionLauncher.launch(permissions)
        }

        binding.buttonStop.setOnClickListener {
            peripheral.stop()
        }

        if (!peripheral.isBlePeripheralSupported()) {
            binding.textBleStatus.text = "⚠️ Questo dispositivo potrebbe NON supportare BLE peripheral mode"
        }

        binding.editSpeed.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s.toString().replace(',', '.').toDoubleOrNull()
                if (v != null && v >= 0) peripheral.currentSpeedKmh = v
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.buttonPlus.setOnClickListener { adjustSpeed(0.5) }
        binding.buttonMinus.setOnClickListener { adjustSpeed(-0.5) }
    }

    private fun adjustSpeed(delta: Double) {
        val current = binding.editSpeed.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
        val updated = (current + delta).coerceAtLeast(0.0)
        binding.editSpeed.setText(String.format(java.util.Locale.US, "%.1f", updated))
    }

    private fun hasAllPermissions(): Boolean =
        permissions.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun startBridge() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Attiva il Bluetooth e riprova", Toast.LENGTH_LONG).show()
            return
        }

        val speed = binding.editSpeed.text.toString().replace(',', '.').toDoubleOrNull()
        if (speed == null || speed < 0) {
            Toast.makeText(this, "Inserisci una velocità valida", Toast.LENGTH_SHORT).show()
            return
        }

        peripheral.currentSpeedKmh = speed
        peripheral.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        peripheral.stop()
    }
}
