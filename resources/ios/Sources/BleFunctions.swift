import CoreBluetooth
import Foundation

private enum BleConstants {
    static let bleScanCompletedEvent = "Nativephp\\Ble\\Events\\BleScanCompleted"
    static let bleDeviceConnectedEvent = "Nativephp\\Ble\\Events\\BleDeviceConnected"
    static let bleCharacteristicReadEvent = "Nativephp\\Ble\\Events\\BleCharacteristicRead"
    static let bleCharacteristicWrittenEvent = "Nativephp\\Ble\\Events\\BleCharacteristicWritten"
}

private final class BleManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let shared = BleManager()

    private let cccdUuid = CBUUID(string: "2902")
    private let queue = DispatchQueue.main

    private var centralManager: CBCentralManager!
    private var isScanning = false
    private var scanCompletionWorkItem: DispatchWorkItem?

    private var discoveredDevices: [String: [String: Any]] = [:]
    private var discoveredPeripherals: [String: CBPeripheral] = [:]

    private(set) var connectedGatts: [String: CBPeripheral] = [:]
    private(set) var readyDevices: Set<String> = []

    private var pendingServiceCharacteristicDiscovery: [String: Set<CBUUID>] = [:]
    private var pendingWriteValues: [String: String] = [:]

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: queue)
    }

    private func invalidParameters(_ message: String) -> [String: Any] {
        BridgeResponse.error(code: "invalid_parameters", message: message)
    }

    private func executionFailed(_ message: String) -> [String: Any] {
        BridgeResponse.error(code: "execution_failed", message: message)
    }

    func scanDevices(duration: Int) -> [String: Any] {
        guard isBluetoothReady() else {
            return executionFailed("Bluetooth is not enabled")
        }

        if isScanning {
            return BridgeResponse.success(data: ["status": "already_scanning"])
        }

        let scanDuration = max(duration, 0)
        isScanning = true
        discoveredDevices.removeAll()
        discoveredPeripherals.removeAll()

        centralManager.scanForPeripherals(withServices: nil, options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false
        ])

        scanCompletionWorkItem?.cancel()
        let completion = DispatchWorkItem { [weak self] in
            self?.finishScan(error: nil)
        }
        scanCompletionWorkItem = completion
        queue.asyncAfter(deadline: .now() + .milliseconds(scanDuration), execute: completion)

        return BridgeResponse.success(data: [
            "status": "scanning_started",
            "duration": scanDuration
        ])
    }

    func connectToDevice(deviceId: String) -> [String: Any] {
        guard !deviceId.isEmpty else {
            return invalidParameters("Device ID is required")
        }

        guard isBluetoothReady() else {
            return executionFailed("Bluetooth is not enabled")
        }

        guard let peripheral = peripheralFor(deviceId: deviceId) else {
            return invalidParameters("Invalid device ID format")
        }

        if let previous = connectedGatts.removeValue(forKey: deviceId) {
            readyDevices.remove(deviceId)
            previous.delegate = nil
            centralManager.cancelPeripheralConnection(previous)
        }

        readyDevices.remove(deviceId)
        pendingServiceCharacteristicDiscovery.removeValue(forKey: deviceId)

        peripheral.delegate = self
        connectedGatts[deviceId] = peripheral
        centralManager.connect(peripheral, options: nil)

        return BridgeResponse.success(data: [
            "status": "connecting_started",
            "connected": false,
            "deviceId": deviceId
        ])
    }

    func disconnectDevice(deviceId: String) -> [String: Any] {
        guard !deviceId.isEmpty else {
            return invalidParameters("Device ID is required")
        }

        guard let peripheral = connectedGatts.removeValue(forKey: deviceId) else {
            return executionFailed("Device is not connected")
        }

        readyDevices.remove(deviceId)
        pendingServiceCharacteristicDiscovery.removeValue(forKey: deviceId)

        peripheral.delegate = nil
        centralManager.cancelPeripheralConnection(peripheral)

        dispatchEvent(BleConstants.bleDeviceConnectedEvent, payload: [
            "deviceId": deviceId,
            "connected": false,
            "error": NSNull()
        ])

        return BridgeResponse.success(data: [
            "disconnected": true,
            "deviceId": deviceId
        ])
    }

    func readCharacteristic(deviceId: String, serviceUuid: String, characteristicUuid: String) -> [String: Any] {
        guard !deviceId.isEmpty, !serviceUuid.isEmpty, !characteristicUuid.isEmpty else {
            return invalidParameters("deviceId, serviceUuid and characteristicUuid are required")
        }

        guard let peripheral = connectedGatts[deviceId] else {
            return executionFailed("Device is not connected")
        }

        guard readyDevices.contains(deviceId) else {
            return executionFailed("Services not discovered yet. Wait until connection is ready.")
        }

        guard let service = findService(in: peripheral, serviceUuid: serviceUuid) else {
            return invalidParameters("Service not found on device")
        }

        guard let characteristic = findCharacteristic(in: service, characteristicUuid: characteristicUuid) else {
            return invalidParameters("Characteristic not found on service")
        }

        peripheral.readValue(for: characteristic)

        return BridgeResponse.success(data: [
            "deviceId": deviceId,
            "serviceUuid": serviceUuid,
            "characteristicUuid": characteristicUuid,
            "status": "read_started"
        ])
    }

    func writeCharacteristic(
        deviceId: String,
        serviceUuid: String,
        characteristicUuid: String,
        value: String,
        withoutResponse: Bool
    ) -> [String: Any] {
        guard !deviceId.isEmpty, !serviceUuid.isEmpty, !characteristicUuid.isEmpty else {
            return invalidParameters("deviceId, serviceUuid and characteristicUuid are required")
        }

        guard let peripheral = connectedGatts[deviceId] else {
            return executionFailed("Device is not connected")
        }

        guard readyDevices.contains(deviceId) else {
            return executionFailed("Services not discovered yet. Wait until connection is ready.")
        }

        guard let service = findService(in: peripheral, serviceUuid: serviceUuid) else {
            return invalidParameters("Service not found on device")
        }

        guard let characteristic = findCharacteristic(in: service, characteristicUuid: characteristicUuid) else {
            return invalidParameters("Characteristic not found on service")
        }

        guard let payload = hexToData(value) else {
            return invalidParameters("Value must be a valid hex string")
        }

        let writeType: CBCharacteristicWriteType = withoutResponse ? .withoutResponse : .withResponse
        let writeKey = writeStorageKey(deviceId: deviceId, characteristic: characteristic)
        pendingWriteValues[writeKey] = dataToHex(payload)
        peripheral.writeValue(payload, for: characteristic, type: writeType)

        return BridgeResponse.success(data: [
            "deviceId": deviceId,
            "serviceUuid": serviceUuid,
            "characteristicUuid": characteristicUuid,
            "written": true
        ])
    }

    func setNotification(
        deviceId: String,
        serviceUuid: String,
        characteristicUuid: String,
        enable: Bool
    ) -> [String: Any] {
        guard !deviceId.isEmpty, !serviceUuid.isEmpty, !characteristicUuid.isEmpty else {
            return invalidParameters("deviceId, serviceUuid and characteristicUuid are required")
        }

        guard let peripheral = connectedGatts[deviceId] else {
            return executionFailed("Device is not connected")
        }

        guard readyDevices.contains(deviceId) else {
            return executionFailed("Services not discovered yet. Wait until connection is ready.")
        }

        guard let service = findService(in: peripheral, serviceUuid: serviceUuid) else {
            return invalidParameters("Service not found on device")
        }

        guard let characteristic = findCharacteristic(in: service, characteristicUuid: characteristicUuid) else {
            return invalidParameters("Characteristic not found on service")
        }

        let supportsNotify = characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate)
        if !supportsNotify {
            return executionFailed("CCCD descriptor not found; characteristic may not support notifications")
        }

        peripheral.setNotifyValue(enable, for: characteristic)

        if let descriptor = characteristic.descriptors?.first(where: { $0.uuid == cccdUuid }) {
            let valueData: Data
            if enable {
                if characteristic.properties.contains(.indicate) {
                    valueData = Data([0x02, 0x00])
                } else {
                    valueData = Data([0x01, 0x00])
                }
            } else {
                valueData = Data([0x00, 0x00])
            }

            peripheral.writeValue(valueData, for: descriptor)
        }

        return BridgeResponse.success(data: [
            "deviceId": deviceId,
            "serviceUuid": serviceUuid,
            "characteristicUuid": characteristicUuid,
            "notificationEnabled": enable
        ])
    }

    private func isBluetoothReady() -> Bool {
        return centralManager.state == .poweredOn
    }

    private func finishScan(error: String?) {
        if isScanning {
            centralManager.stopScan()
            isScanning = false
        }

        let devices = discoveredDevices.values.sorted {
            ($0["id"] as? String ?? "") < ($1["id"] as? String ?? "")
        }

        let payload: [String: Any] = [
            "devices": devices,
            "error": error ?? NSNull()
        ]
        dispatchEvent(BleConstants.bleScanCompletedEvent, payload: payload)
    }

    private func peripheralFor(deviceId: String) -> CBPeripheral? {
        if let known = discoveredPeripherals[deviceId] {
            return known
        }

        guard let uuid = UUID(uuidString: deviceId) else {
            return nil
        }

        let known = centralManager.retrievePeripherals(withIdentifiers: [uuid]).first
        if let known {
            discoveredPeripherals[deviceId] = known
        }

        return known
    }

    private func parseUuid(_ input: String) -> CBUUID? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if value.isEmpty {
            return nil
        }

        if value.range(of: "^[0-9a-f]{4}$", options: .regularExpression) != nil {
            return CBUUID(string: "0000\(value)-0000-1000-8000-00805f9b34fb")
        }

        if value.range(of: "^[0-9a-f]{8}$", options: .regularExpression) != nil {
            return CBUUID(string: "\(value)-0000-1000-8000-00805f9b34fb")
        }

        if value.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil {
            let normalized = "\(value.prefix(8))-\(value.dropFirst(8).prefix(4))-\(value.dropFirst(12).prefix(4))-\(value.dropFirst(16).prefix(4))-\(value.dropFirst(20))"
            return CBUUID(string: normalized)
        }

        let dashed = value.replacingOccurrences(of: "{", with: "").replacingOccurrences(of: "}", with: "")
        guard UUID(uuidString: dashed) != nil else {
            return nil
        }
        return CBUUID(string: dashed)
    }

    private func findService(in peripheral: CBPeripheral, serviceUuid: String) -> CBService? {
        guard let targetUuid = parseUuid(serviceUuid) else {
            return nil
        }

        return peripheral.services?.first { $0.uuid == targetUuid }
    }

    private func findCharacteristic(in service: CBService, characteristicUuid: String) -> CBCharacteristic? {
        guard let targetUuid = parseUuid(characteristicUuid) else {
            return nil
        }

        return service.characteristics?.first { $0.uuid == targetUuid }
    }

    private func dataToHex(_ data: Data) -> String {
        return data.map { String(format: "%02x", $0) }.joined()
    }

    private func hexToData(_ value: String) -> Data? {
        let normalized = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: ":", with: "")
            .lowercased()

        if normalized.isEmpty {
            return Data()
        }

        guard normalized.count % 2 == 0 else {
            return nil
        }

        var result = Data(capacity: normalized.count / 2)
        var index = normalized.startIndex

        while index < normalized.endIndex {
            let nextIndex = normalized.index(index, offsetBy: 2)
            let byteString = String(normalized[index..<nextIndex])
            guard let byte = UInt8(byteString, radix: 16) else {
                return nil
            }
            result.append(byte)
            index = nextIndex
        }

        return result
    }

    private func dispatchEvent(_ eventClass: String, payload: [String: Any]) {
        queue.async {
            LaravelBridge.shared.send?(eventClass, payload)
        }
    }

    private func connectionErrorText(_ error: Error?) -> String {
        guard let error else {
            return "GATT_SUCCESS"
        }

        if let cbError = error as? CBError {
            switch cbError.code {
            case .connectionTimeout:
                return "GATT_CONN_TIMEOUT"
            case .peripheralDisconnected:
                return "GATT_CONN_TERMINATE_PEER_USER"
            default:
                return "CB_ERROR_\(cbError.code.rawValue)"
            }
        }

        return error.localizedDescription
    }

    private func characteristicErrorText(_ error: Error?) -> String {
        guard let error else {
            return "GATT_SUCCESS"
        }

        if let cbError = error as? CBError {
            return "CB_ERROR_\(cbError.code.rawValue)"
        }

        return error.localizedDescription
    }

    private func writeStorageKey(deviceId: String, characteristic: CBCharacteristic) -> String {
        return "\(deviceId)|\(characteristic.service?.uuid.uuidString ?? "")|\(characteristic.uuid.uuidString)"
    }

    private func writeStorageKey(deviceId: String, serviceUuid: String, characteristicUuid: String) -> String {
        return "\(deviceId)|\(serviceUuid)|\(characteristicUuid)"
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn && isScanning {
            scanCompletionWorkItem?.cancel()
            finishScan(error: "Bluetooth is not enabled")
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier.uuidString
        discoveredPeripherals[id] = peripheral

        let connectable = advertisementData[CBAdvertisementDataIsConnectable] as? Bool ?? false
        let txPowerValue = advertisementData[CBAdvertisementDataTxPowerLevelKey] as? NSNumber
        let serviceUuids = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []).map { $0.uuidString.lowercased() }

        discoveredDevices[id] = [
            "id": id,
            "name": peripheral.name ?? "Unknown",
            "address": id,
            "rssi": RSSI.intValue,
            "connectable": connectable,
            "txPower": txPowerValue?.intValue ?? "N/A",
            "deviceType": "le",
            "serviceUuids": serviceUuids
        ]
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        connectedGatts.removeValue(forKey: deviceId)
        readyDevices.remove(deviceId)
        pendingServiceCharacteristicDiscovery.removeValue(forKey: deviceId)

        dispatchEvent(BleConstants.bleDeviceConnectedEvent, payload: [
            "deviceId": deviceId,
            "connected": false,
            "error": "Connection failed: \(connectionErrorText(error))"
        ])
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let deviceId = peripheral.identifier.uuidString
        connectedGatts[deviceId] = peripheral
        readyDevices.remove(deviceId)
        pendingServiceCharacteristicDiscovery.removeValue(forKey: deviceId)

        peripheral.delegate = self
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        connectedGatts.removeValue(forKey: deviceId)
        readyDevices.remove(deviceId)
        pendingServiceCharacteristicDiscovery.removeValue(forKey: deviceId)

        dispatchEvent(BleConstants.bleDeviceConnectedEvent, payload: [
            "deviceId": deviceId,
            "connected": false,
            "error": "Disconnected (\(connectionErrorText(error)))"
        ])
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let deviceId = peripheral.identifier.uuidString

        if let error {
            readyDevices.remove(deviceId)
            dispatchEvent(BleConstants.bleDeviceConnectedEvent, payload: [
                "deviceId": deviceId,
                "connected": false,
                "error": "Service discovery failed: \(connectionErrorText(error))"
            ])
            return
        }

        let services = peripheral.services ?? []
        if services.isEmpty {
            readyDevices.insert(deviceId)
            dispatchEvent(BleConstants.bleDeviceConnectedEvent, payload: [
                "deviceId": deviceId,
                "connected": true,
                "error": NSNull()
            ])
            return
        }

        pendingServiceCharacteristicDiscovery[deviceId] = Set(services.map(\.uuid))
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let deviceId = peripheral.identifier.uuidString

        if let error {
            readyDevices.remove(deviceId)
            pendingServiceCharacteristicDiscovery.removeValue(forKey: deviceId)
            dispatchEvent(BleConstants.bleDeviceConnectedEvent, payload: [
                "deviceId": deviceId,
                "connected": false,
                "error": "Service discovery failed: \(connectionErrorText(error))"
            ])
            return
        }

        var pending = pendingServiceCharacteristicDiscovery[deviceId] ?? []
        pending.remove(service.uuid)
        pendingServiceCharacteristicDiscovery[deviceId] = pending

        if pending.isEmpty {
            readyDevices.insert(deviceId)
            pendingServiceCharacteristicDiscovery.removeValue(forKey: deviceId)
            dispatchEvent(BleConstants.bleDeviceConnectedEvent, payload: [
                "deviceId": deviceId,
                "connected": true,
                "error": NSNull()
            ])
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        let payload: [String: Any] = [
            "deviceId": deviceId,
            "serviceUuid": characteristic.service?.uuid.uuidString.lowercased() ?? "",
            "characteristicUuid": characteristic.uuid.uuidString.lowercased(),
            "value": dataToHex(characteristic.value ?? Data()),
            "error": error == nil ? NSNull() : "Characteristic read failed: \(characteristicErrorText(error))"
        ]
        dispatchEvent(BleConstants.bleCharacteristicReadEvent, payload: payload)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        let serviceUuid = characteristic.service?.uuid.uuidString.lowercased() ?? ""
        let characteristicUuid = characteristic.uuid.uuidString.lowercased()
        let key = writeStorageKey(deviceId: deviceId, serviceUuid: serviceUuid, characteristicUuid: characteristicUuid)
        let writtenValue = pendingWriteValues.removeValue(forKey: key) ?? dataToHex(characteristic.value ?? Data())

        let payload: [String: Any] = [
            "deviceId": deviceId,
            "serviceUuid": serviceUuid,
            "characteristicUuid": characteristicUuid,
            "value": writtenValue,
            "error": error == nil ? NSNull() : "Characteristic write failed: \(characteristicErrorText(error))"
        ]
        dispatchEvent(BleConstants.bleCharacteristicWrittenEvent, payload: payload)
    }
}

