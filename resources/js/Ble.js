/**
 * Ble Plugin for NativePHP Mobile
 *
 * @example
 * import { Ble, Events } from '@wilsonatb/nativephp-ble';
 *
 * // Scan for devices
 * const result = await Ble.scanDevices({ duration: 5000 });
 *
 * // Connect to device
 * await Ble.connectToDevice('device-id');
 *
 * // Listen for events
 * import { on } from '@nativephp/native';
 * on(Events.BleScanCompleted, (devices) => {
 *     console.log('Devices found:', devices);
 * });
 */

const baseUrl = '/_native/api/call';

async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="csrf-token"]')?.content || ''
        },
        body: JSON.stringify({ method, params })
    });

    const result = await response.json();

    if (result.status === 'error') {
        throw new Error(result.message || 'Native call failed');
    }

    const nativeResponse = result.data;
    if (nativeResponse && nativeResponse.data !== undefined) {
        return nativeResponse.data;
    }

    return nativeResponse;
}

async function scanDevices(options = {}) {
    return bridgeCall('Ble.scanDevices', options);
}

async function connectToDevice(deviceId, options = {}) {
    return bridgeCall('Ble.connectToDevice', { deviceId, ...options });
}

async function disconnectDevice(deviceId) {
    return bridgeCall('Ble.disconnectDevice', { deviceId });
}

async function readCharacteristic(deviceId, serviceUuid, characteristicUuid) {
    return bridgeCall('Ble.readCharacteristic', { deviceId, serviceUuid, characteristicUuid });
}

async function writeCharacteristic(deviceId, serviceUuid, characteristicUuid, value, withoutResponse = false) {
    return bridgeCall('Ble.writeCharacteristic', {
        deviceId,
        serviceUuid,
        characteristicUuid,
        value,
        withoutResponse
    });
}

async function setNotification(deviceId, serviceUuid, characteristicUuid, enable) {
    return bridgeCall('Ble.setNotification', {
        deviceId,
        serviceUuid,
        characteristicUuid,
        enable
    });
}

// PascalCase for exported namespace object
export const Ble = {
    scanDevices,
    connectToDevice,
    disconnectDevice,
    readCharacteristic,
    writeCharacteristic,
    setNotification
};

// Event constants - use these instead of hardcoded strings
export const Events = {
    BleScanCompleted: 'Nativephp\\Ble\\Events\\BleScanCompleted',
    BleDeviceConnected: 'Nativephp\\Ble\\Events\\BleDeviceConnected',
    BleCharacteristicRead: 'Nativephp\\Ble\\Events\\BleCharacteristicRead',
    BleCharacteristicWritten: 'Nativephp\\Ble\\Events\\BleCharacteristicWritten'
};

export default Ble;
