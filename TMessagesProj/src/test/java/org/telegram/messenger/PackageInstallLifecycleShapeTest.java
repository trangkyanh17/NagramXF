package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class PackageInstallLifecycleShapeTest {

    private static String source(String relative) throws Exception {
        Path[] candidates = {
                Paths.get(relative),
                Paths.get("TMessagesProj").resolve(relative)
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(relative + " not found");
    }

    @Test
    public void uninstallDoesNotRetainPackageDataButAuthTokenBackupRemainsAvailable() throws Exception {
        String manifest = source("src/main/AndroidManifest.xml");

        assertTrue(manifest.contains("android:hasFragileUserData=\"false\""));
        assertFalse(manifest.contains("android:hasFragileUserData=\"true\""));

        // Preserve the existing Telegram token-backup feature. The fix must only
        // stop Android from retaining the whole package/data after uninstall.
        assertTrue(manifest.contains("android:allowBackup=\"true\""));
        assertTrue(manifest.contains("android:backupAgent=\".BackupAgent\""));
    }
}
