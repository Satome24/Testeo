package com.example.testeo

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList

/**
 * Wrapper para comunicación BLE en forma de socket
 * - connect, disconnect y write como métodos
 * - read + status se devuelve por SerialListener
 */
@SuppressLint("MissingPermission")
class SerialSocket(private val context: Context, private var device: BluetoothDevice?) : BluetoothGattCallback() {

    /**
     * Delegar comportamiento específico del dispositivo a clase interna
     */
    private open class DeviceDelegate {
        open fun connectCharacteristics(s: BluetoothGattService): Boolean = true
        open fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {}
        open fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {}
        open fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {}
        open fun canWrite(): Boolean = true
        open fun disconnect() {}
    }

    companion object {
        private val BLUETOOTH_LE_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BLUETOOTH_LE_CC254X_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val BLUETOOTH_LE_CC254X_CHAR_RW = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val BLUETOOTH_LE_NRF_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val BLUETOOTH_LE_NRF_CHAR_RW2 = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val BLUETOOTH_LE_NRF_CHAR_RW3 = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val BLUETOOTH_LE_MICROCHIP_SERVICE = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        private val BLUETOOTH_LE_MICROCHIP_CHAR_RW = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
        private val BLUETOOTH_LE_MICROCHIP_CHAR_W = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")
        private val BLUETOOTH_LE_TIO_SERVICE = UUID.fromString("0000FEFB-0000-1000-8000-00805F9B34FB")
        private val BLUETOOTH_LE_TIO_CHAR_TX = UUID.fromString("00000001-0000-1000-8000-008025000000")
        private val BLUETOOTH_LE_TIO_CHAR_RX = UUID.fromString("00000002-0000-1000-8000-008025000000")
        private val BLUETOOTH_LE_TIO_CHAR_TX_CREDITS = UUID.fromString("00000003-0000-1000-8000-008025000000")
        private val BLUETOOTH_LE_TIO_CHAR_RX_CREDITS = UUID.fromString("00000004-0000-1000-8000-008025000000")

        private const val MAX_MTU = 512
        private const val DEFAULT_MTU = 23
        private const val TAG = "SerialSocket"
    }

