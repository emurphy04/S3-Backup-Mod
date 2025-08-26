package freesmelly.s3backup;

import net.fabricmc.api.DedicatedServerModInitializer;

public final class BackupBootstrap implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        BackupService.bootstrap();
    }
}
