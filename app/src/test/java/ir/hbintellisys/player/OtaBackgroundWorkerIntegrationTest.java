package ir.hbintellisys.player;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The production worker requires an Android Context for PackageManager-based
 * identity verification. JVM fake APK bytes cannot pass that Android gate.
 * Full staging + identity verification must run as an Android instrumented test.
 */
public class OtaBackgroundWorkerIntegrationTest {
    @Test public void workerRejectsMissingAndroidContextBeforeScheduling() throws Exception {
        OtaBackgroundWorker worker = new OtaBackgroundWorker();
        try {
            try {
                worker.stageApprovedRelease(null, null, null, null);
                fail("Expected missing context rejection");
            } catch (IllegalArgumentException expected) {
                assertEquals("context required", expected.getMessage());
            }
            assertFalse(worker.isRunning());
        } finally {
            worker.shutdown();
        }
    }
}