    private val writeBuffer = ArrayList<ByteArray>()
    private val pairingIntentFilter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
    }

    private val pairingBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onPairingBroadcastReceive(context, intent)
        }
    }

    private val disconnectBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            listener?.onSerialIoError(IOException("background disconnect"))
            disconnect()
        }
    }

    private var listener: SerialListener? = null
    private var delegate: DeviceDelegate? = null
    private var gatt: BluetoothGatt? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var writePending = false
    private var canceled = false
    private var connected = false
    private var payloadSize = DEFAULT_MTU - 3

    init {
        if (context is Activity) {
            throw IllegalArgumentException("expected non UI context")
        }
    }

    fun getName(): String {
        return device?.name ?: device?.address ?: "Unknown"
    }

    fun disconnect() {
        Log.d(TAG, "disconnect")
        listener = null
        device = null
        canceled = true

        synchronized(writeBuffer) {
            writePending = false
            writeBuffer.clear()
        }

        readCharacteristic = null
        writeCharacteristic = null
        delegate?.disconnect()

        gatt?.let { bluetoothGatt ->
            Log.d(TAG, "gatt.disconnect")
            bluetoothGatt.disconnect()
            Log.d(TAG, "gatt.close")
            try {
                bluetoothGatt.close()
            } catch (ignored: Exception) {}
            gatt = null
            connected = false
        }

        try {
            context.unregisterReceiver(pairingBroadcastReceiver)
        } catch (ignored: Exception) {}

        try {
            context.unregisterReceiver(disconnectBroadcastReceiver)
        } catch (ignored: Exception) {}
    }

    @Throws(IOException::class)
    fun connect(listener: SerialListener) {
        if (connected || gatt != null) {
            throw IOException("already connected")
        }

        canceled = false
        this.listener = listener

        ContextCompat.registerReceiver(
            context,
            disconnectBroadcastReceiver,
            IntentFilter(Constants.INTENT_ACTION_DISCONNECT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Log.d(TAG, "connect $device")
        context.registerReceiver(pairingBroadcastReceiver, pairingIntentFilter)

        device?.let { bluetoothDevice ->
            gatt = if (Build.VERSION.SDK_INT < 23) {
                Log.d(TAG, "connectGatt")
                bluetoothDevice.connectGatt(context, false, this)
            } else {
                Log.d(TAG, "connectGatt,LE")
                bluetoothDevice.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            }
        }

        if (gatt == null) {
            throw IOException("connectGatt failed")
        }
    }

    private fun onPairingBroadcastReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device == null || device != this.device) return

        when (intent.action) {
            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                Log.d(TAG, "pairing request $pairingVariant")
                onSerialConnectError(IOException(context.getString(R.string.pairing_request)))
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                Log.d(TAG, "bond state $previousBondState->$bondState")
            }
            else -> Log.d(TAG, "unknown broadcast ${intent.action}")
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.d(TAG, "connect status $status, discoverServices")
                if (!gatt.discoverServices()) {
                    onSerialConnectError(IOException("discoverServices failed"))
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (connected) {
                    onSerialIoError(IOException("gatt status $status"))
                } else {
                    onSerialConnectError(IOException("gatt status $status"))
                }
            }
            else -> Log.d(TAG, "unknown connect state $newState $status")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Log.d(TAG, "servicesDiscovered, status $status")
        if (canceled) return
        connectCharacteristics1(gatt)
    }

    private fun connectCharacteristics1(gatt: BluetoothGatt) {
        var sync = true
        writePending = false

        for (gattService in gatt.services) {
            when (gattService.uuid) {
                BLUETOOTH_LE_CC254X_SERVICE -> delegate = Cc245XDelegate()
                BLUETOOTH_LE_MICROCHIP_SERVICE -> delegate = MicrochipDelegate()
                BLUETOOTH_LE_NRF_SERVICE -> delegate = NrfDelegate()
                BLUETOOTH_LE_TIO_SERVICE -> delegate = TelitDelegate()
            }

            delegate?.let {
                sync = it.connectCharacteristics(gattService)
                return@let
            }
        }

        if (canceled) return

        if (delegate == null || readCharacteristic == null || writeCharacteristic == null) {
            for (gattService in gatt.services) {
                Log.d(TAG, "service ${gattService.uuid}")
                for (characteristic in gattService.characteristics) {
                    Log.d(TAG, "characteristic ${characteristic.uuid}")
                }
            }
            onSerialConnectError(IOException("no serial profile found"))
            return
        }

        if (sync) {
            connectCharacteristics2(gatt)
        }
    }

    private fun connectCharacteristics2(gatt: BluetoothGatt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "request max MTU")
            if (!gatt.requestMtu(MAX_MTU)) {
                onSerialConnectError(IOException("request MTU failed"))
            }
        } else {
            connectCharacteristics3(gatt)
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(TAG, "mtu size $mtu, status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            payloadSize = mtu - 3
            Log.d(TAG, "payload size $payloadSize")
        }
        connectCharacteristics3(gatt)
    }

    private fun connectCharacteristics3(gatt: BluetoothGatt) {
        val writeProperties = writeCharacteristic?.properties ?: 0
        if ((writeProperties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            onSerialConnectError(IOException("write characteristic not writable"))
            return
        }

        readCharacteristic?.let { readChar ->
            if (!gatt.setCharacteristicNotification(readChar, true)) {
                onSerialConnectError(IOException("no notification for read characteristic"))
                return
            }

            val readDescriptor = readChar.getDescriptor(BLUETOOTH_LE_CCCD)
            if (readDescriptor == null) {
                onSerialConnectError(IOException("no CCCD descriptor for read characteristic"))
                return
            }

            val readProperties = readChar.properties
            when {
                (readProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 -> {
                    Log.d(TAG, "enable read indication")
                    readDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                (readProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 -> {
                    Log.d(TAG, "enable read notification")
                    readDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                else -> {
                    onSerialConnectError(IOException("no indication/notification for read characteristic ($readProperties)"))
                    return
                }
            }

            Log.d(TAG, "writing read characteristic descriptor")
            if (!gatt.writeDescriptor(readDescriptor)) {
                onSerialConnectError(IOException("read characteristic CCCD descriptor not writable"))
            }
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        delegate?.onDescriptorWrite(gatt, descriptor, status)
        if (canceled) return

        if (descriptor.characteristic == readCharacteristic) {
            Log.d(TAG, "writing read characteristic descriptor finished, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onSerialConnectError(IOException("write descriptor failed"))
            } else {
                onSerialConnect()
                connected = true
                Log.d(TAG, "connected")
            }
        }
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (canceled) return
        delegate?.onCharacteristicChanged(gatt, characteristic)
        if (canceled) return

        if (characteristic == readCharacteristic) {
            val data = readCharacteristic?.value ?: return
            onSerialRead(data)
            Log.d(TAG, "read, len=${data.size}")
        }
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (canceled || !connected || writeCharacteristic == null) {
            throw IOException("not connected")
        }

        var data0: ByteArray? = null
        synchronized(writeBuffer) {
            val firstChunk = if (data.size <= payloadSize) {
                data
            } else {
                data.copyOfRange(0, payloadSize)
            }

            if (!writePending && writeBuffer.isEmpty() && delegate?.canWrite() == true) {
                writePending = true
                data0 = firstChunk
            } else {
                writeBuffer.add(firstChunk)
                Log.d(TAG, "write queued, len=${firstChunk.size}")
            }

            if (data.size > payloadSize) {
                var i = 1
                while (i < (data.size + payloadSize - 1) / payloadSize) {
                    val from = i * payloadSize
                    val to = minOf(from + payloadSize, data.size)
                    writeBuffer.add(data.copyOfRange(from, to))
                    Log.d(TAG, "write queued, len=${to - from}")
                    i++
                }
            }
        }

        data0?.let { dataToWrite ->
            writeCharacteristic?.value = dataToWrite
            if (gatt?.writeCharacteristic(writeCharacteristic) != true) {
                onSerialIoError(IOException("write failed"))
            } else {
                Log.d(TAG, "write started, len=${dataToWrite.size}")
            }
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (canceled || !connected || writeCharacteristic == null) return

        if (status != BluetoothGatt.GATT_SUCCESS) {
            onSerialIoError(IOException("write failed"))
            return
        }

        delegate?.onCharacteristicWrite(gatt, characteristic, status)
        if (canceled) return

        if (characteristic == writeCharacteristic) {
            Log.d(TAG, "write finished, status=$status")
            writeNext()
        }
    }

    private fun writeNext() {
        val data: ByteArray?
        synchronized(writeBuffer) {
            data = if (writeBuffer.isNotEmpty() && delegate?.canWrite() == true) {
                writePending = true
                writeBuffer.removeAt(0)
            } else {
                writePending = false
                null
            }
        }

        data?.let { dataToWrite ->
            writeCharacteristic?.value = dataToWrite
            if (gatt?.writeCharacteristic(writeCharacteristic) != true) {
                onSerialIoError(IOException("write failed"))
            } else {
                Log.d(TAG, "write started, len=${dataToWrite.size}")
            }
        }
    }

    // SerialListener methods
    private fun onSerialConnect() {
        listener?.onSerialConnect()
    }

    private fun onSerialConnectError(e: Exception) {
        canceled = true
        listener?.onSerialConnectError(e)
    }

    private fun onSerialRead(data: ByteArray) {
        listener?.onSerialRead(data)
    }

    private fun onSerialIoError(e: Exception) {
        writePending = false
        canceled = true
        listener?.onSerialIoError(e)
    }

    // Device Delegates
    private inner class Cc245XDelegate : DeviceDelegate() {
        override fun connectCharacteristics(gattService: BluetoothGattService): Boolean {
            Log.d(TAG, "service cc254x uart")
            readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW)
            writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW)
            return true
        }
    }

    private inner class MicrochipDelegate : DeviceDelegate() {
        override fun connectCharacteristics(gattService: BluetoothGattService): Boolean {
            Log.d(TAG, "service microchip uart")
            readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_RW)
            writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_W)
                ?: gattService.getCharacteristic(BLUETOOTH_LE_MICROCHIP_CHAR_RW)
            return true
        }
    }

    private inner class NrfDelegate : DeviceDelegate() {
        override fun connectCharacteristics(gattService: BluetoothGattService): Boolean {
            Log.d(TAG, "service nrf uart")
            val rw2 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW2)
            val rw3 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW3)

            if (rw2 != null && rw3 != null) {
                val rw2prop = rw2.properties
                val rw3prop = rw3.properties
                val rw2write = (rw2prop and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                val rw3write = (rw3prop and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

                Log.d(TAG, "characteristic properties $rw2prop/$rw3prop")

                when {
                    rw2write && rw3write -> {
                        onSerialConnectError(IOException("multiple write characteristics ($rw2prop/$rw3prop)"))
                    }
                    rw2write -> {
                        writeCharacteristic = rw2
                        readCharacteristic = rw3
                    }
                    rw3write -> {
                        writeCharacteristic = rw3
                        readCharacteristic = rw2
                    }
                    else -> {
                        onSerialConnectError(IOException("no write characteristic ($rw2prop/$rw3prop)"))
                    }
                }
            }
            return true
        }
    }

    private inner class TelitDelegate : DeviceDelegate() {
        private var readCreditsCharacteristic: BluetoothGattCharacteristic? = null
        private var writeCreditsCharacteristic: BluetoothGattCharacteristic? = null
        private var readCredits = 0
        private var writeCredits = 0

        override fun connectCharacteristics(gattService: BluetoothGattService): Boolean {
            Log.d(TAG, "service telit tio 2.0")
            readCredits = 0
            writeCredits = 0

            readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_RX)
            writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_TX)
            readCreditsCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_RX_CREDITS)
            writeCreditsCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_TIO_CHAR_TX_CREDITS)

            if (readCharacteristic == null) {
                onSerialConnectError(IOException("read characteristic not found"))
                return false
            }
            if (writeCharacteristic == null) {
                onSerialConnectError(IOException("write characteristic not found"))
                return false
            }
            if (readCreditsCharacteristic == null) {
                onSerialConnectError(IOException("read credits characteristic not found"))
                return false
            }
            if (writeCreditsCharacteristic == null) {
                onSerialConnectError(IOException("write credits characteristic not found"))
                return false
            }

            readCreditsCharacteristic?.let { creditsChar ->
                if (!gatt!!.setCharacteristicNotification(creditsChar, true)) {
                    onSerialConnectError(IOException("no notification for read credits characteristic"))
                    return false
                }

                val readCreditsDescriptor = creditsChar.getDescriptor(BLUETOOTH_LE_CCCD)
                if (readCreditsDescriptor == null) {
                    onSerialConnectError(IOException("no CCCD descriptor for read credits characteristic"))
                    return false
                }

                readCreditsDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                Log.d(TAG, "writing read credits characteristic descriptor")
                if (!gatt!!.writeDescriptor(readCreditsDescriptor)) {
                    onSerialConnectError(IOException("read credits characteristic CCCD descriptor not writable"))
                    return false
                }
            }

            Log.d(TAG, "writing read credits characteristic descriptor")
            return false
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            when (descriptor.characteristic) {
                readCreditsCharacteristic -> {
                    Log.d(TAG, "writing read credits characteristic descriptor finished, status=$status")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        onSerialConnectError(IOException("write credits descriptor failed"))
                    } else {
                        connectCharacteristics2(gatt)
                    }
                }
                readCharacteristic -> {
                    Log.d(TAG, "writing read characteristic descriptor finished, status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        readCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        writeCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        grantReadCredits()
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic) {
                readCreditsCharacteristic -> {
                    val newCredits = readCreditsCharacteristic?.value?.get(0)?.toInt() ?: 0
                    synchronized(writeBuffer) {
                        writeCredits += newCredits
                    }
                    Log.d(TAG, "got write credits +$newCredits =$writeCredits")

                    if (!writePending && writeBuffer.isNotEmpty()) {
                        Log.d(TAG, "resume blocked write")
                        writeNext()
                    }
                }
                readCharacteristic -> {
                    grantReadCredits()
                    Log.d(TAG, "read, credits=$readCredits")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            when (characteristic) {
                writeCharacteristic -> {
                    synchronized(writeBuffer) {
                        if (writeCredits > 0) writeCredits -= 1
                    }
                    Log.d(TAG, "write finished, credits=$writeCredits")
                }
                writeCreditsCharacteristic -> {
                    Log.d(TAG, "write credits finished, status=$status")
                }
            }
        }

        override fun canWrite(): Boolean {
            return if (writeCredits > 0) {
                true
            } else {
                Log.d(TAG, "no write credits")
                false
            }
        }

        override fun disconnect() {
            readCreditsCharacteristic = null
            writeCreditsCharacteristic = null
        }

        private fun grantReadCredits() {
            val minReadCredits = 16
            val maxReadCredits = 64

            if (readCredits > 0) readCredits -= 1

            if (readCredits <= minReadCredits) {
                val newCredits = maxReadCredits - readCredits
                readCredits += newCredits
                val data = byteArrayOf(newCredits.toByte())
                Log.d(TAG, "grant read credits +$newCredits =$readCredits")

                writeCreditsCharacteristic?.value = data
                if (gatt?.writeCharacteristic(writeCreditsCharacteristic) != true) {
                    if (connected) {
                        onSerialIoError(IOException("write read credits failed"))
                    } else {
                        onSerialConnectError(IOException("write read credits failed"))
                    }
                }
            }
        }
    }
}