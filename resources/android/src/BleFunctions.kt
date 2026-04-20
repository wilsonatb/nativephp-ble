package com.wilsonatb.plugins.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeError
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object BleFunctions {
    private const val TAG = "BleFunctions"
    private const val BLE_PERMISSION_REQUEST_CODE = 3100
    private const val BLE_SCAN_COMPLETED_EVENT = "Nativephp\\Ble\\Events\\BleScanCompleted"
    private const val BLE_DEVICE_CONNECTED_EVENT = "Nativephp\\Ble\\Events\\BleDeviceConnected"
    private const val BLE_CHARACTERISTIC_READ_EVENT = "Nativephp\\Ble\\Events\\BleCharacteristicRead"
    private const val BLE_CHARACTERISTIC_WRITTEN_EVENT =
        "Nativephp\\Ble\\Events\\BleCharacteristicWritten"
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val connectedGatts = mutableMapOf<String, BluetoothGatt>()
    private val readyDevices = mutableSetOf<String>()

    private fun isPermissionGranted(activity: FragmentActivity, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(activity: FragmentActivity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isPermissionGranted(activity, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            isPermissionGranted(activity, Manifest.permission.BLUETOOTH)
        }
    }

    private fun dispatchEvent(activity: FragmentActivity, eventClass: String, payload: JSONObject) {
        Handler(Looper.getMainLooper()).post {
            NativeActionCoordinator.dispatchEvent(activity, eventClass, payload.toString())
        }
    }

    private fun findService(gatt: BluetoothGatt, serviceUuid: String): BluetoothGattService? {
        val normalizedUuid = parseUuid(serviceUuid) ?: return null
        return gatt.services.firstOrNull { it.uuid == normalizedUuid }
    }

    private fun findCharacteristic(
        service: BluetoothGattService,
        characteristicUuid: String
    ): BluetoothGattCharacteristic? {
        val normalizedUuid = parseUuid(characteristicUuid) ?: return null
        return service.characteristics.firstOrNull { it.uuid == normalizedUuid }
    }

    private fun parseUuid(input: String): UUID? {
        val value = input.trim().lowercase()
        if (value.isEmpty()) {
            return null
        }

        return when {
            Regex("^[0-9a-f]{4}$").matches(value) -> UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")
            Regex("^[0-9a-f]{8}$").matches(value) -> UUID.fromString("$value-0000-1000-8000-00805f9b34fb")
            Regex("^[0-9a-f]{32}$").matches(value) -> UUID.fromString(
                "${value.substring(0, 8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-${value.substring(16, 20)}-${value.substring(20, 32)}"
            )

            else -> runCatching { UUID.fromString(value) }.getOrNull()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(value: String): ByteArray {
        val normalized = value.trim().replace(" ", "").replace(":", "")
        if (normalized.isEmpty()) {
            return ByteArray(0)
        }
        require(normalized.length % 2 == 0) { "Hex value must contain an even number of characters" }
        return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun gattStatusText(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            8 -> "GATT_CONN_TIMEOUT"
            19 -> "GATT_CONN_TERMINATE_PEER_USER"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
            133 -> "GATT_ERROR"
            else -> "GATT_STATUS_$status"
        }
    }

    class ScanDevices(private val activity: FragmentActivity) : BridgeFunction {
        private var bluetoothAdapter: BluetoothAdapter? = null
        private var bluetoothLeScanner: BluetoothLeScanner? = null
        private var scanning = false
        private val handler = Handler(Looper.getMainLooper())
        private val scanResults = mutableListOf<Map<String, Any>>()
        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                val device = result.device
                val scanRecord = result.scanRecord
                val deviceInfo = mapOf(
                    "id" to device.address,
                    "name" to (device.name ?: "Unknown"),
                    "address" to device.address,
                    "rssi" to result.rssi,
                    "connectable" to result.isConnectable,
                    "txPower" to if (result.txPower != Int.MIN_VALUE) result.txPower else "N/A",
                    "deviceType" to when (device.type) {
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                        BluetoothDevice.DEVICE_TYPE_LE -> "le"
                        else -> "unknown"
                    },
                    "serviceUuids" to (scanRecord?.serviceUuids?.map { it.toString() } ?: emptyList<String>())
                )
                scanResults.removeAll { existing -> existing["id"] == device.address }
                scanResults.add(deviceInfo)
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                scanning = false
                Log.e(TAG, "BLE scan failed. errorCode=$errorCode")
                dispatchEvent(
                    activity,
                    BLE_SCAN_COMPLETED_EVENT,
                    JSONObject().apply {
                        put("devices", JSONArray())
                        put("error", "BLE scan failed with error code: $errorCode")
                    }
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val missingPermissions = missingPermissions()
            if (missingPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    activity,
                    missingPermissions.toTypedArray(),
                    BLE_PERMISSION_REQUEST_CODE
                )

                return BridgeResponse.success(
                    mapOf(
                        "status" to "permission_requested",
                        "missingPermissions" to missingPermissions
                    )
                )
            }

            val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                return BridgeResponse.error(
                    BridgeError.ExecutionFailed("Bluetooth is not enabled")
                )
            }

            bluetoothLeScanner = bluetoothAdapter!!.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                return BridgeResponse.error(
                    BridgeError.ExecutionFailed("BLE scanner not available")
                )
            }

            if (scanning) {
                return BridgeResponse.success(mapOf("status" to "already_scanning"))
            }

            val scanDuration = (parameters["duration"] as? Number)?.toLong() ?: 5000L
            scanResults.clear()
            scanning = true
            bluetoothLeScanner!!.startScan(scanCallback)

            handler.postDelayed({
                if (scanning) {
                    bluetoothLeScanner!!.stopScan(scanCallback)
                    scanning = false

                    val devices = JSONArray().apply {
                        scanResults.forEach { result -> put(JSONObject(result)) }
                    }

                    dispatchEvent(
                        activity,
                        BLE_SCAN_COMPLETED_EVENT,
                        JSONObject().apply {
                            put("devices", devices)
                            put("error", JSONObject.NULL)
                        }
                    )
                }
            }, scanDuration)

            return BridgeResponse.success(mapOf("status" to "scanning_started", "duration" to scanDuration))
        }

        private fun missingPermissions(): List<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ).filterNot { isPermissionGranted(activity, it) }
            } else {
                listOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ).filterNot { isPermissionGranted(activity, it) }
            }
        }
    }

    class ConnectToDevice(private val activity: FragmentActivity) : BridgeFunction {
        @SuppressLint("MissingPermission")
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val deviceId = parameters["deviceId"] as? String ?: ""
            if (deviceId.isEmpty()) {
                return BridgeResponse.error(BridgeError.InvalidParameters("Device ID is required"))
            }

            if (!hasConnectPermission(activity)) {
                return BridgeResponse.error(
                    BridgeError.PermissionRequired("Bluetooth connect permission required")
                )
            }

            val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                return BridgeResponse.error(BridgeError.ExecutionFailed("Bluetooth is not enabled"))
            }

            val device = try {
                bluetoothAdapter.getRemoteDevice(deviceId)
            } catch (_: IllegalArgumentException) {
                return BridgeResponse.error(BridgeError.InvalidParameters("Invalid device ID format"))
            }

            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    super.onConnectionStateChange(gatt, status, newState)

                    if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                        val statusText = gattStatusText(status)
                        Log.e(TAG, "Connection failed for $deviceId (status=$statusText)")
                        connectedGatts.remove(deviceId)
                        readyDevices.remove(deviceId)
                        gatt.close()
                        dispatchEvent(
                            activity,
                            BLE_DEVICE_CONNECTED_EVENT,
                            JSONObject().apply {
                                put("deviceId", deviceId)
                                put("connected", false)
                                put("error", "Connection failed: $statusText")
                            }
                        )
                        return
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Connected to BLE device $deviceId")
                            connectedGatts[deviceId] = gatt
                            readyDevices.remove(deviceId)
                            gatt.discoverServices()
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.w(TAG, "Disconnected from BLE device $deviceId (status=$status)")
                            connectedGatts.remove(deviceId)
                            readyDevices.remove(deviceId)
                            gatt.close()
                            dispatchEvent(
                                activity,
                                BLE_DEVICE_CONNECTED_EVENT,
                                JSONObject().apply {
                                    put("deviceId", deviceId)
                                    put("connected", false)
                                    put("error", "Disconnected (${gattStatusText(status)})")
                                }
                            )
                        }

                        else -> {
                            Log.d(TAG, "BLE state changed for $deviceId: newState=$newState status=$status")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    super.onServicesDiscovered(gatt, status)

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        readyDevices.add(deviceId)
                        dispatchEvent(
                            activity,
                            BLE_DEVICE_CONNECTED_EVENT,
                            JSONObject().apply {
                                put("deviceId", deviceId)
                                put("connected", true)
                                put("error", JSONObject.NULL)
                            }
                        )

                        return
                    }

                    readyDevices.remove(deviceId)
                    dispatchEvent(
                        activity,
                        BLE_DEVICE_CONNECTED_EVENT,
                        JSONObject().apply {
                            put("deviceId", deviceId)
                            put("connected", false)
                            put("error", "Service discovery failed: ${gattStatusText(status)}")
                        }
                    )
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    super.onCharacteristicRead(gatt, characteristic, status)
                    val value = characteristic.value ?: ByteArray(0)
                    val error = if (status == BluetoothGatt.GATT_SUCCESS) {
                        JSONObject.NULL
                    } else {
                        "Characteristic read failed: ${gattStatusText(status)}"
                    }

                    dispatchEvent(
                        activity,
                        BLE_CHARACTERISTIC_READ_EVENT,
                        JSONObject().apply {
                            put("deviceId", deviceId)
                            put("serviceUuid", characteristic.service.uuid.toString())
                            put("characteristicUuid", characteristic.uuid.toString())
                            put("value", bytesToHex(value))
                            put("error", error)
                        }
                    )
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    super.onCharacteristicChanged(gatt, characteristic)
                    dispatchEvent(
                        activity,
                        BLE_CHARACTERISTIC_READ_EVENT,
                        JSONObject().apply {
                            put("deviceId", deviceId)
                            put("serviceUuid", characteristic.service.uuid.toString())
                            put("characteristicUuid", characteristic.uuid.toString())
                            put("value", bytesToHex(characteristic.value ?: ByteArray(0)))
                            put("error", JSONObject.NULL)
                        }
                    )
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    super.onCharacteristicWrite(gatt, characteristic, status)

                    val error = if (status == BluetoothGatt.GATT_SUCCESS) {
                        JSONObject.NULL
                    } else {
                        "Characteristic write failed: ${gattStatusText(status)}"
                    }

                    dispatchEvent(
                        activity,
                        BLE_CHARACTERISTIC_WRITTEN_EVENT,
                        JSONObject().apply {
                            put("deviceId", deviceId)
                            put("serviceUuid", characteristic.service.uuid.toString())
                            put("characteristicUuid", characteristic.uuid.toString())
                            put("value", bytesToHex(characteristic.value ?: ByteArray(0)))
                            put("error", error)
                        }
                    )
                }
            }

            connectedGatts.remove(deviceId)?.let { previousGatt ->
                previousGatt.disconnect()
                previousGatt.close()
            }
            readyDevices.remove(deviceId)

            val gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                return BridgeResponse.error(BridgeError.ExecutionFailed("Failed to start GATT connection"))
            }
            connectedGatts[deviceId] = gatt

            return BridgeResponse.success(
                mapOf(
                    "status" to "connecting_started",
                    "connected" to false,
                    "deviceId" to deviceId
                )
            )
        }
    }

    class DisconnectDevice(private val activity: FragmentActivity) : BridgeFunction {
        @SuppressLint("MissingPermission")
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val deviceId = parameters["deviceId"] as? String ?: ""
            if (deviceId.isEmpty()) {
                return BridgeResponse.error(BridgeError.InvalidParameters("Device ID is required"))
            }

            if (!hasConnectPermission(activity)) {
                return BridgeResponse.error(
                    BridgeError.PermissionRequired("Bluetooth connect permission required")
                )
            }

            val gatt = connectedGatts.remove(deviceId)
                ?: return BridgeResponse.error(BridgeError.ExecutionFailed("Device is not connected"))
            readyDevices.remove(deviceId)

            gatt.disconnect()
            gatt.close()

            dispatchEvent(
                activity,
                BLE_DEVICE_CONNECTED_EVENT,
                JSONObject().apply {
                    put("deviceId", deviceId)
                    put("connected", false)
                    put("error", JSONObject.NULL)
                }
            )

            return BridgeResponse.success(mapOf("disconnected" to true, "deviceId" to deviceId))
        }
    }

    class ReadCharacteristic(private val activity: FragmentActivity) : BridgeFunction {
        @SuppressLint("MissingPermission")
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val deviceId = parameters["deviceId"] as? String ?: ""
            val serviceUuid = parameters["serviceUuid"] as? String ?: ""
            val characteristicUuid = parameters["characteristicUuid"] as? String ?: ""

            if (deviceId.isEmpty() || serviceUuid.isEmpty() || characteristicUuid.isEmpty()) {
                return BridgeResponse.error(
                    BridgeError.InvalidParameters("deviceId, serviceUuid and characteristicUuid are required")
                )
            }

            if (!hasConnectPermission(activity)) {
                return BridgeResponse.error(
                    BridgeError.PermissionRequired("Bluetooth connect permission required")
                )
            }

            val gatt = connectedGatts[deviceId]
                ?: return BridgeResponse.error(BridgeError.ExecutionFailed("Device is not connected"))

            if (!readyDevices.contains(deviceId)) {
                return BridgeResponse.error(
                    BridgeError.ExecutionFailed("Services not discovered yet. Wait until connection is ready.")
                )
            }

            val service = findService(gatt, serviceUuid)
                ?: return BridgeResponse.error(BridgeError.InvalidParameters("Service not found on device"))

            val characteristic = findCharacteristic(service, characteristicUuid)
                ?: return BridgeResponse.error(BridgeError.InvalidParameters("Characteristic not found on service"))

            val started = gatt.readCharacteristic(characteristic)
            if (!started) {
                return BridgeResponse.error(BridgeError.ExecutionFailed("Failed to start characteristic read"))
            }

            return BridgeResponse.success(
                mapOf(
                    "deviceId" to deviceId,
                    "serviceUuid" to serviceUuid,
                    "characteristicUuid" to characteristicUuid,
                    "status" to "read_started"
                )
            )
        }
    }

    class WriteCharacteristic(private val activity: FragmentActivity) : BridgeFunction {
        @SuppressLint("MissingPermission")
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val deviceId = parameters["deviceId"] as? String ?: ""
            val serviceUuid = parameters["serviceUuid"] as? String ?: ""
            val characteristicUuid = parameters["characteristicUuid"] as? String ?: ""
            val value = parameters["value"] as? String ?: ""
            val withoutResponse = parameters["withoutResponse"] as? Boolean ?: false

            if (deviceId.isEmpty() || serviceUuid.isEmpty() || characteristicUuid.isEmpty()) {
                return BridgeResponse.error(
                    BridgeError.InvalidParameters("deviceId, serviceUuid and characteristicUuid are required")
                )
            }

            if (!hasConnectPermission(activity)) {
                return BridgeResponse.error(
                    BridgeError.PermissionRequired("Bluetooth connect permission required")
                )
            }

            val gatt = connectedGatts[deviceId]
                ?: return BridgeResponse.error(BridgeError.ExecutionFailed("Device is not connected"))

            if (!readyDevices.contains(deviceId)) {
                return BridgeResponse.error(
                    BridgeError.ExecutionFailed("Services not discovered yet. Wait until connection is ready.")
                )
            }

            val service = findService(gatt, serviceUuid)
                ?: return BridgeResponse.error(BridgeError.InvalidParameters("Service not found on device"))

            val characteristic = findCharacteristic(service, characteristicUuid)
                ?: return BridgeResponse.error(BridgeError.InvalidParameters("Characteristic not found on service"))

            val payload = try {
                hexToBytes(value)
            } catch (_: Exception) {
                return BridgeResponse.error(BridgeError.InvalidParameters("Value must be a valid hex string"))
            }

            characteristic.writeType = if (withoutResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            characteristic.value = payload

            val started = gatt.writeCharacteristic(characteristic)
            if (!started) {
                return BridgeResponse.error(BridgeError.ExecutionFailed("Failed to start characteristic write"))
            }

            return BridgeResponse.success(
                mapOf(
                    "deviceId" to deviceId,
                    "serviceUuid" to serviceUuid,
                    "characteristicUuid" to characteristicUuid,
                    "written" to true
                )
            )
        }
    }

    class SetNotification(private val activity: FragmentActivity) : BridgeFunction {
        @SuppressLint("MissingPermission")
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val deviceId = parameters["deviceId"] as? String ?: ""
            val serviceUuid = parameters["serviceUuid"] as? String ?: ""
            val characteristicUuid = parameters["characteristicUuid"] as? String ?: ""
            val enable = parameters["enable"] as? Boolean ?: false

            if (deviceId.isEmpty() || serviceUuid.isEmpty() || characteristicUuid.isEmpty()) {
                return BridgeResponse.error(
                    BridgeError.InvalidParameters("deviceId, serviceUuid and characteristicUuid are required")
                )
            }

            if (!hasConnectPermission(activity)) {
                return BridgeResponse.error(
                    BridgeError.PermissionRequired("Bluetooth connect permission required")
                )
            }

            val gatt = connectedGatts[deviceId]
                ?: return BridgeResponse.error(BridgeError.ExecutionFailed("Device is not connected"))

            if (!readyDevices.contains(deviceId)) {
                return BridgeResponse.error(
                    BridgeError.ExecutionFailed("Services not discovered yet. Wait until connection is ready.")
                )
            }

            val service = findService(gatt, serviceUuid)
                ?: return BridgeResponse.error(BridgeError.InvalidParameters("Service not found on device"))

            val characteristic = findCharacteristic(service, characteristicUuid)
                ?: return BridgeResponse.error(BridgeError.InvalidParameters("Characteristic not found on service"))

            val enabled = gatt.setCharacteristicNotification(characteristic, enable)
            if (!enabled) {
                return BridgeResponse.error(
                    BridgeError.ExecutionFailed("Failed to set local characteristic notification state")
                )
            }

            val cccd = characteristic.getDescriptor(cccdUuid)
                ?: return BridgeResponse.error(
                    BridgeError.ExecutionFailed("CCCD descriptor not found; characteristic may not support notifications")
                )

            cccd.value = if (enable) {
                if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            val descriptorWriteStarted = gatt.writeDescriptor(cccd)
            if (!descriptorWriteStarted) {
                return BridgeResponse.error(BridgeError.ExecutionFailed("Failed to write CCCD descriptor"))
            }

            return BridgeResponse.success(
                mapOf(
                    "deviceId" to deviceId,
                    "serviceUuid" to serviceUuid,
                    "characteristicUuid" to characteristicUuid,
                    "notificationEnabled" to enable
                )
            )
        }
    }

}
