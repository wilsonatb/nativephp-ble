<?php

declare(strict_types=1);

namespace Nativephp\Ble;

use Illuminate\Support\ServiceProvider;
use Nativephp\Ble\Commands\CopyAssetsCommand;

final class BleServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(Ble::class, function () {
            return new Ble();
        });
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->commands([
                CopyAssetsCommand::class,
            ]);
        }
    }
}
