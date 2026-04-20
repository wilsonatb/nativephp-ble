<?php

declare(strict_types=1);

namespace Nativephp\Ble\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

final class BleScanCompleted
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public array $devices,
        public ?string $error = null
    ) {}
}
