package com.cleo.blebridge

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Implementa un sensore BLE "Cycling Speed and Cadence" (CSC) in modalità periferica.
 * MyWhoosh (in modalità "Speed Sensor") si collega a questo servizio come farebbe
 * con un sensore di velocità Bluetooth tradizionale.
 *
 * IMPORTANTE: la circonferenza ruota qui sotto deve corrispondere a quella impostata
 * dentro MyWhoosh nella configurazione del sensore di velocità, altrimenti la velocità
 * ricostruita da MyWhoosh non corrisponderà a quella reale.
 */
class CscPeripheral(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "CscPeripheral"

        val CSC_SERVICE_UUID: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        val CSC_MEASUREMENT_UUID: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        val CSC_FEATURE_UUID: UUID = UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb")
        val SENSOR_LOCATION_UUID: UUID = UUID.fromString("00002a5d-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Circonferenza ruota in millimetri. 2105mm è un valore tipico per ruote 700x23c.
        // Deve combaciare con quanto impostato in MyWhoosh.
        const val WHEEL_CIRCUMFERENCE_MM = 2105

        // Fattore di correzione: MyWhoosh traduce potenza -> velocità in gioco secondo una
        // propria curva, che in pianura risulta più veloce della velocità reale pedalata.
        // Storico test: 1.00 -> 20 reali=30 in gioco; 0.65 -> 20 reali=27 in gioco.
        // La relazione non è lineare, quindi si procede per tentativi.
        // Da affinare: se in gioco vai ancora troppo veloce, abbassa questo valore; se troppo
        // lento, alzalo leggermente. Testa sempre su tratti PIANEGGIANTI (pendenza vicina a 0)
        // per non confondere l'effetto della discesa/salita con quello del fattore.
        const val GAME_SPEED_CORRECTION_FACTOR = 0.50
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var measurementChar: BluetoothGattCharacteristic? = null
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // Stato interno per il calcolo delle rivoluzioni cumulative
    private var cumulativeWheelRevs: Long = 0
    private var wheelRevFraction: Double = 0.0
    private var lastEventTime1024: Int = 0 // in unità 1/1024 s, wrap a 16 bit
    private var lastTickRealtimeMs: Long = 0L
    private var notifyBusy = false
    private var notifyBusySinceMs: Long = 0L

    @Volatile
    var currentSpeedKmh: Double = 0.0

    fun isBlePeripheralSupported(): Boolean {
        val a = adapter ?: return false
        return a.bluetoothLeAdvertiser != null && a.isMultipleAdvertisementSupported
    }

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            onStatus("🔴 Bluetooth spento")
            return
        }
        if (!isBlePeripheralSupported()) {
            onStatus("🔴 Questo dispositivo NON supporta BLE peripheral/advertising")
            return
        }

        setupGattServer()
        startAdvertising()
        running = true
        cumulativeWheelRevs = 0
        wheelRevFraction = 0.0
        lastEventTime1024 = 0
        lastTickRealtimeMs = 0L
        notifyBusy = false
        notifyBusySinceMs = 0L
        handler.post(updateLoop)
        onStatus("🟡 In pubblicità, in attesa di connessione MyWhoosh...")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(updateLoop)
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising: ${e.message}")
        }
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "gattServer close: ${e.message}")
        }
        gattServer = null
        subscribedDevices.clear()
        onStatus("🔴 FERMATO")
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(CSC_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        measurementChar = BluetoothGattCharacteristic(
            CSC_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }

        val featureChar = BluetoothGattCharacteristic(
            CSC_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            // bit0 = Wheel Revolution Data Supported
            value = byteArrayOf(0x01, 0x00)
        }

        val locationChar = BluetoothGattCharacteristic(
            SENSOR_LOCATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(0x0C) // "Rear wheel" (valore convenzionale)
        }

        service.addCharacteristic(measurementChar)
        service.addCharacteristic(featureChar)
        service.addCharacteristic(locationChar)

        gattServer?.addService(service)
    }

    private fun startAdvertising() {
        advertiser = adapter?.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuidCompat.from(CSC_SERVICE_UUID))
            .build()

        adapter?.name = "CLEO Speed Sensor"

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising avviato")
        }

        override fun onStartFailure(errorCode: Int) {
            onStatus("🔴 Advertising fallito (codice $errorCode)")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("🟢 CONNESSO (${device.address})")
                onLog("Connesso: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
                onStatus(if (subscribedDevices.isEmpty()) "🟡 In pubblicità..." else "🟢 CONNESSO")
                onLog("Disconnesso: ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyBusy = false
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (value != null && value.isNotEmpty() && value[0].toInt() != 0) {
                    subscribedDevices.add(device)
                    onLog("MyWhoosh si è iscritto alle notifiche")
                } else {
                    subscribedDevices.remove(device)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private val updateLoop = object : Runnable {
        override fun run() {
            if (!running) return
            sendMeasurement()
            handler.postDelayed(this, 250L)
        }
    }

    private fun sendMeasurement() {
        val now = android.os.SystemClock.elapsedRealtime()
        val dtMs = if (lastTickRealtimeMs == 0L) 250L else (now - lastTickRealtimeMs)
        lastTickRealtimeMs = now

        val speedMs = (currentSpeedKmh * GAME_SPEED_CORRECTION_FACTOR) / 3.6
        val circumferenceM = WHEEL_CIRCUMFERENCE_MM / 1000.0

        // rivoluzioni percorse nel tempo REALMENTE trascorso (non un secondo fisso assunto)
        val revsThisTick = if (circumferenceM > 0) (speedMs * (dtMs / 1000.0)) / circumferenceM else 0.0
        wheelRevFraction += revsThisTick
        val wholeRevs = wheelRevFraction.toLong()
        wheelRevFraction -= wholeRevs
        cumulativeWheelRevs += wholeRevs

        // incremento del tempo evento coerente con il dt reale, non un valore fisso
        val dtTicks = ((dtMs * 1024) / 1000).toInt()
        lastEventTime1024 = (lastEventTime1024 + dtTicks) and 0xFFFF

        val payload = ByteArray(7)
        payload[0] = 0x01 // flags: wheel revolution data present
        // cumulative wheel revolutions, uint32 little endian
        payload[1] = (cumulativeWheelRevs and 0xFF).toByte()
        payload[2] = ((cumulativeWheelRevs shr 8) and 0xFF).toByte()
        payload[3] = ((cumulativeWheelRevs shr 16) and 0xFF).toByte()
        payload[4] = ((cumulativeWheelRevs shr 24) and 0xFF).toByte()
        // last wheel event time, uint16 little endian
        payload[5] = (lastEventTime1024 and 0xFF).toByte()
        payload[6] = ((lastEventTime1024 shr 8) and 0xFF).toByte()

        measurementChar?.value = payload

        if (notifyBusy && (now - notifyBusySinceMs) > 2000L) {
            // La conferma di invio precedente non è mai arrivata: sblocchiamo
            // per non restare congelati per sempre.
            notifyBusy = false
        }

        if (!notifyBusy) {
            for (device in subscribedDevices) {
                notifyBusy = true
                notifyBusySinceMs = now
                gattServer?.notifyCharacteristicChanged(device, measurementChar, false)
            }
        }
    }
}

/** Piccola utility per costruire un ParcelUuid da un UUID standard. */
object ParcelUuidCompat {
    fun from(uuid: UUID): android.os.ParcelUuid = android.os.ParcelUuid(uuid)
}
