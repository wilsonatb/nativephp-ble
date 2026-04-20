# Changelog

All notable changes to the NativePHP BLE plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial release with basic BLE functionality
- Device scanning with duration parameter
- Device connection/disconnection
- Characteristic read/write operations
- Notifications/indications support
- Native events for all async operations
- JavaScript API module
- Boost AI guidelines
- Android permissions for Bluetooth
- iOS Info.plist configurations

### Bridge Functions
- `Ble.scanDevices` - Scan for nearby BLE devices
- `Ble.connectToDevice` - Connect to a BLE device
- `Ble.disconnectDevice` - Disconnect from a BLE device
- `Ble.readCharacteristic` - Read a BLE characteristic
- `Ble.writeCharacteristic` - Write to a BLE characteristic
- `Ble.setNotification` - Enable/disable notifications

### Events
- `BleScanCompleted` - Emitted when device scan completes
- `BleDeviceConnected` - Emitted when device connection changes
- `BleCharacteristicRead` - Emitted when characteristic value is read
- `BleCharacteristicWritten` - Emitted when characteristic write completes

### Platform Support
- Android: API 26+ (Android 8.0 Oreo)
- iOS: 16.0+
- PHP: 8.2+
- NativePHP Mobile: ^3.0