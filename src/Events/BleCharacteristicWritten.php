<?php

declare(strict_types=1);

namespace Nativephp\Ble\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

final class BleCharacteristicWritten
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $deviceId,
        public string $serviceUuid,
        public string $characteristicUuid,
        public string $value,
        public ?string $error = null
    ) {}
}
