package ir.hbintellisys.player;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Offline-only integration: approved manifest -> worker -> fake HTTPS -> private staging. */
public class OtaBackgroundWorkerIntegrationTest {
    @Test public void approvedManifestStagesExactBytesWithoutInstalling() throws Exception {
        byte[] bytes = "lab-apk-payload".getBytes("UTF-8");
        OtaStagedDownloadIntegrationTest.FakeServer server =
                new OtaStagedDownloadIntegrationTest.FakeServer();
        server.responses.add(new OtaStagedDownloadIntegrationTest.Response(
                200, bytes, bytes.length, "\"lab-v9\"", null));
        OtaReleaseContract.Approved approved = OtaReleaseContract.validate(
                "update_available", "approved", 8, 9, "lab_9",
                "https://ota.example.invalid/file.apk",
                OtaStagedDownloadIntegrationTest.sha(bytes), bytes.length);
        // Replace only the URL transport with a deterministic fake HTTPS handler.
        OtaReleaseContract.Approved testApproved = new OtaReleaseContract.Approved(
                approved.releaseId, approved.versionCode, server.url(),
                approved.sha256, approved.sizeBytes);
        File dir = Files.createTempDirectory("hb-ota-worker-").toFile();
        OtaBackgroundWorker worker = new OtaBackgroundWorker();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<File> staged = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            assertFalse(worker.isRunning());
            worker.stageApprovedRelease(testApproved, dir, new OtaBackgroundWorker.Callback() {
                @Override public void onStaged(File apk) { staged.set(apk); done.countDown(); }
                @Override public void onFailure(Exception error) { failure.set(error); done.countDown(); }
            });
            assertTrue("Worker timed out", done.await(5, TimeUnit.SECONDS));
            assertNull("Unexpected worker failure: " + failure.get(), failure.get());
            assertNotNull(staged.get());
            assertArrayEquals(bytes, Files.readAllBytes(staged.get().toPath()));
            assertEquals("lab_9.apk", staged.get().getName());
            assertEquals(1, server.connections.size());
        } finally {
            worker.shutdown();
        }
    }
}
