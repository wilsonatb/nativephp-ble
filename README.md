# NativePHP BLE Plugin

Bluetooth Low Energy (BLE) plugin for NativePHP Mobile with a practical GATT workflow:

## Features at a glance

| Feature | Android | iOS |
|---|---|---|
| Device scan | ✅ | ✅ |
| Connect / Disconnect | ✅ | ✅ |
| Characteristic read | ✅ | ✅ |
| Characteristic write | ✅ | ✅ |
| Notifications / indications | ✅ | ✅ |
| Native events to PHP (`#[OnNative]`) | ✅ | ✅ |
| Native events to JS (`on(...)`) | ✅ | ✅ |

## Great fit for

BLE sensors, IoT control panels, smart-home dashboards, beacon workflows, device provisioning tools, QA BLE test apps, and custom GATT diagnostics.

## Installation

```bash
composer require wilsonatb/nativephp-ble

# First time only (creates/updates NativePluginsServiceProvider)
php artisan vendor:publish --tag=nativephp-plugins-provider

# Register plugin
php artisan native:plugin:register wilsonatb/nativephp-ble

# Verify
php artisan native:plugin:list
```

This registers `Nativephp\Ble\BleServiceProvider`.

## Platform requirements

### Android

- Min SDK: 26
- Runtime permissions are required
- Bluetooth must be enabled

Permissions used by this plugin:

- `android.permission.BLUETOOTH_SCAN` (Android 12+)
- `android.permission.BLUETOOTH_CONNECT` (Android 12+)
- `android.permission.BLUETOOTH` (legacy fallback)
- `android.permission.BLUETOOTH_ADMIN` (legacy fallback)
- `android.permission.ACCESS_FINE_LOCATION` (legacy scan fallback)

### iOS

- Min iOS: 16.0
- `NSBluetoothAlwaysUsageDescription`
- `NSBluetoothPeripheralUsageDescription`

## PHP API reference

```php
use Nativephp\Ble\Facades\Ble;
```

### Scan devices

```php
$result = Ble::scanDevices(['duration' => 5000]);
```

Immediate result examples:

- `['status' => 'scanning_started', 'duration' => 5000]`
- `['status' => 'already_scanning']`
- `['status' => 'permission_requested', 'message' => '...']`
- `['status' => 'error', 'message' => '...']`

Final scan results arrive via `BleScanCompleted`.

### Connect to device

```php
$result = Ble::connectToDevice('AA:BB:CC:DD:EE:FF');
```

Immediate result:

- `['status' => 'connecting_started', 'connected' => false, 'deviceId' => '...']`

Ready state (connected + services discovered) arrives via `BleDeviceConnected`.

### Disconnect device

```php
$result = Ble::disconnectDevice('AA:BB:CC:DD:EE:FF');
```

Result:

- Success: `['disconnected' => true, 'deviceId' => '...']`
- Error: `['status' => 'error', 'message' => 'Device is not connected']`

### Read characteristic

```php
$result = Ble::readCharacteristic(
    deviceId: 'AA:BB:CC:DD:EE:FF',
    serviceUuid: '1111',
    characteristicUuid: '2222'
);
```

Immediate result:

- `['status' => 'read_started', ...]` or `['status' => 'error', 'message' => '...']`

Value arrives via `BleCharacteristicRead`.

### Write characteristic

```php
$result = Ble::writeCharacteristic(
    deviceId: 'AA:BB:CC:DD:EE:FF',
    serviceUuid: '1111',
    characteristicUuid: '3333',
    value: 'FF00AA', // Hex string
    withoutResponse: false
);
```

Immediate result:

- `['written' => true, ...]` or `['status' => 'error', 'message' => '...']`

Write completion is emitted via `BleCharacteristicWritten`.

### Configure notification / indication

```php
$result = Ble::setNotification(
    deviceId: 'AA:BB:CC:DD:EE:FF',
    serviceUuid: '1111',
    characteristicUuid: '4444',
    enable: true
);
```

Result:

- `['notificationEnabled' => true, ...]` or `['status' => 'error', 'message' => '...']`

Incoming values are emitted via `BleCharacteristicRead`.

## Livewire example

```php
<?php

namespace App\Livewire;

use Livewire\Component;
use Native\Mobile\Attributes\OnNative;
use Nativephp\Ble\Events\BleCharacteristicRead;
use Nativephp\Ble\Events\BleCharacteristicWritten;
use Nativephp\Ble\Events\BleDeviceConnected;
use Nativephp\Ble\Events\BleScanCompleted;
use Nativephp\Ble\Facades\Ble;

class BleDemo extends Component
{
    public array $devices = [];
    public ?string $deviceId = null;
    public ?string $message = null;
    public ?string $lastReadHex = null;
    public ?string $lastWriteHex = null;

    public function scan(): void
    {
        $result = Ble::scanDevices(['duration' => 5000]);

        if (($result['status'] ?? null) === 'permission_requested') {
            $this->message = 'Bluetooth permission requested.';
        }
    }

    public function connect(string $deviceId): void
    {
        $this->deviceId = $deviceId;
        Ble::connectToDevice($deviceId);
    }

    #[OnNative(BleScanCompleted::class)]
    public function onScanCompleted(array $devices, ?string $error = null): void
    {
        $this->devices = $devices;
        $this->message = $error ?: 'Scan completed.';
    }

    #[OnNative(BleDeviceConnected::class)]
    public function onConnected(string $deviceId, bool $connected, ?string $error = null): void
    {
        $this->message = $connected
            ? "Connected and ready: {$deviceId}"
            : "Connection failed: ".($error ?? 'Unknown error');
    }

    #[OnNative(BleCharacteristicRead::class)]
    public function onRead(string $deviceId, string $serviceUuid, string $characteristicUuid, string $value, ?string $error = null): void
    {
        $this->message = $error ? "Read error: {$error}" : "Read value: 0x{$value}";
        $this->lastReadHex = $error ? null : $value;
    }

    #[OnNative(BleCharacteristicWritten::class)]
    public function onWritten(string $deviceId, string $serviceUuid, string $characteristicUuid, string $value, ?string $error = null): void
    {
        $this->message = $error ? "Write error: {$error}" : "Write confirmed: 0x{$value}";
        $this->lastWriteHex = $error ? null : $value;
    }
}
```

