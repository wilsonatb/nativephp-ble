## wilsonatb/nativephp-ble

A NativePHP plugin for Bluetooth Low Energy (BLE) functionality including device scanning and connection.

### Installation

```bash
# Install the package
composer require wilsonatb/nativephp-ble

# Publish the plugins provider (first time only)
php artisan vendor:publish --tag=nativephp-plugins-provider

# Register the plugin (adds \Nativephp\Ble\BleServiceProvider::class)
php artisan native:plugin:register wilsonatb/nativephp-ble

# Verify registration
php artisan native:plugin:list
```

### Requirements

#### Android Permissions
- `android.permission.BLUETOOTH`
- `android.permission.BLUETOOTH_ADMIN`
- `android.permission.ACCESS_FINE_LOCATION`

#### iOS Permissions
- `NSBluetoothAlwaysUsageDescription`
- `NSBluetoothPeripheralUsageDescription`

### Usage

#### PHP (Livewire/Blade)

Use the `Ble` facade:

@verbatim
<code-snippet name="Using Ble Facade" lang="php">
use Nativephp\Ble\Facades\Ble;

// Scan for nearby BLE devices
$devices = Ble::scanDevices(['duration' => 5000]);

// Connect to a device
$connection = Ble::connectToDevice('00:11:22:33:44:55');

// Disconnect from a device
Ble::disconnectDevice('00:11:22:33:44:55');

// Read a characteristic
$value = Ble::readCharacteristic(
    deviceId: '00:11:22:33:44:55',
    serviceUuid: '0000180f-0000-1000-8000-00805f9b34fb',
    characteristicUuid: '00002a19-0000-1000-8000-00805f9b34fb'
);

// Write to a characteristic
Ble::writeCharacteristic(
    deviceId: '00:11:22:33:44:55',
    serviceUuid: '0000180f-0000-1000-8000-00805f9b34fb',
    characteristicUuid: '00002a19-0000-1000-8000-00805f9b34fb',
    value: '01',
    withoutResponse: false
);

// Enable notifications
Ble::setNotification(
    deviceId: '00:11:22:33:44:55',
    serviceUuid: '0000180f-0000-1000-8000-00805f9b34fb',
    characteristicUuid: '00002a19-0000-1000-8000-00805f9b34fb',
    enable: true
);
</code-snippet>
@endverbatim

### Available Methods

- `Ble::scanDevices(array $options = [])` - Scan for nearby BLE devices
- `Ble::connectToDevice(string $deviceId, array $options = [])` - Connect to a BLE device
- `Ble::disconnectDevice(string $deviceId)` - Disconnect from a BLE device
- `Ble::readCharacteristic(string $deviceId, string $serviceUuid, string $characteristicUuid)` - Read a BLE characteristic
- `Ble::writeCharacteristic(string $deviceId, string $serviceUuid, string $characteristicUuid, string $value, bool $withoutResponse = false)` - Write to a BLE characteristic
- `Ble::setNotification(string $deviceId, string $serviceUuid, string $characteristicUuid, bool $enable)` - Enable/disable notifications for a characteristic

### Events

- `BleScanCompleted` - Listen with `#[OnNative(BleScanCompleted::class)]`
- `BleDeviceConnected` - Listen with `#[OnNative(BleDeviceConnected::class)]`
- `BleCharacteristicRead` - Listen with `#[OnNative(BleCharacteristicRead::class)]`

@verbatim
<code-snippet name="Listening for Ble Events" lang="php">
use Native\Mobile\Attributes\OnNative;
use Nativephp\Ble\Events\BleScanCompleted;
use Nativephp\Ble\Events\BleDeviceConnected;
use Nativephp\Ble\Events\BleCharacteristicRead;

#[OnNative(BleScanCompleted::class)]
public function handleBleScanCompleted($devices, $error = null)
{
    // Handle scan completion
}

#[OnNative(BleDeviceConnected::class)]
public function handleBleDeviceConnected($deviceId, $connected, $error = null)
{
    // Handle connection events
}

#[OnNative(BleCharacteristicRead::class)]
public function handleBleCharacteristicRead($deviceId, $serviceUuid, $characteristicUuid, $value, $error = null)
{
    // Handle characteristic reads
}
</code-snippet>
@endverbatim

### JavaScript Usage (Vue/React/Inertia)

@verbatim
<code-snippet name="Using Ble in JavaScript" lang="javascript">
import { Ble, Events } from '@wilsonatb/nativephp-ble';
import { on, off } from '@nativephp/native';

// Scan for devices
const result = await Ble.scanDevices({ duration: 5000 });
console.log('Found devices:', result.devices);

// Connect to a device
await Ble.connectToDevice('00:11:22:33:44:55');

// Listen for events using exported constants
const scanHandler = (devices) => {
    console.log('Scan completed:', devices);
};
on(Events.BleScanCompleted, scanHandler);

// Clean up
off(Events.BleScanCompleted, scanHandler);
</code-snippet>
@endverbatim

### Platform Notes

**Android:**
- Requires Android 5.0 (API 21) or higher for BLE support
- Location permission is required for scanning on Android 6.0+
- Bluetooth must be enabled on the device

**iOS:**
- Requires iOS 10.0 or higher
- Requires `NSBluetoothAlwaysUsageDescription` and `NSBluetoothPeripheralUsageDescription` in Info.plist
- Bluetooth permissions must be granted by the user

### Testing

The plugin includes stub implementations for both Android and iOS. For production use, you may need to extend the native implementations with your specific BLE device requirements.

### License

MIT