enum BleFunctions {
    class ScanDevices: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let duration = (parameters["duration"] as? NSNumber)?.intValue ?? 5000
            return BleManager.shared.scanDevices(duration: duration)
        }
    }

    class ConnectToDevice: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let deviceId = parameters["deviceId"] as? String ?? ""
            return BleManager.shared.connectToDevice(deviceId: deviceId)
        }
    }

    class DisconnectDevice: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let deviceId = parameters["deviceId"] as? String ?? ""
            return BleManager.shared.disconnectDevice(deviceId: deviceId)
        }
    }

    class ReadCharacteristic: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let deviceId = parameters["deviceId"] as? String ?? ""
            let serviceUuid = parameters["serviceUuid"] as? String ?? ""
            let characteristicUuid = parameters["characteristicUuid"] as? String ?? ""

            return BleManager.shared.readCharacteristic(
                deviceId: deviceId,
                serviceUuid: serviceUuid,
                characteristicUuid: characteristicUuid
            )
        }
    }

    class WriteCharacteristic: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let deviceId = parameters["deviceId"] as? String ?? ""
            let serviceUuid = parameters["serviceUuid"] as? String ?? ""
            let characteristicUuid = parameters["characteristicUuid"] as? String ?? ""
            let value = parameters["value"] as? String ?? ""
            let withoutResponse = parameters["withoutResponse"] as? Bool ?? false

            return BleManager.shared.writeCharacteristic(
                deviceId: deviceId,
                serviceUuid: serviceUuid,
                characteristicUuid: characteristicUuid,
                value: value,
                withoutResponse: withoutResponse
            )
        }
    }

    class SetNotification: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let deviceId = parameters["deviceId"] as? String ?? ""
            let serviceUuid = parameters["serviceUuid"] as? String ?? ""
            let characteristicUuid = parameters["characteristicUuid"] as? String ?? ""
            let enable = parameters["enable"] as? Bool ?? false

            return BleManager.shared.setNotification(
                deviceId: deviceId,
                serviceUuid: serviceUuid,
                characteristicUuid: characteristicUuid,
                enable: enable
            )
        }
    }
}
