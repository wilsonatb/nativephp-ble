<?php

declare(strict_types=1);

namespace Nativephp\Ble;

final class Ble
{
    /**
     * Scan for nearby BLE devices
     */
    public function scanDevices(array $options = []): mixed
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Ble.scanDevices', json_encode($options));

            return $this->decodeResponse($result);
        }

        return null;
    }

    /**
     * Connect to a BLE device
     */
    public function connectToDevice(string $deviceId, array $options = []): mixed
    {
        if (function_exists('nativephp_call')) {
            $params = array_merge(['deviceId' => $deviceId], $options);
            $result = nativephp_call('Ble.connectToDevice', json_encode($params));

            return $this->decodeResponse($result);
        }

        return null;
    }

    /**
     * Disconnect from a BLE device
     */
    public function disconnectDevice(string $deviceId): mixed
    {
        if (function_exists('nativephp_call')) {
            $result = nativephp_call('Ble.disconnectDevice', json_encode(['deviceId' => $deviceId]));

            return $this->decodeResponse($result);
        }

        return null;
    }

    /**
     * Read a BLE characteristic
     */
    public function readCharacteristic(string $deviceId, string $serviceUuid, string $characteristicUuid): mixed
    {
        if (function_exists('nativephp_call')) {
            $params = [
                'deviceId' => $deviceId,
                'serviceUuid' => $serviceUuid,
                'characteristicUuid' => $characteristicUuid,
            ];
            $result = nativephp_call('Ble.readCharacteristic', json_encode($params));

            return $this->decodeResponse($result);
        }

        return null;
    }

    /**
     * Write to a BLE characteristic
     */
    public function writeCharacteristic(string $deviceId, string $serviceUuid, string $characteristicUuid, string $value, bool $withoutResponse = false): mixed
    {
        if (function_exists('nativephp_call')) {
            $params = [
                'deviceId' => $deviceId,
                'serviceUuid' => $serviceUuid,
                'characteristicUuid' => $characteristicUuid,
                'value' => $value,
                'withoutResponse' => $withoutResponse,
            ];
            $result = nativephp_call('Ble.writeCharacteristic', json_encode($params));

            return $this->decodeResponse($result);
        }

        return null;
    }

    /**
     * Enable/disable notifications for a characteristic
     */
    public function setNotification(string $deviceId, string $serviceUuid, string $characteristicUuid, bool $enable): mixed
    {
        if (function_exists('nativephp_call')) {
            $params = [
                'deviceId' => $deviceId,
                'serviceUuid' => $serviceUuid,
                'characteristicUuid' => $characteristicUuid,
                'enable' => $enable,
            ];
            $result = nativephp_call('Ble.setNotification', json_encode($params));

            return $this->decodeResponse($result);
        }

        return null;
    }

    private function decodeResponse(?string $result): mixed
    {
        if (! $result) {
            return null;
        }

        $decoded = json_decode($result, true);

        if (! is_array($decoded)) {
            return null;
        }

        if (($decoded['status'] ?? null) === 'error') {
            return $decoded;
        }

        if (array_key_exists('data', $decoded) && ! array_key_exists('status', $decoded)) {
            return $decoded['data'];
        }

        return $decoded;
    }
}
