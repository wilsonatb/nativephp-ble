<?php

declare(strict_types=1);

namespace Nativephp\Ble\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static mixed scanDevices(array $options = [])
 * @method static mixed connectToDevice(string $deviceId, array $options = [])
 * @method static mixed disconnectDevice(string $deviceId)
 * @method static mixed readCharacteristic(string $deviceId, string $serviceUuid, string $characteristicUuid)
 * @method static mixed writeCharacteristic(string $deviceId, string $serviceUuid, string $characteristicUuid, string $value, bool $withoutResponse = false)
 * @method static mixed setNotification(string $deviceId, string $serviceUuid, string $characteristicUuid, bool $enable)
 *
 * @see \Nativephp\Ble\Ble
 */
final class Ble extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Nativephp\Ble\Ble::class;
    }
}
