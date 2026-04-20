<?php

declare(strict_types=1);

namespace Nativephp\Ble\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

final class BleDeviceConnected
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $deviceId,
        public bool $connected,
        public ?string $error = null
    ) {}
}