## JavaScript API

The plugin ships with `resources/js/Ble.js`.

```javascript
import { Ble, Events } from '@wilsonatb/nativephp-ble';
import { on, off } from '@nativephp/native';
```

### JS methods

```javascript
await Ble.scanDevices({ duration: 5000 });
await Ble.connectToDevice('AA:BB:CC:DD:EE:FF');
await Ble.disconnectDevice('AA:BB:CC:DD:EE:FF');

await Ble.readCharacteristic(
  'AA:BB:CC:DD:EE:FF',
  '1111',
  '2222'
);

await Ble.writeCharacteristic(
  'AA:BB:CC:DD:EE:FF',
  '1111',
  '3333',
  'FF00AA',
  false // withoutResponse
);

await Ble.setNotification(
  'AA:BB:CC:DD:EE:FF',
  '1111',
  '4444',
  true
);
```

### JS event listening

```javascript
import { Ble, Events } from '@wilsonatb/nativephp-ble';
import { on, off } from '@nativephp/native';

const onScanCompleted = (devices, error = null) => {
  if (error) {
    console.error('Scan failed:', error);
    return;
  }
  console.log('Discovered devices:', devices);
};

const onConnected = (deviceId, connected, error = null) => {
  console.log('Connection event:', { deviceId, connected, error });
};

const onRead = (deviceId, serviceUuid, characteristicUuid, value, error = null) => {
  console.log('Read event:', { deviceId, serviceUuid, characteristicUuid, value, error });
};

const onWritten = (deviceId, serviceUuid, characteristicUuid, value, error = null) => {
  console.log('Write event:', { deviceId, serviceUuid, characteristicUuid, value, error });
};

on(Events.BleScanCompleted, onScanCompleted);
on(Events.BleDeviceConnected, onConnected);
on(Events.BleCharacteristicRead, onRead);
on(Events.BleCharacteristicWritten, onWritten);

// Cleanup when leaving the screen/component:
off(Events.BleScanCompleted, onScanCompleted);
off(Events.BleDeviceConnected, onConnected);
off(Events.BleCharacteristicRead, onRead);
off(Events.BleCharacteristicWritten, onWritten);
```

## Native events

| Event | Payload |
|---|---|
| `Nativephp\Ble\Events\BleScanCompleted` | `devices: array`, `error: ?string` |
| `Nativephp\Ble\Events\BleDeviceConnected` | `deviceId: string`, `connected: bool`, `error: ?string` |
| `Nativephp\Ble\Events\BleCharacteristicRead` | `deviceId: string`, `serviceUuid: string`, `characteristicUuid: string`, `value: string (hex)`, `error: ?string` |
| `Nativephp\Ble\Events\BleCharacteristicWritten` | `deviceId: string`, `serviceUuid: string`, `characteristicUuid: string`, `value: string (hex)`, `error: ?string` |

## Supported UUID formats

For `serviceUuid` and `characteristicUuid`:

- 16-bit: `1111`
- 32-bit: `00001111`
- 128-bit UUID (with or without dashes)

Short UUIDs are normalized to BLE base format internally.

## Recommended flow

1. Start scan (`scanDevices`), handle `permission_requested` if returned.
2. Wait for `BleScanCompleted` and pick a connectable device.
3. Connect (`connectToDevice`) and wait for `BleDeviceConnected` with `connected=true`.
4. Perform read/write/notify operations.
5. Handle final async results via `BleCharacteristicRead` and `BleCharacteristicWritten`.
6. Disconnect when done.

## Notes

- Scan is non-blocking.
- Successful connect means services are discovered and device is ready.
- Read/write/notify require connected + ready state.
- `value` in writes must be hexadecimal (`FF00AA`, `01`, etc.).
- `withoutResponse=true` uses write command mode.
- Notification setup writes CCCD and uses indication fallback when needed.

## Troubleshooting

- **`permission_requested`**: accept native permission prompts and retry scan.
- **`Device is not connected`**: wait for `BleDeviceConnected` with `connected=true`.
- **`Service not found` / `Characteristic not found`**: verify UUIDs and target GATT profile.
- **No visible read/write result**: check event listeners for `BleCharacteristicRead` / `BleCharacteristicWritten`.

## License

MIT
