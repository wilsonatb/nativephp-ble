<?php

declare(strict_types=1);

namespace Nativephp\Ble\Commands;

use Native\Mobile\Plugins\Commands\NativePluginHookCommand;

final class CopyAssetsCommand extends NativePluginHookCommand
{
    protected $signature = 'nativephp:ble:copy-assets';

    protected $description = 'Copy assets for Ble plugin';

    public function handle(): int
    {
        if ($this->isAndroid()) {
            $this->copyAndroidAssets();
        }

        if ($this->isIos()) {
            $this->copyIosAssets();
        }

        return self::SUCCESS;
    }

    protected function copyAndroidAssets(): void
    {
        // Copy any Android assets if needed
        $this->info('Android assets copied for Ble plugin');
    }

    protected function copyIosAssets(): void
    {
        // Copy any iOS assets if needed
        $this->info('iOS assets copied for Ble plugin');
    }
}
